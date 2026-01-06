package com.mysite.sbb.legaldong;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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

	private static final String SESSION_KEY_PREVIEW_BYTES = "legalDongMigrationPreviewBytes";
	private static final String SESSION_KEY_PREVIEW_FILENAME = "legalDongMigrationPreviewFileName";

	private final LegalDongMigrationService migrationService;

	@GetMapping("/migration")
	public String migrationPage() {
		return "admin/legal_dong_migration";
	}

	@GetMapping("/migration/preview")
	public String previewPage(@RequestParam(value = "page", defaultValue = "0") int page, Model model, HttpSession session,
			RedirectAttributes redirectAttributes) {
		byte[] bytes = (byte[]) session.getAttribute(SESSION_KEY_PREVIEW_BYTES);
		if (bytes == null || bytes.length == 0) {
			redirectAttributes.addFlashAttribute("adminMessage", "미리보기 데이터가 없습니다. 엑셀 파일을 다시 업로드하세요.");
			return "redirect:/admin/legal-dong/migration";
		}

		String fileName = (String) session.getAttribute(SESSION_KEY_PREVIEW_FILENAME);
		LegalDongMigrationPreviewResult preview = migrationService.preview(bytes, page, LegalDongMigrationService.DEFAULT_PREVIEW_PAGE_SIZE);
		model.addAttribute("preview", preview);
		model.addAttribute("fileName", fileName != null ? fileName : "(업로드 파일)");
		model.addAttribute("pageNumbers", buildPageNumbers(preview));
		return "admin/legal_dong_migration";
	}

	@PostMapping("/migration/preview")
	public String preview(@RequestParam("file") MultipartFile file, HttpSession session, RedirectAttributes redirectAttributes) {
		byte[] bytes = readFileBytes(file, redirectAttributes);
		if (bytes == null) {
			return "redirect:/admin/legal-dong/migration";
		}

		session.setAttribute(SESSION_KEY_PREVIEW_BYTES, bytes);
		session.setAttribute(SESSION_KEY_PREVIEW_FILENAME, file.getOriginalFilename());
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
		LegalDongMigrationApplyResult result = migrationService.apply(bytes, operatorId);

		model.addAttribute("result", result);
		model.addAttribute("fileName", fileName != null ? fileName : "(업로드 파일)");
		session.removeAttribute(SESSION_KEY_PREVIEW_BYTES);
		session.removeAttribute(SESSION_KEY_PREVIEW_FILENAME);
		return "admin/legal_dong_migration";
	}

	private byte[] readFileBytes(MultipartFile file, RedirectAttributes redirectAttributes) {
		if (file == null || file.isEmpty()) {
			redirectAttributes.addFlashAttribute("adminMessage", "업로드할 엑셀 파일(xlsx)을 선택하세요.");
			return null;
		}
		String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
		if (filename.endsWith(".xlsx") == false) {
			redirectAttributes.addFlashAttribute("adminMessage", "xlsx 파일만 업로드할 수 있습니다.");
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
}
