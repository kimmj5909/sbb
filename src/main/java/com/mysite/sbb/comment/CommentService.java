package com.mysite.sbb.comment;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.mysite.sbb.DataNotFoundException;
import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.question.Question;
import com.mysite.sbb.user.SiteUser;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
/**
 * 댓글 생성, 조회, 수정, 삭제, 추천을 담당하는 서비스 계층.
 * 트랜잭션 안에서 CommentRepository를 호출해 상태를 변경한다.
 */
public class CommentService {
	private final CommentRepository commentRepository;
	
	/**
	 * 답변과 작성자를 기반으로 댓글을 생성하고 저장한다.
	 */
	public Comment create(Answer answer, String content, SiteUser author) {
		Comment comment = new Comment();
		comment.setContent(content);
		comment.setCreateDate(LocalDateTime.now());
		comment.setAnswer(answer);
		comment.setAuthor(author);
		this.commentRepository.save(comment);
		return comment;
	}
	
	/**
	 * 댓글 단건 조회. 존재하지 않으면 예외를 발생시켜 상위에서 처리하도록 한다.
	 */
	public Comment getComment(Integer Id) {
		Optional<Comment> comment = this.commentRepository.findById(Id);
		if(comment.isPresent()) {
			return comment.get();
		} else {
			throw new DataNotFoundException("comment not found");
		}
	}
	
	/**
	 * 댓글 본문 수정과 수정 시각 기록.
	 */
	public void modify(Comment comment, String content) {

		comment.setContent(content);
		comment.setModifyDate(LocalDateTime.now());
		this.commentRepository.save(comment);
	}
	
	/**
	 * 댓글 삭제.
	 */
	public void delete(Comment comment) {
		this.commentRepository.delete(comment);
	}

	/**
	 * 댓글 삭제 권한 확인.
	 * - 작성자 본인 또는 관리자 계정이면 삭제를 허용한다.
	 */
	public boolean canDelete(Comment comment, SiteUser actor) {
		if (comment == null || actor == null) {
			return false;
		}
		SiteUser author = comment.getAuthor();
		boolean isAuthor = author != null && author.getId() != null && author.getId().equals(actor.getId());
		boolean isAdmin = actor.isAdmin();
		return isAuthor || isAdmin;
	}
	
	/**
	 * 추천 사용자 추가.
	 */
	public void vote(Comment comment, SiteUser siteUser) {
		comment.getVoter().add(siteUser);
		this.commentRepository.save(comment);
	}
}
