package com.mysite.sbb.legaldong;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
/**
 * 법정동 엑셀 업로드 마이그레이션 서비스.
 *
 * 처리 흐름
 * 1) 엑셀 원본 행을 파싱한다.
 * 2) 10자리 코드에서 단위별 코드/명칭을 파생하고, 꼬리값 00/000 규칙으로 하위 없음(null)을 정리한다.
 * 3) rank를 상위/하위 그룹 각각 별도 카운터로 계산해 단일 컬럼에 저장한다.
 * 4) PostgreSQL에 batch upsert 한다.
 *
 * 미리보기 페이징
 * - 미리보기는 전체 파생 데이터를 만들어 카운트한 뒤, 화면에는 page/size 기준으로 필요한 구간만 내려준다.
 */
public class LegalDongMigrationService {

	public static final int DEFAULT_PREVIEW_PAGE_SIZE = 50;

	private final LegalDongExcelParser excelParser;
	private final LegalDongJdbcUpsertRepository jdbcUpsertRepository;
	private final LegalDongJdbcSnapshotRepository snapshotRepository;
	private final LegalDongPastMappingService pastMappingService;

	public LegalDongMigrationPreviewResult preview(byte[] xlsxBytes, int page, int size) {
		LegalDongExcelParser.ParseResult parsed = parse(xlsxBytes);
		DerivedResult derived = deriveRows(parsed.getRows(), parsed.getErrors());

		int resolvedSize = size > 0 ? size : DEFAULT_PREVIEW_PAGE_SIZE;
		int resolvedPage = Math.max(page, 0);
		int totalPages = (int) Math.ceil((double) derived.rows.size() / (double) resolvedSize);
		int safeTotalPages = Math.max(totalPages, 1);
		int boundedPage = Math.min(resolvedPage, safeTotalPages - 1);
		int fromIndex = boundedPage * resolvedSize;
		int toIndex = Math.min(fromIndex + resolvedSize, derived.rows.size());
		List<LegalDongDerivedRow> pageEntries = fromIndex >= toIndex ? List.of() : derived.rows.subList(fromIndex, toIndex);

		return new LegalDongMigrationPreviewResult(
				parsed.getRows().size(),
				derived.rows.size(),
				derived.upperRows,
				derived.lowerRows,
				boundedPage,
				resolvedSize,
				totalPages,
				derived.errors,
				pageEntries);
	}

	@Transactional
	public LegalDongMigrationApplyResult apply(byte[] xlsxBytes, String operatorId) {
		LegalDongExcelParser.ParseResult parsed = parse(xlsxBytes);
		DerivedResult derived = deriveRows(parsed.getRows(), parsed.getErrors());

		UpsertChangeSummary upsertChangeSummary = summarizeUpsertChanges(derived.rows);

		LocalDateTime now = LocalDateTime.now();
		List<String> errors = new ArrayList<>(derived.errors);
		jdbcUpsertRepository.upsertAll(derived.rows, operatorId, now);

		// 과거법정동코드(past_legal_dong_cd) 자동 반영
		// - 시행일(=new.cr_dt) 기준으로 old.dlt_dt = 시행일인 "말소 코드"만 과거 코드 후보로 사용한다.
		// - 애매 케이스(후보>1) 또는 누락(0)은 자동 반영하지 않고, 별도 조회/수정 대상으로 남긴다.
		int pastMappedEmndn = 0;
		int pastMappedLi = 0;
		List<String> pastMappingDiagnostics = new ArrayList<>();
		if (pastMappingService != null) {
			java.util.Set<String> effDts = derived.rows.stream()
					.map(r -> r.getCrDt() == null ? null : java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(r.getCrDt()))
					.filter(v -> v != null && v.isBlank() == false)
					.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			List<LegalDongPastMappingApplyResult> mappingResults = pastMappingService.applyForEffectiveDates(effDts, operatorId);
			for (LegalDongPastMappingApplyResult r : mappingResults) {
				pastMappedEmndn += r.getEmndnUpdatedCnt();
				pastMappedLi += r.getLiUpdatedCnt();

				// 운영에서 가장 많이 헷갈리는 지점은 "왜 신규 코드 past가 비어있나" 이므로,
				// 시행일별로 애매/누락 카운트를 함께 남겨 원인 추적이 가능하게 한다.
				// - old.dlt_dt = eff_dt 조건을 만족하는 후보가 없거나(누락),
				// - 후보가 2개 이상이라 유니크 매핑이 불가한 경우(애매),
				// - 리 단위는 (old_emndn_cd||tail2) 형태의 말소 코드가 누락된 경우(말소 리 누락)로 분류한다.
				if (r.getEmndnAmbiguousCnt() > 0 || r.getEmndnMissingCnt() > 0 || r.getLiMissingOldCnt() > 0 || r.getOldEmndnCnt() == 0) {
					pastMappingDiagnostics.add("[시행일 " + r.getEffDt() + "] 말소 읍면동 " + r.getOldEmndnCnt()
							+ "건, 신규 읍면동 " + r.getNewEmndnCnt()
							+ "건 중 반영 " + r.getEmndnUpdatedCnt()
							+ "건 (애매 " + r.getEmndnAmbiguousCnt()
							+ ", 누락 " + r.getEmndnMissingCnt()
							+ "), 말소 리 " + r.getOldLiCnt()
							+ "건, 신규 리 " + r.getNewLiCnt()
							+ "건 중 반영 " + r.getLiUpdatedCnt()
							+ "건 (말소 리 누락 " + r.getLiMissingOldCnt() + ")");
				}
			}
		}

		List<String> updateDetails = new ArrayList<>();
		updateDetails.add("업서트 대상: " + upsertChangeSummary.upsertTargetRows + "건");
		if (upsertChangeSummary.breakdownAvailable) {
			updateDetails.add("업데이트 완료: " + upsertChangeSummary.changedRows + "건"
					+ " (신규코드 추가 " + upsertChangeSummary.insertedRows + "건"
					+ ", 기존코드 업데이트 " + upsertChangeSummary.updatedRows + "건"
					+ ", 말소일자 업데이트 " + upsertChangeSummary.dltDtUpdatedRows + "건)");
		} else {
			updateDetails.add("업데이트 완료: " + upsertChangeSummary.changedRows + "건 (상세 분류 불가)");
		}
		if (upsertChangeSummary.noChangeRows > 0) {
			updateDetails.add("변경 없음(업서트 제외): " + upsertChangeSummary.noChangeRows + "건");
		}
		if (upsertChangeSummary.breakdownWarning != null) {
			updateDetails.add(upsertChangeSummary.breakdownWarning);
		}
		if (pastMappedEmndn > 0 || pastMappedLi > 0) {
			updateDetails.add("과거법정동코드 반영: 읍면동 " + pastMappedEmndn + "건, 리 " + pastMappedLi + "건");
		}
		if (pastMappingDiagnostics.isEmpty() == false) {
			updateDetails.addAll(pastMappingDiagnostics);
		}

		return new LegalDongMigrationApplyResult(
				parsed.getRows().size(),
				upsertChangeSummary.upsertTargetRows,
				upsertChangeSummary.changedRows,
				upsertChangeSummary.insertedRows,
				upsertChangeSummary.updatedRows,
				upsertChangeSummary.dltDtUpdatedRows,
				updateDetails,
				errors);
	}

	private UpsertChangeSummary summarizeUpsertChanges(List<LegalDongDerivedRow> upsertRows) {
		if (upsertRows == null || upsertRows.isEmpty()) {
			return new UpsertChangeSummary(0, 0, 0, 0, 0, 0, true, null);
		}

		List<String> codes = upsertRows.stream()
				.map(LegalDongDerivedRow::getLegalDongCd)
				.filter(v -> v != null && v.isBlank() == false)
				.map(String::trim)
				.toList();

		Map<String, LegalDongJdbcSnapshotRepository.SnapshotRow> existing;
		try {
			existing = snapshotRepository.findByLegalDongCds(codes);
		} catch (Exception ex) {
			// 스냅샷 조회 실패가 마이그레이션 자체를 막지 않도록, "업서트 대상=변경"으로만 요약하고 상세 분류는 생략한다.
			return new UpsertChangeSummary(upsertRows.size(), upsertRows.size(), 0, 0, 0, 0, false,
					"[주의] 기존 데이터 스냅샷 조회 실패로 상세 분류를 생략했습니다: " + ex.getMessage());
		}

		int inserted = 0;
		int updated = 0;
		int dltUpdated = 0;
		int noChange = 0;

		for (LegalDongDerivedRow row : upsertRows) {
			String code = row.getLegalDongCd();
			if (code == null || code.isBlank()) {
				continue;
			}
			LegalDongJdbcSnapshotRepository.SnapshotRow before = existing.get(code);
			if (before == null) {
				inserted++;
				if (toYyyyMmDd(row.getDltDt()) != null) {
					dltUpdated++;
				}
				continue;
			}

			String incomingCrDt = toYyyyMmDd(row.getCrDt());
			String incomingDltDt = toYyyyMmDd(row.getDltDt());

			boolean dltWillChange = compareMaxChange(before.dltDt(), incomingDltDt);
			boolean crWillChange = compareMinChange(before.crDt(), incomingCrDt);
			boolean metaWillChange = notEquals(before.legalDongNm(), row.getLegalDongNm())
					|| notEquals(before.ctprvCd(), row.getCtprvCd())
					|| notEquals(before.ctprvNm(), row.getCtprvNm())
					|| notEquals(before.sgngCd(), row.getSgngCd())
					|| notEquals(before.sgngNm(), row.getSgngNm())
					|| notEquals(before.emndnCd(), row.getEmndnCd())
					|| notEquals(before.emndnNm(), row.getEmndnNm())
					|| notEquals(before.liCd(), row.getLiCd())
					|| notEquals(before.liNm(), row.getLiNm())
					|| (before.rank() == null ? row.getRank() != null : before.rank().equals(row.getRank()) == false);

			// 업서트 규칙상 use_yn은 dlt_dt 결과로 결정되므로, dlt 변화가 있으면 포함한다.
			boolean willChange = dltWillChange || crWillChange || metaWillChange;
			if (willChange == false) {
				noChange++;
				continue;
			}

			updated++;
			if (dltWillChange) {
				dltUpdated++;
			}
		}

		int upsertTarget = upsertRows.size();
		int changed = inserted + updated;
		return new UpsertChangeSummary(upsertTarget, changed, inserted, updated, dltUpdated, noChange, true, null);
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
		return value == null ? null : java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(value);
	}

	private record UpsertChangeSummary(
			int upsertTargetRows,
			int changedRows,
			int insertedRows,
			int updatedRows,
			int dltDtUpdatedRows,
			int noChangeRows,
			boolean breakdownAvailable,
			String breakdownWarning) {
	}

	private LegalDongExcelParser.ParseResult parse(byte[] xlsxBytes) {
		try {
			return excelParser.parse(new java.io.ByteArrayInputStream(xlsxBytes));
		} catch (Exception ex) {
			return new LegalDongExcelParser.ParseResult(List.of(), List.of("엑셀 파싱 실패: " + ex.getMessage()));
		}
	}

	private DerivedResult deriveRows(List<LegalDongSourceRow> sourceRows, List<String> parseErrors) {
		List<String> errors = new ArrayList<>(parseErrors == null ? List.of() : parseErrors);
		List<LegalDongDerivedRow> derivedRows = new ArrayList<>();

		// rank 규칙(요구사항 변경 반영)
		// - 같은 sgng_cd 내에서 emndn(읍면동) 단위 "상위" 행은 1,2,3...으로 증가한다.
		// - li(리) 단위 "하위" 행은 해당 emndn 그룹 내에서 1,2,3...으로 증가하며, emndn이 바뀌면 1로 리셋된다.
		// - sgng(시군구) 단위 행(emndn_cd가 null)은 해당 sgng의 첫 그룹과 동일하게 1로 둔다.
		String currentSgngCd = null;
		String currentEmndnCd = null;
		int groupRank = 0;
		int liRank = 0;

		for (LegalDongSourceRow row : sourceRows) {
			String code = row.getLegalDongCd();
			if (code == null || code.length() != 10) {
				errors.add("[" + row.getRowNumber() + "행] 법정동코드는 10자리여야 합니다: " + code);
				continue;
			}

			String ctprvCd = code.substring(0, 2);
			String sgngTail = code.substring(2, 5);
			String emndnTail = code.substring(5, 8);
			String liTail = code.substring(8, 10);

			// 단위별 하위 없음 처리 규칙
			boolean hasSgng = !sgngTail.equals("000");
			boolean hasEmndn = hasSgng && !emndnTail.equals("000");
			boolean hasLi = hasEmndn && !liTail.equals("00");

			String ctprvNm = requiredName(row.getCtprvNm(), row.getRowNumber(), "시도명", errors);
			if (ctprvNm == null) {
				continue;
			}

			String sgngCd = hasSgng ? code.substring(0, 5) : null;
			String sgngNm = hasSgng ? row.getSgngNm() : null;
			String emndnCd = hasEmndn ? code.substring(0, 8) : null;

			// 명칭 매핑 주의(입력 파일 컬럼 의미)
			// - '읍면동명' 컬럼: 읍면동(부모 단위) 명칭(리 단위 행에서 주로 의미가 있음)
			// - '동리명' 컬럼: 실제 법정동/리 명칭(읍면동 단위 행에서는 이 값이 곧 읍면동명인 케이스가 존재)
			//
			// 관측된 데이터 이슈(예: 1111011900)
			// - emndn_cd(11110119)는 존재하나 emndn_nm이 sgng_nm과 동일하게 저장되는 케이스가 있었고,
			//   정상 값은 '동리명' 컬럼에 들어있는 경우가 확인됐다.
			//
			// 따라서,
			// - 리 단위(hasLi=true): emndn_nm은 '읍면동명', li_nm은 '동리명'
			// - 읍면동 단위(hasLi=false, hasEmndn=true): emndn_nm은 '동리명' 우선(없으면 '읍면동명' fallback)
			String emndnNm = null;
			String liCd = null;
			String liNm = null;
			if (hasEmndn) {
				if (hasLi) {
					emndnNm = row.getEmndnNm();
					liCd = code;
					liNm = row.getLiNm();
				} else {
					emndnNm = firstNonBlank(row.getLiNm(), row.getEmndnNm());
				}
				if (emndnNm == null || emndnNm.isBlank()) {
					errors.add("[" + row.getRowNumber() + "행] 읍면동 코드가 존재하지만 읍면동명이 비어 있습니다: " + code);
					continue;
				}
			}

			// sgng 변경 시 rank 카운터를 리셋한다(동일 sgng 단위에서만 그룹 카운팅).
			if ((currentSgngCd == null && sgngCd != null) || (currentSgngCd != null && currentSgngCd.equals(sgngCd) == false)) {
				currentSgngCd = sgngCd;
				currentEmndnCd = null;
				groupRank = 0;
				liRank = 0;
			}

			// rank는 단일 컬럼 유지(단, 의미가 레벨에 따라 다름).
			// - emndn(상위) 행: groupRank
			// - li(하위) 행: liRank (emndn 변경 시 1로 리셋)
			// - sgng(상위 상단) 행: 1
			Integer rank;
			if (emndnCd == null) {
				rank = groupRank > 0 ? groupRank : 1;
			} else if (liCd == null) {
				groupRank++;
				currentEmndnCd = emndnCd;
				liRank = 0;
				rank = groupRank;
			} else {
				if (currentEmndnCd == null || currentEmndnCd.equals(emndnCd) == false) {
					// 파일에 emndn 상위 행이 누락되더라도, emndn 변경을 감지해 그룹을 시작한다.
					groupRank++;
					currentEmndnCd = emndnCd;
					liRank = 0;
				}
				liRank++;
				rank = liRank;
			}

			String legalDongNm = buildLegalDongName(ctprvNm, sgngNm, emndnNm, liNm);
			if (legalDongNm == null) {
				errors.add("[" + row.getRowNumber() + "행] 법정동명 생성 실패(시도명 누락 등): " + code);
				continue;
			}

			derivedRows.add(LegalDongDerivedRow.builder()
					.rowNumber(row.getRowNumber())
					.legalDongCd(code)
					.legalDongNm(legalDongNm)
					.ctprvCd(ctprvCd)
					.ctprvNm(ctprvNm)
					.sgngCd(sgngCd)
					.sgngNm(sgngNm)
					.emndnCd(emndnCd)
					.emndnNm(emndnNm)
					.liCd(liCd)
					.liNm(liNm)
					.rank(rank)
					.crDt(row.getCrDt())
					.dltDt(row.getDltDt())
					.build());
		}

		List<LegalDongDerivedRow> consolidated = consolidateByLegalDongCd(derivedRows, errors);

		int upperRows = (int) consolidated.stream().filter(r -> r.getLiCd() == null).count();
		int lowerRows = consolidated.size() - upperRows;
		return new DerivedResult(consolidated, upperRows, lowerRows, errors);
	}

	/**
	 * 엑셀 원본에는 동일한 법정동코드(legal_dong_cd)가 중복 등장할 수 있다.
	 *
	 * 요구사항(사용자 확인)
	 * - 법정동코드는 고유 코드이므로 `tb_legal_dong_l`에는 1행=1코드로 유지한다.
	 * - 따라서 업로드 데이터 내부에서 중복이 발견되면, DB 적재 전에 병합(consolidation)한다.
	 *
	 * 병합 규칙
	 * - cr_dt: 가장 이른(최소) 생성일자 유지
	 * - dlt_dt: 가장 늦은(최대) 말소일자 유지
	 * - 나머지 컬럼: 최초 행 값을 기준으로 유지(서로 다른 값이 발견되면 경고로 기록)
	 *
	 * 주의
	 * - 이 병합은 "정정"을 의미하지 않는다. 동일 코드가 여러 행으로 존재하는 입력 포맷 특성에 대한 방어 로직이다.
	 */
	private List<LegalDongDerivedRow> consolidateByLegalDongCd(List<LegalDongDerivedRow> rows, List<String> errors) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}

		Map<String, LegalDongDerivedRow> merged = new LinkedHashMap<>();
		java.util.Set<String> duplicatedCodes = new java.util.HashSet<>();
		int duplicateRows = 0;

		for (LegalDongDerivedRow row : rows) {
			String code = row.getLegalDongCd();
			if (code == null || code.isBlank()) {
				continue;
			}

			LegalDongDerivedRow existing = merged.get(code);
			if (existing == null) {
				merged.put(code, row);
				continue;
			}

			duplicateRows++;
			duplicatedCodes.add(code);

			java.time.LocalDate minCrDt = minDate(existing.getCrDt(), row.getCrDt());
			java.time.LocalDate maxDltDt = maxDate(existing.getDltDt(), row.getDltDt());

			if (notEquals(existing.getLegalDongNm(), row.getLegalDongNm())
					|| notEquals(existing.getCtprvNm(), row.getCtprvNm())
					|| notEquals(existing.getSgngNm(), row.getSgngNm())
					|| notEquals(existing.getEmndnNm(), row.getEmndnNm())
					|| notEquals(existing.getLiNm(), row.getLiNm())
					|| notEquals(existing.getCtprvCd(), row.getCtprvCd())
					|| notEquals(existing.getSgngCd(), row.getSgngCd())
					|| notEquals(existing.getEmndnCd(), row.getEmndnCd())
					|| notEquals(existing.getLiCd(), row.getLiCd())) {
				errors.add("[중복코드 병합] 동일 legal_dong_cd=" + code + "에서 다른 메타데이터가 발견되어 첫 행 기준으로 유지합니다. "
						+ "cr_dt=" + formatDate(minCrDt) + ", dlt_dt=" + formatDate(maxDltDt));
			}

			merged.put(code, LegalDongDerivedRow.builder()
					.rowNumber(Math.min(existing.getRowNumber(), row.getRowNumber()))
					.legalDongCd(existing.getLegalDongCd())
					.legalDongNm(existing.getLegalDongNm())
					.ctprvCd(existing.getCtprvCd())
					.ctprvNm(existing.getCtprvNm())
					.sgngCd(existing.getSgngCd())
					.sgngNm(existing.getSgngNm())
					.emndnCd(existing.getEmndnCd())
					.emndnNm(existing.getEmndnNm())
					.liCd(existing.getLiCd())
					.liNm(existing.getLiNm())
					.rank(existing.getRank())
					.crDt(minCrDt)
					.dltDt(maxDltDt)
					.build());
		}

		if (duplicateRows > 0) {
			errors.add("[중복코드 병합] 업로드 데이터에서 중복 행 " + duplicateRows + "건을 병합했습니다. "
					+ "(병합 대상 코드 수=" + duplicatedCodes.size() + ")");
			// 중복 코드 목록은 경고 영역에서 확인할 수 있도록 별도 라인으로 남긴다.
			// - 전체가 너무 길어질 수 있어 상한을 둔다.
			int limit = 200;
			List<String> sorted = duplicatedCodes.stream().sorted().toList();
			int shown = Math.min(sorted.size(), limit);
			errors.add("[중복코드 목록] (표시 " + shown + " / 총 " + sorted.size() + ")");
			for (int i = 0; i < shown; i++) {
				errors.add("중복코드: " + sorted.get(i));
			}
			if (sorted.size() > shown) {
				errors.add("중복코드: ... 외 " + (sorted.size() - shown) + "건");
			}
		}

		return List.copyOf(merged.values());
	}

	private boolean notEquals(String a, String b) {
		if (a == null && b == null) {
			return false;
		}
		if (a == null || b == null) {
			return true;
		}
		return a.equals(b) == false;
	}

	private java.time.LocalDate minDate(java.time.LocalDate a, java.time.LocalDate b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return a.isBefore(b) ? a : b;
	}

	private java.time.LocalDate maxDate(java.time.LocalDate a, java.time.LocalDate b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return a.isAfter(b) ? a : b;
	}

	private String formatDate(java.time.LocalDate value) {
		return value == null ? "null" : java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(value);
	}

	private String firstNonBlank(String first, String second) {
		if (first != null && first.isBlank() == false) {
			return first.trim();
		}
		if (second != null && second.isBlank() == false) {
			return second.trim();
		}
		return null;
	}

	private String requiredName(String value, int rowNumber, String label, List<String> errors) {
		if (value == null || value.isBlank()) {
			errors.add("[" + rowNumber + "행] " + label + "이(가) 비어 있습니다.");
			return null;
		}
		return value.trim();
	}

	private String buildLegalDongName(String... parts) {
		List<String> tokens = new ArrayList<>();
		for (String part : parts) {
			if (part == null) {
				continue;
			}
			String trimmed = part.trim();
			if (trimmed.isBlank()) {
				continue;
			}
			tokens.add(trimmed);
		}
		if (tokens.isEmpty()) {
			return null;
		}
		return String.join(" ", tokens);
	}

	private record DerivedResult(
			List<LegalDongDerivedRow> rows,
			int upperRows,
			int lowerRows,
			List<String> errors) {
	}
}
