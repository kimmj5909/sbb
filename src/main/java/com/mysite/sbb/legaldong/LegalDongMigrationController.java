package com.mysite.sbb.legaldong;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpSession;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/legal-dong")
@PreAuthorize("hasRole('ADMIN')")
/**
 * 법정동 코드 업로드(엑셀) 관리자 기능.
 *
 * 주의: 클래스 레벨 @RequestMapping("/admin/legal-dong")을 기준으로
 * 메서드 매핑은 "/migration" 등 상대 경로만 사용해 이중 경로가 생성되지 않도록 한다.
 *
 * 미리보기 페이징 방식
 * - 브라우저는 파일 input 값을 페이지 이동 시 유지하지 않으므로, 업로드된 파일 바이트를 세션에 임시 저장한다.
 * - 이후 `/migration/preview?page=N` 요청에서 세션 데이터를 사용해 N페이지(20개 단위)만 화면에 표시한다.
 */
public class LegalDongMigrationController {

	private static final Logger log = LoggerFactory.getLogger(LegalDongMigrationController.class);

	private static final String SESSION_KEY_PREVIEW_BYTES = "legalDongMigrationPreviewBytes";
	private static final String SESSION_KEY_PREVIEW_FILENAME = "legalDongMigrationPreviewFileName";
	private static final String SESSION_KEY_LAST_RUN_ID = "legalDongMigrationLastRunId";
	private static final String SESSION_KEY_PREVIEW_CACHE = "legalDongMigrationPreviewCache";
	private static final java.time.format.DateTimeFormatter YYYYMMDD = java.time.format.DateTimeFormatter.BASIC_ISO_DATE;

	private final LegalDongMigrationService migrationService;
	private final LegalDongJdbcSnapshotRepository snapshotRepository;
	private final LegalDongMigrationRunRepository migrationRunRepository;

	private record PreviewCache(
			int totalRows,
			int derivedRows,
			int upperRows,
			int lowerRows,
			List<String> errors,
			List<LegalDongDerivedRow> entries) {
	}

	@GetMapping("/migration")
	public String migrationPage() {
		return "admin/legal_dong_migration";
	}

	@GetMapping("/migration/preview")
	public String previewPage(
			@RequestParam(value = "page", defaultValue = "0") int page,
			@RequestParam(value = "size", defaultValue = "50") int size,
			Model model,
			HttpSession session,
			RedirectAttributes redirectAttributes) {
		long started = System.nanoTime();
		String fileName = (String) session.getAttribute(SESSION_KEY_PREVIEW_FILENAME);
		int resolvedSize = resolvePreviewSize(size);

		PreviewCache cache = (PreviewCache) session.getAttribute(SESSION_KEY_PREVIEW_CACHE);
		if (cache == null || cache.entries == null || cache.entries.isEmpty()) {
			byte[] bytes = (byte[]) session.getAttribute(SESSION_KEY_PREVIEW_BYTES);
			if (bytes == null || bytes.length == 0) {
				redirectAttributes.addFlashAttribute("adminMessage", "미리보기 데이터가 없습니다. 엑셀 파일을 다시 업로드하세요.");
				log.info("[legaldong-migration][preview] redirect(no session bytes) page={}, size={}", page, resolvedSize);
				return "redirect:/admin/legal-dong/migration";
			}

			long t0 = System.nanoTime();
			// 미리보기는 페이징 이동이 있을 수 있어, 매 요청마다 엑셀을 재파싱하지 않도록
			// 최초 1회만 전체 파생 결과를 만들어 세션에 캐시한다.
			LegalDongMigrationPreviewResult all = migrationService.preview(bytes, fileName, 0, Integer.MAX_VALUE);
			long t1 = System.nanoTime();
			cache = new PreviewCache(
					all.getTotalRows(),
					all.getDerivedRows(),
					all.getUpperRows(),
					all.getLowerRows(),
					all.getErrors(),
					all.getEntries());
			session.setAttribute(SESSION_KEY_PREVIEW_CACHE, cache);
			log.info("[legaldong-migration][preview] cache_miss parsed fileName={}, bytes={}, derivedRows={}, tookMs={}",
					fileName, bytes.length, all.getDerivedRows(), (t1 - t0) / 1_000_000);
		}

		long sliceStarted = System.nanoTime();
		LegalDongMigrationPreviewResult preview = buildPreviewFromCache(cache, page, resolvedSize);
		long sliceEnded = System.nanoTime();
		model.addAttribute("preview", preview);
		model.addAttribute("fileName", fileName != null ? fileName : "(업로드 파일)");
		model.addAttribute("pageNumbers", buildPageNumbers(preview));
		model.addAttribute("sizeOptions", List.of(50, 200, 400));

		long pastStarted = System.nanoTime();
		model.addAttribute("pastByCode", buildPastByCode(preview.getEntries()));
		long pastEnded = System.nanoTime();
		// 미리보기에서 "생성/말소일자 정합성"을 함께 확인할 수 있도록,
		// DB 스냅샷 기준으로 이번 업로드로 cr_dt(LEAST) / dlt_dt(GREATEST)가 갱신될지 여부를 계산해 내려준다.
		long dateStarted = System.nanoTime();
		model.addAttribute("dateStatusByCode", buildDateStatusByCode(preview.getEntries()));
		long dateEnded = System.nanoTime();

		long ended = System.nanoTime();
		log.info("[legaldong-migration][preview] page={}, size={}, entries={}, sliceMs={}, pastMs={}, dateMs={}, totalMs={}",
				preview.getPage(), preview.getSize(), preview.getEntries() == null ? 0 : preview.getEntries().size(),
				(sliceEnded - sliceStarted) / 1_000_000,
				(pastEnded - pastStarted) / 1_000_000,
				(dateEnded - dateStarted) / 1_000_000,
				(ended - started) / 1_000_000);
		return "admin/legal_dong_migration";
	}

	private int resolvePreviewSize(int size) {
		if (size == 50 || size == 200 || size == 400) {
			return size;
		}
		// UI 제공값 외 입력은 기본값으로 방어한다.
		return 50;
	}

	private Map<String, String> buildPastByCode(List<LegalDongDerivedRow> entries) {
		if (entries == null || entries.isEmpty()) {
			return Map.of();
		}

		List<String> codes = entries.stream()
				.map(LegalDongDerivedRow::getLegalDongCd)
				.filter(v -> v != null && v.isBlank() == false)
				.map(String::trim)
				.toList();

		Map<String, LegalDongJdbcSnapshotRepository.SnapshotRow> before = snapshotRepository.findByLegalDongCds(codes);

		java.util.Map<String, String> result = new java.util.HashMap<>();
		for (var e : before.entrySet()) {
			String code = e.getKey();
			var row = e.getValue();
			if (row == null) {
				continue;
			}
			result.put(code, row.pastLegalDongCd());
		}
		return java.util.Collections.unmodifiableMap(result);
	}

	private Map<String, DateStatus> buildDateStatusByCode(List<LegalDongDerivedRow> entries) {
		if (entries == null || entries.isEmpty()) {
			return Map.of();
		}

		List<String> codes = entries.stream()
				.map(LegalDongDerivedRow::getLegalDongCd)
				.filter(v -> v != null && v.isBlank() == false)
				.map(String::trim)
				.toList();

		Map<String, LegalDongJdbcSnapshotRepository.SnapshotRow> before;
		try {
			before = snapshotRepository.findByLegalDongCds(codes);
		} catch (Exception ex) {
			// 스냅샷 조회 실패(테이블 삭제/권한/연결 문제 등) 시, 미리보기 화면 자체는 유지한다.
			// - 이 경우 날짜 정합성 표시만 생략된다.
			return Map.of();
		}
		java.util.Map<String, DateStatus> result = new java.util.HashMap<>();

		for (LegalDongDerivedRow incoming : entries) {
			String code = incoming.getLegalDongCd();
			if (code == null || code.isBlank()) {
				continue;
			}

			LegalDongJdbcSnapshotRepository.SnapshotRow snapshot = before.get(code);
			String beforeCr = snapshot == null ? null : snapshot.crDt();
			String beforeDlt = snapshot == null ? null : snapshot.dltDt();
			String incomingCr = toYyyyMmDd(incoming.getCrDt());
			String incomingDlt = toYyyyMmDd(incoming.getDltDt());

			boolean crWillChange = compareMinChange(beforeCr, incomingCr);
			boolean dltWillChange = compareMaxChange(beforeDlt, incomingDlt);
			boolean isNew = snapshot == null;

			result.put(code, new DateStatus(isNew, beforeCr, beforeDlt, incomingCr, incomingDlt, crWillChange, dltWillChange));
		}

		return java.util.Collections.unmodifiableMap(result);
	}

	private boolean compareMaxChange(String before, String incoming) {
		// dlt_dt는 GREATEST 규칙이므로, incoming이 더 크면 변경된다.
		if (before == null) {
			return incoming != null;
		}
		if (incoming == null) {
			return false;
		}
		return incoming.compareTo(before) > 0;
	}

	private boolean compareMinChange(String before, String incoming) {
		// cr_dt는 LEAST 규칙이므로, incoming이 더 작으면 변경된다.
		if (before == null) {
			return incoming != null;
		}
		if (incoming == null) {
			return false;
		}
		return incoming.compareTo(before) < 0;
	}

	private String toYyyyMmDd(java.time.LocalDate value) {
		return value == null ? null : YYYYMMDD.format(value);
	}

	public record DateStatus(
			boolean isNew,
			String beforeCrDt,
			String beforeDltDt,
			String incomingCrDt,
			String incomingDltDt,
			boolean crWillChange,
			boolean dltWillChange) {
	}

	/**
	 * 미리보기 파생 결과 전체를 CSV로 다운로드한다.
	 *
	 * 주의
	 * - 미리보기는 페이지 단위로 표시되지만, CSV 다운로드는 전체 파생 결과를 내려준다.
	 * - 브라우저/엑셀에서 숫자로 해석하면 앞 0이 유실될 수 있으므로, "DB 적재용"으로 사용하는 것을 권장한다.
	 * - 파싱/검증 경고(errors)가 존재해도, 운영에서 델타 업서트/검증을 위해 CSV 다운로드가 필요한 경우가 있어 차단하지 않는다.
	 *   대신 CSV 헤더 아래에 `#WARN:` 라인으로 경고 메시지를 포함해, 사용자가 적재 전 확인할 수 있도록 한다.
	 */
	@GetMapping("/migration/preview/csv")
	public ResponseEntity<byte[]> downloadPreviewCsv(HttpSession session) {
		PreviewCache cache = (PreviewCache) session.getAttribute(SESSION_KEY_PREVIEW_CACHE);
		if (cache == null || cache.entries == null || cache.entries.isEmpty()) {
			return ResponseEntity.badRequest()
					.contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
					.body("미리보기 데이터가 없습니다. 엑셀 파일을 먼저 업로드하세요.".getBytes(StandardCharsets.UTF_8));
		}
		String originalFileName = (String) session.getAttribute(SESSION_KEY_PREVIEW_FILENAME);
		String csvFileName = toCsvFileName(originalFileName != null ? originalFileName : "legal_dong_migration.xlsx");

		StringBuilder csv = new StringBuilder(1024);
		// 경고가 있으면 CSV 상단에 주석 라인으로 포함한다(엑셀/psql 적재에는 영향 없음).
		if (cache.errors != null && cache.errors.isEmpty() == false) {
			for (String e : cache.errors) {
				if (e == null || e.isBlank()) {
					continue;
				}
				csv.append("#WARN: ").append(e.replace("\r", " ").replace("\n", " ")).append("\r\n");
			}
		}
		csv.append("legal_dong_cd,legal_dong_nm,ctprv_cd,ctprv_nm,sgng_cd,sgng_nm,emndn_cd,emndn_nm,li_cd,li_nm,rank,cr_dt,dlt_dt\r\n");
		for (LegalDongDerivedRow row : cache.entries) {
			appendCsvRow(csv, row);
			csv.append("\r\n");
		}

		// Excel이 UTF-8 CSV를 ANSI(로컬 코드페이지)로 오인해 한글이 깨지는 경우가 있어, UTF-8 BOM을 추가한다.
		// - DB 적재용으로 사용하는 경우에도 BOM은 일반적으로 무해하다.
		byte[] body = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);

		String fallbackName = toAsciiFileName(csvFileName);
		String encodedFileName = java.net.URLEncoder.encode(csvFileName, StandardCharsets.UTF_8).replace("+", "%20");
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"" + fallbackName + "\"; filename*=UTF-8''" + encodedFileName)
				.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
				.body(body);
	}

	@PostMapping("/migration/preview")
	public String preview(@RequestParam(value = "file", required = false) MultipartFile file, HttpSession session,
			RedirectAttributes redirectAttributes) {
		long started = System.nanoTime();
		byte[] bytes = readFileBytes(file, redirectAttributes);
		if (bytes == null) {
			return "redirect:/admin/legal-dong/migration";
		}

		session.setAttribute(SESSION_KEY_PREVIEW_BYTES, bytes);
		session.setAttribute(SESSION_KEY_PREVIEW_FILENAME, file.getOriginalFilename());
		// 업로드 직후 1회 파싱/파생 결과를 캐시해, 미리보기 페이징 이동 시 재파싱 비용을 제거한다.
		LegalDongMigrationPreviewResult all = migrationService.preview(bytes, file.getOriginalFilename(), 0, Integer.MAX_VALUE);
		PreviewCache cache = new PreviewCache(
				all.getTotalRows(),
				all.getDerivedRows(),
				all.getUpperRows(),
				all.getLowerRows(),
				all.getErrors(),
				all.getEntries());
		session.setAttribute(SESSION_KEY_PREVIEW_CACHE, cache);
		long ended = System.nanoTime();
		log.info("[legaldong-migration][preview-upload] fileName={}, bytes={}, derivedRows={}, tookMs={}",
				file.getOriginalFilename(), bytes.length, all.getDerivedRows(), (ended - started) / 1_000_000);

		return "redirect:/admin/legal-dong/migration/preview?page=0";
	}

	@PostMapping("/migration/apply")
	public String apply(@RequestParam(value = "file", required = false) MultipartFile file, Authentication authentication, Model model,
			HttpSession session, RedirectAttributes redirectAttributes) {
		byte[] bytes;
		String fileName;
		if (file != null && file.isEmpty() == false) {
			bytes = readFileBytes(file, redirectAttributes);
			fileName = file.getOriginalFilename();
		} else {
			bytes = (byte[]) session.getAttribute(SESSION_KEY_PREVIEW_BYTES);
			fileName = (String) session.getAttribute(SESSION_KEY_PREVIEW_FILENAME);
		}

		if (bytes == null || bytes.length == 0) {
			redirectAttributes.addFlashAttribute("adminMessage", "적용할 파일 데이터가 없습니다. 엑셀 파일을 다시 업로드하세요.");
			return "redirect:/admin/legal-dong/migration";
		}

		String operatorId = authentication != null ? authentication.getName() : "SYSTEM";
		LegalDongMigrationApplyResult result = migrationService.apply(bytes, fileName, operatorId);
		if (result != null && result.getRunId() != null && result.getRunId().isBlank() == false) {
			session.setAttribute(SESSION_KEY_LAST_RUN_ID, result.getRunId());
		}

		model.addAttribute("result", result);
		model.addAttribute("fileName", fileName != null ? fileName : "(업로드 파일)");
		session.removeAttribute(SESSION_KEY_PREVIEW_BYTES);
		session.removeAttribute(SESSION_KEY_PREVIEW_FILENAME);
		session.removeAttribute(SESSION_KEY_PREVIEW_CACHE);
		return "admin/legal_dong_migration";
	}

	private LegalDongMigrationPreviewResult buildPreviewFromCache(PreviewCache cache, int page, int size) {
		int resolvedSize = size > 0 ? size : 50;
		int resolvedPage = Math.max(page, 0);
		int totalPages = (int) Math.ceil((double) cache.entries.size() / (double) resolvedSize);
		int safeTotalPages = Math.max(totalPages, 1);
		int boundedPage = Math.min(resolvedPage, safeTotalPages - 1);
		int fromIndex = boundedPage * resolvedSize;
		int toIndex = Math.min(fromIndex + resolvedSize, cache.entries.size());
		List<LegalDongDerivedRow> pageEntries = fromIndex >= toIndex ? List.of() : cache.entries.subList(fromIndex, toIndex);

		return new LegalDongMigrationPreviewResult(
				cache.totalRows,
				cache.derivedRows,
				cache.upperRows,
				cache.lowerRows,
				boundedPage,
				resolvedSize,
				totalPages,
				cache.errors,
				pageEntries);
	}

	@PostMapping("/migration/rollback")
	public String rollback(@RequestParam(value = "runId", required = false) String runId, Authentication authentication, HttpSession session,
			RedirectAttributes redirectAttributes) {
		String operatorId = authentication != null ? authentication.getName() : "SYSTEM";

		String resolvedRunId = runId;
		if (resolvedRunId == null || resolvedRunId.isBlank()) {
			Object sessionRun = session.getAttribute(SESSION_KEY_LAST_RUN_ID);
			resolvedRunId = sessionRun == null ? null : sessionRun.toString();
		}

		if (resolvedRunId == null || resolvedRunId.isBlank()) {
			redirectAttributes.addFlashAttribute("adminMessage", "롤백할 실행(runId)이 없습니다. DB 적용 후 다시 시도하세요.");
			return "redirect:/admin/legal-dong/migration";
		}

		try {
			java.util.UUID uuid = java.util.UUID.fromString(resolvedRunId.trim());
			LegalDongMigrationRunRepository.RollbackResult r = migrationRunRepository.rollback(uuid, operatorId);
			if (r.message() != null) {
				redirectAttributes.addFlashAttribute("adminMessage", r.message());
			} else {
				redirectAttributes.addFlashAttribute("adminMessage",
						"롤백 완료(runId=" + resolvedRunId + "): 스냅샷 " + r.snapshotRows() + "건 중 복원 " + r.restoredRows()
								+ "건, 삭제 " + r.deletedRows() + "건");
			}
		} catch (Exception ex) {
			redirectAttributes.addFlashAttribute("adminMessage", "롤백 실패: " + ex.getMessage());
		}

		return "redirect:/admin/legal-dong/migration";
	}

	private byte[] readFileBytes(MultipartFile file, RedirectAttributes redirectAttributes) {
		if (file == null || file.isEmpty()) {
			redirectAttributes.addFlashAttribute("adminMessage", "업로드할 파일(xlsx/csv)을 선택하세요.");
			return null;
		}
		String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
		if (filename.endsWith(".xlsx") == false && filename.endsWith(".csv") == false) {
			redirectAttributes.addFlashAttribute("adminMessage", "xlsx 또는 csv 파일만 업로드할 수 있습니다.");
			return null;
		}
		try {
			return file.getBytes();
		} catch (IOException ex) {
			redirectAttributes.addFlashAttribute("adminMessage", "파일을 읽을 수 없습니다: " + ex.getMessage());
			return null;
		}
	}

	private List<Integer> buildPageNumbers(LegalDongMigrationPreviewResult preview) {
		int totalPages = preview.getTotalPages();
		if (totalPages <= 0) {
			return List.of();
		}

		int current = preview.getPage();
		int start = Math.max(current - 2, 0);
		int end = Math.min(start + 4, totalPages - 1);
		if ((end - start) < 4) {
			start = Math.max(end - 4, 0);
		}

		List<Integer> pages = new ArrayList<>();
		for (int i = start; i <= end; i++) {
			pages.add(i);
		}
		return pages;
	}

	private void appendCsvRow(StringBuilder csv, LegalDongDerivedRow row) {
		// CSV 필드 순서는 업서트 SQL 스크립트(복사 적재)와 동일하게 유지한다.
		appendCsvField(csv, row.getLegalDongCd()); csv.append(',');
		appendCsvField(csv, row.getLegalDongNm()); csv.append(',');
		appendCsvField(csv, row.getCtprvCd()); csv.append(',');
		appendCsvField(csv, row.getCtprvNm()); csv.append(',');
		appendCsvField(csv, row.getSgngCd()); csv.append(',');
		appendCsvField(csv, row.getSgngNm()); csv.append(',');
		appendCsvField(csv, row.getEmndnCd()); csv.append(',');
		appendCsvField(csv, row.getEmndnNm()); csv.append(',');
		appendCsvField(csv, row.getLiCd()); csv.append(',');
		appendCsvField(csv, row.getLiNm()); csv.append(',');
		appendCsvField(csv, row.getRank() == null ? null : row.getRank().toString()); csv.append(',');
		appendCsvField(csv, row.getCrDt() == null ? null : YYYYMMDD.format(row.getCrDt())); csv.append(',');
		appendCsvField(csv, row.getDltDt() == null ? null : YYYYMMDD.format(row.getDltDt()));
	}

	private void appendCsvField(StringBuilder csv, String value) {
		if (value == null) {
			return;
		}
		String normalized = value.trim();
		boolean needsQuote = normalized.contains(",") || normalized.contains("\"") || normalized.contains("\n") || normalized.contains("\r");
		if (needsQuote == false) {
			csv.append(normalized);
			return;
		}
		csv.append('"');
		csv.append(normalized.replace("\"", "\"\""));
		csv.append('"');
	}

	private String toCsvFileName(String originalFileName) {
		// 파일명에 경로 구분자가 섞여 들어오는 것을 방지하고, 확장자를 csv로 교체한다.
		String base = originalFileName.replace("\\", "_").replace("/", "_");
		if (base.toLowerCase().endsWith(".xlsx")) {
			base = base.substring(0, base.length() - 5);
		}
		if (base.toLowerCase().endsWith(".csv")) {
			base = base.substring(0, base.length() - 4);
		}
		return base + ".derived.csv";
	}

	private String toAsciiFileName(String fileName) {
		if (fileName == null || fileName.isBlank()) {
			return "legal_dong_preview.csv";
		}
		String normalized = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
		if (normalized.isBlank()) {
			return "legal_dong_preview.csv";
		}
		return normalized;
	}
}
