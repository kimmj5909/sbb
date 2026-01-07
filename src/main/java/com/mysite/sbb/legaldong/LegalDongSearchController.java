package com.mysite.sbb.legaldong;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 법정동 DB 검색/조회 화면.
 *
 * 담당 역할
 * - 화면 입력 파라미터를 검색 요청 DTO로 변환한다.
 * - DB에서 실시간으로 조회한 결과를 템플릿에 전달하고, 20개 단위 페이징 링크를 구성한다.
 */
@Controller
@RequestMapping("/admin/legal-dong")
@PreAuthorize("hasRole('ADMIN')")
public class LegalDongSearchController {

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final LegalDongDbSearchService dbSearchService;
	private final LegalDongAdminUpdateService adminUpdateService;

	public LegalDongSearchController(LegalDongDbSearchService dbSearchService, LegalDongAdminUpdateService adminUpdateService) {
		this.dbSearchService = dbSearchService;
		this.adminUpdateService = adminUpdateService;
	}

	@GetMapping("/search")
	public String search(
			@RequestParam(value = "field", required = false) String searchField,
			@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "dateField", required = false) String dateField,
			@RequestParam(value = "dateFrom", required = false) String dateFrom,
			@RequestParam(value = "dateTo", required = false) String dateTo,
			@RequestParam(value = "useYn", required = false) String useYn,
			@RequestParam(value = "size", required = false, defaultValue = "40") Integer size,
			// 아래 2개는 기존 체크박스 파라미터 호환용.
			@RequestParam(value = "useYnUsed", required = false) String useYnUsed,
			@RequestParam(value = "useYnUnused", required = false) String useYnUnused,
			// 하위 파라미터는 이전 버전 링크 호환용(선택박스 방식 도입 후에도 최소한의 동작을 보장).
			@RequestParam(value = "q", required = false) String legacyQuery,
			@RequestParam(value = "cd", required = false) String legacyLegalDongCd,
			@RequestParam(value = "nm", required = false) String legacyLegalDongNm,
			@RequestParam(value = "ctprvNm", required = false) String legacyCtprvNm,
			@RequestParam(value = "sgngNm", required = false) String legacySgngNm,
			@RequestParam(value = "emndnNm", required = false) String legacyEmndnNm,
			@RequestParam(value = "liNm", required = false) String legacyLiNm,
			@RequestParam(value = "crFrom", required = false) String legacyCrFrom,
			@RequestParam(value = "crTo", required = false) String legacyCrTo,
			@RequestParam(value = "dltFrom", required = false) String legacyDltFrom,
			@RequestParam(value = "dltTo", required = false) String legacyDltTo,
			@RequestParam(value = "pastCd", required = false) String legacyPastLegalDongCd,
			@RequestParam(value = "page", required = false, defaultValue = "0") Integer page,
			Model model) {

		LegalDongSearchRequest request = new LegalDongSearchRequest();
		// 선택박스 기반 검색을 우선 적용한다.
		request.setSearchField(searchField);
		request.setKeyword(keyword);
		request.setDateField(dateField);
		applyUseYnFilter(request, useYn, useYnUsed, useYnUnused);
		applyKeyword(request, searchField, keyword);
		applyDateRange(request, dateField, dateFrom, dateTo);

		// 호환 파라미터가 들어오고, 선택박스가 비어 있으면 기존 방식으로도 검색이 되도록 세팅한다.
		if ((searchField == null || searchField.isBlank()) && (keyword == null || keyword.isBlank())) {
			request.setQuery(legacyQuery);
			request.setLegalDongCd(legacyLegalDongCd);
			request.setLegalDongNm(legacyLegalDongNm);
			request.setCtprvNm(legacyCtprvNm);
			request.setSgngNm(legacySgngNm);
			request.setEmndnNm(legacyEmndnNm);
			request.setLiNm(legacyLiNm);
			request.setPastLegalDongCd(legacyPastLegalDongCd);

			// 날짜 범위도 구버전 파라미터를 읽어 적용한다.
			request.setCrDtFrom(parseYyyyMmDd(legacyCrFrom));
			request.setCrDtTo(parseYyyyMmDd(legacyCrTo));
			request.setDltDtFrom(parseYyyyMmDd(legacyDltFrom));
			request.setDltDtTo(parseYyyyMmDd(legacyDltTo));
		}
		request.setPage(page != null ? page : 0);
		request.setSize(size != null ? size : 40);

		LegalDongSearchResult result;
		try {
			result = dbSearchService.search(request);
		} catch (RuntimeException ex) {
			model.addAttribute("errorMessage", "법정동 DB 조회 중 오류가 발생했습니다: " + ex.getMessage());
			result = LegalDongSearchResult.empty(request.getPage(), request.getSize());
		}

		model.addAttribute("filters", request);
		model.addAttribute("result", result);
		model.addAttribute("pageNumbers", buildPageNumbers(result));
		return "admin/legal_dong_search";
	}

	/**
	 * 검색 목록에서 과거코드/말소일자를 인라인으로 수정한다.
	 *
	 * 주의: 클래스 레벨 @RequestMapping("/admin/legal-dong") 기준으로 상대 경로만 사용한다.
	 */
	@PostMapping("/update")
	public String updateFromList(
			@RequestParam("legalDongCd") String legalDongCd,
			@RequestParam(value = "pastLegalDongCd", required = false) String pastLegalDongCd,
			@RequestParam(value = "dltDt", required = false) String dltDt,
			@RequestParam(value = "field", required = false) String field,
			@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "dateField", required = false) String dateField,
			@RequestParam(value = "dateFrom", required = false) String dateFrom,
			@RequestParam(value = "dateTo", required = false) String dateTo,
			@RequestParam(value = "useYn", required = false) String useYn,
			@RequestParam(value = "size", required = false, defaultValue = "40") Integer size,
			@RequestParam(value = "page", required = false, defaultValue = "0") Integer page,
			Authentication authentication,
			RedirectAttributes redirectAttributes) {
		String operatorId = authentication != null ? authentication.getName() : "SYSTEM";
		try {
			adminUpdateService.updateAdminFields(legalDongCd, pastLegalDongCd, dltDt, operatorId);
			redirectAttributes.addFlashAttribute("adminMessage", "수정 완료: " + legalDongCd);
		} catch (RuntimeException ex) {
			redirectAttributes.addFlashAttribute("errorMessage", "수정 실패: " + ex.getMessage());
		}

		// 수정 후에도 현재 검색 조건/페이지를 유지한다.
		redirectAttributes.addAttribute("field", field);
		redirectAttributes.addAttribute("keyword", keyword);
		redirectAttributes.addAttribute("dateField", dateField);
		redirectAttributes.addAttribute("dateFrom", dateFrom);
		redirectAttributes.addAttribute("dateTo", dateTo);
		redirectAttributes.addAttribute("useYn", useYn);
		redirectAttributes.addAttribute("size", size);
		redirectAttributes.addAttribute("page", page);
		return "redirect:/admin/legal-dong/search";
	}

	private void applyUseYnFilter(LegalDongSearchRequest request, String useYn, String useYnUsed, String useYnUnused) {
		// 신규(선택박스) 파라미터가 있으면 최우선 적용한다.
		if (useYn != null && useYn.isBlank() == false) {
			request.setUseYnFilter(useYn.trim());
			return;
		}

		// 하위 호환(체크박스)
		// - 둘 다 체크: all
		// - 사용만: used
		// - 미사용만: unused
		if (useYnUsed == null && useYnUnused == null) {
			request.setUseYnFilter("all");
			return;
		}
		if (useYnUsed != null && useYnUnused != null) {
			request.setUseYnFilter("all");
			return;
		}
		request.setUseYnFilter(useYnUsed != null ? "used" : "unused");
	}

	private void applyKeyword(LegalDongSearchRequest request, String searchField, String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return;
		}
		String normalizedField = searchField == null ? "all" : searchField.trim();
		String normalizedKeyword = keyword.trim();

		switch (normalizedField) {
			case "legalDongCd" -> request.setLegalDongCd(normalizedKeyword);
			case "legalDongNm" -> request.setLegalDongNm(normalizedKeyword);
			case "ctprvNm" -> request.setCtprvNm(normalizedKeyword);
			case "sgngNm" -> request.setSgngNm(normalizedKeyword);
			case "emndnNm" -> request.setEmndnNm(normalizedKeyword);
			case "liNm" -> request.setLiNm(normalizedKeyword);
			case "pastLegalDongCd" -> request.setPastLegalDongCd(normalizedKeyword);
			case "all" -> request.setQuery(normalizedKeyword);
			default -> request.setQuery(normalizedKeyword);
		}

		// DB 조회 서비스는 `searchField/keyword`만 사용해 동적 SQL을 구성한다.
		// - legacy 필드(legalDongCd 등)는 이전 파라미터 호환을 위해 컨트롤러에서 세팅만 유지한다.
		// - 필요 시 향후 "필드별 입력" UI로 회귀하더라도 DTO 구조를 재사용할 수 있다.
	}

	private void applyDateRange(LegalDongSearchRequest request, String dateField, String dateFrom, String dateTo) {
		String normalized = dateField == null ? "none" : dateField.trim();
		LocalDate from = parseYyyyMmDd(dateFrom);
		LocalDate to = parseYyyyMmDd(dateTo);

		if ("crDt".equals(normalized)) {
			request.setCrDtFrom(from);
			request.setCrDtTo(to);
			return;
		}
		if ("dltDt".equals(normalized)) {
			request.setDltDtFrom(from);
			request.setDltDtTo(to);
			return;
		}
		// none: 미사용
	}

	private LocalDate parseYyyyMmDd(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String normalized = raw.trim().replaceAll("[^0-9]", "");
		if (normalized.length() != 8) {
			return null;
		}
		try {
			return LocalDate.parse(normalized, YYYYMMDD);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private List<Integer> buildPageNumbers(LegalDongSearchResult result) {
		int totalPages = result.totalPages();
		if (totalPages <= 0) {
			return List.of();
		}

		int current = result.getPage();
		int start = Math.max(current - 2, 0);
		int end = Math.min(start + 4, totalPages - 1);
		if ((end - start) < 4) {
			start = Math.max(end - 4, 0);
		}

		java.util.ArrayList<Integer> pages = new java.util.ArrayList<>();
		for (int i = start; i <= end; i++) {
			pages.add(i);
		}
		return pages;
	}
}
