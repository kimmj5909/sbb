package com.mysite.sbb.answer;

import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserService;
import com.mysite.sbb.question.QuestionService;
import com.mysite.sbb.comment.Comment;
import com.mysite.sbb.comment.CommentForm;
import com.mysite.sbb.comment.CommentService;
import com.mysite.sbb.question.Question;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable; //question.id 매핑
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;

import java.security.Principal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RequestMapping("/answer")
@RequiredArgsConstructor
@Controller
/**
 * 답변 생성, 수정, 삭제, 추천 및 댓글 작성 흐름을 처리하는 컨트롤러.
 * 질문 상세 화면과 연결되어 답변 입력/관리 요청을 수신한다.
 */
public class AnswerController {

		private final QuestionService questionService;
		private final AnswerService answerService;
		private final UserService userService;
		private final CommentService commentService;
/**
		AnswerController(AnswerRepository answerRepository) {
        this.answerRepository = answerRepository ;
    }
**/		
		/**
		 * 답변 등록 처리
		 * - 질문 식별자와 폼 데이터를 받아 검증한 뒤 AnswerService를 통해 저장한다.
		 * - 검증 실패 시 질문 상세 템플릿을 다시 렌더링한다.
		 */
		@PreAuthorize("isAuthenticated()")
		@PostMapping("/create/{id}")
		public String createAnswer(Model model, @PathVariable("id") Integer id, @Valid AnswerForm answerForm,
				BindingResult bindingResult, Principal principal) {
			Question question = this.questionService.getQuestion(id);
			SiteUser siteUser = this.userService.getUser(principal.getName());
			if (bindingResult.hasErrors()) {
				model.addAttribute("question", question);
				return "question_detail";
			}
			Answer answer = this.answerService.create(question, answerForm.getContent(), siteUser);
			return String.format("redirect:/question/detail/%s#answer_%s", answer.getQuestion().getId(), answer.getId());
		}

		/**
		 * 답변 수정 폼 표시
		 * - 본문을 AnswerForm에 주입해 템플릿에서 기존 내용을 확인할 수 있도록 한다.
		 */
		@PreAuthorize("isAuthenticated()")
		@GetMapping("/modify/{id}")
		public String answerModify(AnswerForm answerForm, @PathVariable("id") Integer id, Principal principal) {
			Answer answer = this.answerService.getAnswer(id);
			if (!answer.getAuthor().getUsername().equals(principal.getName())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정권한이 없습니다.");
			}
			answerForm.setContent(answer.getContent());
			return "answer_form";
		}

		/**
		 * 답변 수정 저장
		 * - 검증 통과 후 AnswerService.modify를 호출하고 상세 화면으로 리다이렉트한다.
		 */
		@PreAuthorize("isAuthenticated()")
		@PostMapping("/modify/{id}")
		public String answerModify(@Valid AnswerForm answerForm, BindingResult bindingResult,
				@PathVariable("id") Integer id, Principal principal) {
			if (bindingResult.hasErrors()) {
				return "answer_form";
			}
			Answer answer = this.answerService.getAnswer(id);
			if (!answer.getAuthor().getUsername().equals(principal.getName())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정권한이 없습니다.");
			}
			this.answerService.modify(answer, answerForm.getContent());
			return String.format("redirect:/question/detail/%s#answer_%s", answer.getQuestion().getId(), answer.getId());
		}

		/**
		 * 답변 삭제
		 * - 작성자 본인인지 확인 후 삭제 처리하고 질문 상세 페이지로 이동한다.
		 */
		@PreAuthorize("isAuthenticated()")
		@GetMapping("/delete/{id}")
		public String answerDelete(Principal principal, @PathVariable("id") Integer id) {
			Answer answer = this.answerService.getAnswer(id);
			if (!answer.getAuthor().getUsername().equals(principal.getName())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "삭제권한이 없습니다.");
			}
			this.answerService.delete(answer);
			return String.format("redirect:/question/detail/%s", answer.getQuestion().getId());
		}

		/**
		 * 답변 추천(투표)
		 * - 로그인 사용자를 투표자로 추가하고 앵커 위치로 리다이렉트한다.
		 */
		@PreAuthorize("isAuthenticated()")
		@GetMapping("/vote/{id}")
		public String answerVote(Principal principal, @PathVariable("id") Integer id) {
			Answer answer = this.answerService.getAnswer(id);
			SiteUser siteUser = this.userService.getUser(principal.getName());
			this.answerService.vote(answer, siteUser);
			return String.format("redirect:/question/detail/%s#answer_%s", answer.getQuestion().getId(), answer.getId());
		}
		
		/**
		 * 답변에 대한 댓글 등록
		 * - 댓글 폼 검증 후 CommentService를 이용해 저장하고 질문 상세 화면으로 이동한다.
		 */
		@PreAuthorize("isAuthenticated()")
		@PostMapping("/comment/{id}")
		public String createComment(Model model, @PathVariable("id") Integer id, @Valid CommentForm commentForm,
				BindingResult bindingResult, Principal principal) {
			Answer answer = this.answerService.getAnswer(id);
			SiteUser siteUser = this.userService.getUser(principal.getName());
			if (bindingResult.hasErrors()) {
				model.addAttribute("answer", answer);
				return "question_detail";
			}
			//Answer answer = this.answerService.create(answer, answerForm.getContent(), siteUser);
			Comment comment = this.commentService.create(answer, commentForm.getContent(), siteUser);
			return String.format("redirect:/question/detail/%s#answer_%s", answer.getQuestion().getId(), answer.getId());
		}
		

		
}		
