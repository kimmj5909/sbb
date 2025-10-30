package com.mysite.sbb.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.mysite.sbb.question.Question;
import com.mysite.sbb.question.QuestionService;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRole;
import com.mysite.sbb.user.UserService;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
/**
 * 관리자 전용 대시보드 컨트롤러.
 * - 게시판 공지, 게시물 일괄 삭제, 사용자 권한 관리를 제공한다.
 */
public class AdminController {

	private final QuestionService questionService;
	private final UserService userService;

	/**
	 * 게시물/사용자 관리 현황을 출력한다.
	 */
	@GetMapping
	public String dashboard(Model model,
			@RequestParam(value = "page", defaultValue = "0") int page,
			@RequestParam(value = "size", defaultValue = "50") int size) {
		// 페이지당 게시물 수는 정해진 옵션으로 제한해 예기치 않은 요청값으로 인한 과도한 조회를 방지한다.
		List<Integer> sizeOptions = List.of(50, 100, 200);
		int selectedSize = sizeOptions.contains(size) ? size : 50;

		int safePage = Math.max(page, 0);
		Page<Question> questionPage = this.questionService.getQuestionsForAdmin(safePage, selectedSize);
		List<Question> questions = questionPage.getContent();

		// 전체 페이지 수와 현재 페이지를 기반으로 커스텀 페이지네이션을 계산한다.
		int totalPages = questionPage.getTotalPages();
		int currentPageIndex = safePage; // 0 기반 페이지 인덱스 유지

		// 이전/다음 버튼은 사용 가능 여부에 따라 링크 타겟을 결정한다.
		boolean hasPrevious = questionPage.hasPrevious();
		boolean hasNext = questionPage.hasNext();
		int previousPageIndex = hasPrevious ? currentPageIndex - 1 : currentPageIndex;
		int nextPageIndex = hasNext ? currentPageIndex + 1 : currentPageIndex;

		// 10페이지 단위 점프는 요구사항에 맞춰 앞/뒤로 이동 가능한 페이지 인덱스를 계산한다.
		int jumpBackwardIndex = Math.max(currentPageIndex - 10, 0);
		int jumpForwardIndex = totalPages > 0 ? Math.min(currentPageIndex + 10, totalPages - 1) : 0;
		// 점프 대상 인덱스는 텍스트로 노출할 때 1 기반 페이지 번호로 보여주기 위해 별도 값을 준비한다.
		int jumpBackwardLabel = jumpBackwardIndex + 1;
		int jumpForwardLabel = jumpForwardIndex + 1;

		// 현재 페이지를 중앙에 두도록 최대 5개의 페이지 번호를 산출한다.
		int startIndex = Math.max(currentPageIndex - 2, 0);
		int endIndex = Math.min(startIndex + 4, totalPages - 1);
		// 총 페이지 수가 5 미만일 때는 뒤쪽 범위가 줄어들 수 있으므로 다시 보정한다.
		if ((endIndex - startIndex) < 4) {
			startIndex = Math.max(endIndex - 4, 0);
		}

		// 템플릿에서 순회할 수 있도록 중앙 영역 페이지 인덱스를 리스트로 구성한다.
		List<Integer> centerPages = new ArrayList<>();
		for (int i = startIndex; i <= endIndex && totalPages > 0; i++) {
			centerPages.add(i);
		}

		// 시작/끝 범위를 기준으로 점프 링크(… 버튼) 노출 여부를 판단한다.
		boolean showJumpBackward = startIndex > 0;
		boolean showJumpForward = endIndex < totalPages - 1;

		List<SiteUser> users = this.userService.getAllUsers();
		model.addAttribute("questions", questions);
		model.addAttribute("questionPage", questionPage);
		model.addAttribute("users", users);
		model.addAttribute("userRoles", UserRole.values());
		model.addAttribute("sizeOptions", sizeOptions);
		model.addAttribute("selectedSize", selectedSize);
		model.addAttribute("currentPage", safePage);
		model.addAttribute("hasPrevious", hasPrevious);
		model.addAttribute("hasNext", hasNext);
		model.addAttribute("previousPageIndex", previousPageIndex);
		model.addAttribute("nextPageIndex", nextPageIndex);
		model.addAttribute("centerPages", centerPages);
		model.addAttribute("showJumpBackward", showJumpBackward);
		model.addAttribute("showJumpForward", showJumpForward);
		model.addAttribute("jumpBackwardIndex", jumpBackwardIndex);
		model.addAttribute("jumpForwardIndex", jumpForwardIndex);
		model.addAttribute("jumpBackwardLabel", jumpBackwardLabel);
		model.addAttribute("jumpForwardLabel", jumpForwardLabel);
		return "admin/dashboard";
	}

	/**
	 * 선택한 게시물의 공지 상태를 갱신한다.
	 */
	@PostMapping("/posts/notice")
	public String updateNotice(@RequestParam(name = "questionId", required = false) Collection<Integer> questionIds,
			@RequestParam("action") String action, RedirectAttributes redirectAttributes) {
		if (questionIds == null || questionIds.isEmpty()) {
			redirectAttributes.addFlashAttribute("adminMessage", "선택된 게시물이 없습니다.");
			return "redirect:/admin";
		}
		boolean notice = "enable".equalsIgnoreCase(action);
		this.questionService.updateNoticeStatus(questionIds, notice);
		redirectAttributes.addFlashAttribute("adminMessage", notice ? "선택한 게시물을 공지로 설정했습니다." : "선택한 게시물을 일반 글로 전환했습니다.");
		return "redirect:/admin";
	}

	/**
	 * 선택 게시물을 일괄 삭제한다.
	 */
	@PostMapping("/posts/delete")
	public String deletePosts(@RequestParam(name = "questionId", required = false) Collection<Integer> questionIds,
			RedirectAttributes redirectAttributes) {
		if (questionIds == null || questionIds.isEmpty()) {
			redirectAttributes.addFlashAttribute("adminMessage", "삭제할 게시물을 선택하세요.");
			return "redirect:/admin";
		}
		this.questionService.deleteAllByIds(questionIds);
		redirectAttributes.addFlashAttribute("adminMessage", "선택한 게시물을 삭제했습니다.");
		return "redirect:/admin";
	}

	/**
	 * 사용자 권한을 변경한다.
	 */
	@PostMapping("/users/{userId}/role")
	public String updateUserRole(@PathVariable("userId") Long userId, @RequestParam("role") UserRole role,
			RedirectAttributes redirectAttributes) {
		this.userService.updateUserRole(userId, role);
		redirectAttributes.addFlashAttribute("adminMessage", "사용자 권한을 변경했습니다.");
		return "redirect:/admin";
	}

	/**
	 * 사용자 연락처와 권한을 동시에 갱신한다.
	 * - 이메일/연락처는 공백을 제거한 뒤 저장하며, 권한은 선택 값이 주어지면 적용한다.
	 */
	@PostMapping("/users/{userId}/update")
	public String updateUserProfile(@PathVariable("userId") Long userId,
			@RequestParam("email") String email,
			@RequestParam("phone") String phone,
			@RequestParam("role") UserRole role,
			RedirectAttributes redirectAttributes) {
		try {
			this.userService.updateUserProfile(userId, email, phone, role);
			redirectAttributes.addFlashAttribute("adminMessage", "사용자 정보를 저장했습니다.");
		} catch (DataIntegrityViolationException ex) {
			redirectAttributes.addFlashAttribute("adminMessage", "이메일 또는 연락처가 중복되었습니다. 값을 확인해 주세요.");
		}
		return "redirect:/admin";
	}
}
