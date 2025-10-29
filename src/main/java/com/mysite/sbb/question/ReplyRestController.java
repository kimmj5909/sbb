package com.mysite.sbb.question;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.answer.AnswerForm;
import com.mysite.sbb.answer.AnswerService;
import com.mysite.sbb.comment.Comment;
import com.mysite.sbb.comment.CommentForm;
import com.mysite.sbb.comment.CommentService;
import com.mysite.sbb.question.dto.ReplyResponse;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 답글 팝업에서 사용하는 REST 엔드포인트 모음.
 * UI가 Ajax로 호출해 답변/댓글 CRUD 후 최신 목록을 다시 내려준다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/question/replies")
public class ReplyRestController {

	private final QuestionService questionService;
	private final AnswerService answerService;
	private final CommentService commentService;
	private final UserService userService;

	/**
	 * 새 답변을 생성한 뒤 갱신된 질문/답변/댓글 스냅샷을 돌려준다.
	 */
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/{questionId}/answers")
	public ResponseEntity<ReplyResponse> createAnswer(@PathVariable("questionId") Integer questionId,
			@Valid @RequestBody AnswerForm answerForm, BindingResult bindingResult, Principal principal) {
		if (bindingResult.hasErrors()) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
		var question = this.questionService.getQuestion(questionId);
		SiteUser siteUser = this.userService.getUser(principal.getName());
		this.answerService.create(question, answerForm.getContent(), siteUser);
		ReplyResponse response = this.questionService.buildReplyResponse(questionId);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	/**
	 * 답변 본문을 수정하고 최신 답글 묶음을 반환한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@PutMapping("/answers/{answerId}")
	public ResponseEntity<ReplyResponse> modifyAnswer(@PathVariable("answerId") Integer answerId,
			@Valid @RequestBody AnswerForm answerForm, BindingResult bindingResult, Principal principal) {
		if (bindingResult.hasErrors()) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
		Answer answer = this.answerService.getAnswer(answerId);
		if (!answer.getAuthor().getUsername().equals(principal.getName())) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		this.answerService.modify(answer, answerForm.getContent());
		ReplyResponse response = this.questionService.buildReplyResponse(answer.getQuestion().getId());
		return ResponseEntity.ok(response);
	}

	/**
	 * 답변을 삭제한 후 남은 목록을 재구성한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@DeleteMapping("/answers/{answerId}")
	public ResponseEntity<ReplyResponse> deleteAnswer(@PathVariable("answerId") Integer answerId, Principal principal) {
		Answer answer = this.answerService.getAnswer(answerId);
		if (!answer.getAuthor().getUsername().equals(principal.getName())) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		Integer questionId = answer.getQuestion().getId();
		this.answerService.delete(answer);
		ReplyResponse response = this.questionService.buildReplyResponse(questionId);
		return ResponseEntity.ok(response);
	}

	/**
	 * 특정 답변에 댓글을 추가하고 변경된 트리를 돌려준다.
	 */
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/answers/{answerId}/comments")
	public ResponseEntity<ReplyResponse> createComment(@PathVariable("answerId") Integer answerId,
			@Valid @RequestBody CommentForm commentForm, BindingResult bindingResult, Principal principal) {
		if (bindingResult.hasErrors()) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
		Answer answer = this.answerService.getAnswer(answerId);
		SiteUser siteUser = this.userService.getUser(principal.getName());
		this.commentService.create(answer, commentForm.getContent(), siteUser);
		ReplyResponse response = this.questionService.buildReplyResponse(answer.getQuestion().getId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	/**
	 * 댓글 본문을 수정하고 갱신된 데이터 집합을 반환한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@PutMapping("/comments/{commentId}")
	public ResponseEntity<ReplyResponse> modifyComment(@PathVariable("commentId") Integer commentId,
			@Valid @RequestBody CommentForm commentForm, BindingResult bindingResult, Principal principal) {
		if (bindingResult.hasErrors()) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
		Comment comment = this.commentService.getComment(commentId);
		if (!comment.getAuthor().getUsername().equals(principal.getName())) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		this.commentService.modify(comment, commentForm.getContent());
		ReplyResponse response = this.questionService.buildReplyResponse(comment.getAnswer().getQuestion().getId());
		return ResponseEntity.ok(response);
	}

	/**
	 * 댓글 삭제 후 남은 답글 정보를 전달한다.
	 */
	@PreAuthorize("isAuthenticated()")
	@DeleteMapping("/comments/{commentId}")
	public ResponseEntity<ReplyResponse> deleteComment(@PathVariable("commentId") Integer commentId,
			Principal principal) {
		Comment comment = this.commentService.getComment(commentId);
		SiteUser siteUser = this.userService.getUser(principal.getName());
		// 팝업에서도 관리자 계정은 작성자와 무관하게 댓글을 삭제할 수 있도록 권한을 확인한다.
		if (!this.commentService.canDelete(comment, siteUser)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		Integer questionId = comment.getAnswer().getQuestion().getId();
		this.commentService.delete(comment);
		ReplyResponse response = this.questionService.buildReplyResponse(questionId);
		return ResponseEntity.ok(response);
	}
}
