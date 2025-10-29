package com.mysite.sbb.comment;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;



import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.answer.AnswerService;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserService;
import com.mysite.sbb.comment.Comment;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RequestMapping("/comment")
@RequiredArgsConstructor
@Controller
/**
 * 댓글 작성·수정·삭제·추천 기능을 노출하는 컨트롤러.
 * 답변과 연동된 댓글을 별도 폼으로 관리하며, 권한 체크를 수행한다.
 */
public class CommentController {
	
	private final CommentService commentService;
	private final AnswerService answerService;
	private final UserService userService;
	
	/**
	 * 댓글 작성 폼 진입
	 * - 대상 답변을 모델에 담아 템플릿에서 문맥 정보를 표시한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/create/{id}")
	public String createCommentForm(Model model, @PathVariable("id") Integer id) {
		Answer answer = this.answerService.getAnswer(id);
		model.addAttribute("answer",answer);
		model.addAttribute("commentForm", new CommentForm());
		return "comment_form";
	}
	
	/**
	 * 댓글 등록 처리
	 * - 입력 검증 후 CommentService를 통해 저장하고 질문 상세 페이지로 이동한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/create/{id}")
	public String createComment(Model model, @PathVariable("id") Integer id, @Valid CommentForm commentForm, BindingResult bindingResult, Principal principal){
		Answer answer = this.answerService.getAnswer(id);
	
		if(bindingResult.hasErrors()) {
			model.addAttribute("answer", answer);
			return "comment_form";
		}
		
		SiteUser siteUser = this.userService.getUser(principal.getName());
		Comment comment = this.commentService.create(answer, commentForm.getContent(), siteUser);
		return String.format("redirect:/question/detail/%s#comment_%s", answer.getQuestion().getId(), comment.getId());
	}
	
	/**
	 * 댓글 수정 폼 진입
	 * - 작성자 권한을 확인하고 기존 내용을 폼에 바인딩한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/modify/{id}")
	public String modifyComment(CommentForm commentForm, @PathVariable("id") Integer id, Principal principal, Model model) {
		Comment comment = this.commentService.getComment(id);
		 if(!comment.getAuthor().getUsername().equals(principal.getName())) {
			 throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정 권한이 없습니다.");
		 }
		 commentForm.setContent(comment.getContent());
		 model.addAttribute("comment", comment); // 객체추가
		 model.addAttribute("answer", comment.getAnswer());
		 return "comment_form";
	}
	
	/**
	 * 댓글 수정 저장
	 * - 검증 오류 시 다시 폼을 표시하고, 성공 시 질문 상세로 리다이렉트한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/modify/{id}")
	public String modifyComment(@Valid CommentForm commentForm, BindingResult bindingResult, 
								@PathVariable("id") Integer id, Principal principal, Model model) {
		if(bindingResult.hasErrors()) {
			Comment comment = this.commentService.getComment(id); //추가
			model.addAttribute("answer", comment.getAnswer());
			return "comment_form";
		}
		Comment comment = this.commentService.getComment(id);
		if(!comment.getAuthor().getUsername().equals(principal.getName())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정 권한이 없습니다.");
		}
		this.commentService.modify(comment, commentForm.getContent());
		return String.format("redirect:/question/detail/%s#comment_%s", comment.getAnswer().getQuestion().getId(), comment.getId());		
	}
	
	/**
	 * 댓글 삭제 처리
	 * - 작성자 또는 관리자만 삭제할 수 있으며 삭제 후 질문 상세로 이동한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/delete/{id}")
	public String deleteComment(Principal principal, @PathVariable("id") Integer id) {
		Comment comment = this.commentService.getComment(id);
		SiteUser requester = this.userService.getUser(principal.getName());
		// 작성자 본인 또는 관리자 여부를 확인한 뒤 삭제를 진행한다.
		if(!this.commentService.canDelete(comment, requester)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "삭제 권한이 없습니다.");
		}
		this.commentService.delete(comment);
		return String.format("redirect:/question/detail/%s", comment.getAnswer().getQuestion().getId());
		
	}
	
	/**
	 * 댓글 추천 처리
	 * - 로그인 사용자를 추천 목록에 추가하고 원래 답변 위치로 이동한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/vote/{id}")
	public String voteComment(@PathVariable("id") Integer id, Principal principal) {
		Comment comment = this.commentService.getComment(id);
		SiteUser siteUser = this.userService.getUser(principal.getName());
		this.commentService.vote(comment, siteUser);
		return String.format("redirect:/question/detail/%s#answer_%s",
							comment.getAnswer().getQuestion().getId(), comment.getAnswer().getId());
	}

}
