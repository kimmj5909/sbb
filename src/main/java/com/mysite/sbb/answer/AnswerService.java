package com.mysite.sbb.answer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import com.mysite.sbb.question.QuestionRepository;
import com.mysite.sbb.question.Question;
import com.mysite.sbb.user.SiteUser;

import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;

import com.mysite.sbb.DataNotFoundException;
import com.mysite.sbb.comment.Comment;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
/**
 * 답변 도메인의 생성/조회/수정/삭제 및 추천 기능을 담당하는 서비스 계층.
 * 컨트롤러와 리포지토리 사이에서 트랜잭션 흐름과 도메인 규칙을 관리한다.
 */
public class AnswerService {
	private final AnswerRepository answerRepository;
	private final QuestionRepository questionRepository;
	
	/**
	 * 질문과 작성자를 받아 새 답변을 생성한다.
	 * 생성 일시를 현재 시각으로 기록하고 저장된 엔티티를 반환한다.
	 */
	public Answer create(Question question, String content, SiteUser author) {
		Answer answer = new Answer();
		answer.setContent(content);
		answer.setCreateDate(LocalDateTime.now());
		answer.setQuestion(question);
		answer.setAuthor(author);
		this.answerRepository.save(answer);
		return answer;
	}
	
	/**
	 * 식별자로 답변을 조회한다.
	 * 존재하지 않으면 DataNotFoundException을 던져 상위 레이어에서 처리하도록 한다.
	 */
	public Answer getAnswer(Integer id) {
		Optional<Answer> answer = this.answerRepository.findById(id);
		if (answer.isPresent()) {
			return answer.get();
		}else {
			throw new DataNotFoundException("answer not found");
		}
	}
	
	/**
	 * 답변 본문을 수정하고 수정 일시를 기록한다.
	 */
	public void modify(Answer answer, String content) {
		answer.setContent(content);
		answer.setModifyDate(LocalDateTime.now());
		this.answerRepository.save(answer);
	}
	
	/**
	 * 답변을 삭제한다. 연결된 댓글은 JPA cascade 설정에 따라 함께 제거된다.
	 */
	public void delete(Answer answer) {
		this.answerRepository.delete(answer);
	}
	
	/**
	 * 로그인 사용자를 답변 추천자 목록에 추가한다.
	 */
	public void vote(Answer answer, SiteUser siteUser) {
		answer.getVoter().add(siteUser);
		this.answerRepository.save(answer);
	}


}
