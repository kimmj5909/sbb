package com.mysite.sbb.question;

import com.mysite.sbb.user.SiteUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional; //2-10 추가
import java.time.LocalDateTime;

import com.mysite.sbb.DataNotFoundException; //id값에 해당하는 질뭇 데이터 없을 경우 Exception 실행
import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.comment.Comment;
import com.mysite.sbb.file.FileRepository;
import com.mysite.sbb.question.dto.AnswerReplyDto;
import com.mysite.sbb.question.dto.CommentReplyDto;
import com.mysite.sbb.question.dto.ReplyResponse;
import com.mysite.sbb.question.dto.QuestionExcelRow;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page; //페이징을 위한 클래스
import org.springframework.data.domain.PageRequest; //현재 페이지와 한 페이지에 보여 줄 게시물 개수 등을 설정하여 페이징 요청을 하는 클래스
import org.springframework.data.domain.Pageable; //페이징을 처리하는 인터페이스
import org.springframework.data.domain.Sort;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;

@RequiredArgsConstructor
@Service

public class QuestionService {

	private final QuestionRepository questionRepository;
	private final FileRepository fileRepository;
	
	@Value("${file.upload-dir}")
	private String uploadDir;
	 
	/**
	 * 게시판 검색 조건 구성
	 * - 제목, 본문, 질문 작성자, 답변 본문, 답변 작성자까지 모두 LIKE 조건으로 묶어
	 *   단일 키워드로 광범위한 검색이 가능하도록 한다.
	 */
	private Specification<Question> search(String kw) {
		return new Specification<>() {
			private static final long serialVersionUID = 1L;
			@Override
			public Predicate toPredicate(Root<Question> q, CriteriaQuery<?> query, CriteriaBuilder cb) {
				query.distinct(true);
				Join<Question, SiteUser> u1 = q.join("author", JoinType.LEFT);
				Join<Question, Answer> a = q.join("answerList", JoinType.LEFT);
				Join<Answer, SiteUser> u2 = a.join("author", JoinType.LEFT);
				return cb.or(cb.like(q.get("subject"), "%" + kw + "%"),
						cb.like(q.get("content"),  "%" + kw + "%"),
						cb.like(u1.get("username"),  "%" + kw + "%"),
						cb.like(a.get("content"),  "%" + kw + "%"),
						cb.like(u2.get("username"),  "%" + kw + "%"));
			}
		}; 
	}
	
	/**
	 * 게시판 목록 페이징 조회
	 * - 페이지는 0 이상 값으로 보정하고, 페이지 크기는 허용된 값(컨트롤러에서 검증) 또는 기본 10개로 제한한다.
	 * - 생성일 역순 정렬을 적용해 최신 작성 글이 먼저 보이도록 한다.
	 */
	public Page<Question> getList(int page, int size, String kw) {
		int safePage = Math.max(page, 0);
		int safeSize = size <= 0 ? 10 : size;
		List<Sort.Order> sorts =  new ArrayList<>();
		sorts.add(Sort.Order.desc("CreateDate"));
		Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(sorts));
		Specification<Question> spec = search(kw);
		return this.questionRepository.findAll(spec, pageable);
	}
	public List<Question> getList() {
		
		return this.questionRepository.findAll();
	}

	/**
	 * 단일 게시글 조회
	 * - 존재하지 않을 경우 사용자 정의 예외를 발생시켜 컨트롤러에서 404 처리에 활용한다.
	 */
	public Question getQuestion(Integer id) {
		Optional<Question> question = this.questionRepository.findById(id);
		if (question.isPresent()) {
			return question.get();
		}else {
			throw new DataNotFoundException("question not found");
		}
	}

	/**
	 * 답글 팝업용으로 질문과 연관된 답변 목록을 함께 불러온다.
	 */
	public Question getQuestionWithReplies(Integer id) {
		return this.questionRepository.findByIdWithAnswersAndComments(id)
				.orElseThrow(() -> new DataNotFoundException("question not found"));
	}

	/**
	 * 게시글 생성
	 * - 제목, 내용, 작성자, 생성 시각을 세팅한 뒤 저장한다.
	 */
	public Question create(String subject, String content, SiteUser user) {
		Question q = new Question();
		q.setSubject(subject);
		q.setContent(content);
		q.setCreateDate(LocalDateTime.now());
		q.setAuthor(user);
		return this.questionRepository.save(q);
	}
	
	/**
	 * 게시글 수정
	 * - 제목/내용을 덮어쓰고 수정 시각을 남긴다.
	 */
	public void modify(Question question, String subject, String content) {
		question.setSubject(subject);
		question.setContent(content);
		question.setModifyDate(LocalDateTime.now());
		this.questionRepository.save(question);
	}

	/**
	 * 게시글 삭제
	 * - 연관된 답변/댓글 cascading 전략에 따라 JPA가 정리하도록 리포지토리 삭제만 호출한다.
	 */
	public void delete(Question question) {
		this.questionRepository.delete(question);
	}
	/**
	 * 게시글 추천(투표) 처리
	 * - 질문 엔티티의 voter 집합에 사용자 추가 후 저장한다.
	 */
	public void vote(Question question, SiteUser siteUser) {
		question.getVoter().add(siteUser);
		this.questionRepository.save(question);
	}
	
	/**
	 * 엑셀 다운로드용 목록 조회
	 * - 키워드가 주어지면 제목에 대한 부분 일치 검색,
	 *   아니면 전체 목록을 반환해 Excel 추출 서비스에서 사용한다.
	 */
	public List<Question> getListForExcel(String kw) {
		if(kw != null && !kw.trim().isEmpty()) {
			return this.questionRepository.findBySubjectContainingIgnoreCase(kw.trim());
		}
		return this.questionRepository.findAll();
	}

	/**
	 * 엑셀 다운로드 전용 DTO 목록을 작성한다.
	 * - 검색 키워드가 주어지면 동일한 Specification을 사용해 필터링한다.
	 * - 최신 작성 순으로 정렬하고, 답변/댓글 수를 미리 계산해 전송한다.
	 */
	public List<QuestionExcelRow> getExcelRows(String kw) {
		String keyword = kw != null ? kw.trim() : "";
		Specification<Question> spec = null;
		if (!keyword.isEmpty()) {
			spec = search(keyword);
		}
		Sort sort = Sort.by(Sort.Order.desc("createDate"));
		List<Question> questions = spec != null
				? this.questionRepository.findAll(spec, sort)
				: this.questionRepository.findAll(sort);

		List<QuestionExcelRow> rows = new ArrayList<>();
		long sequence = 1;
		for (Question question : questions) {
			int answerCount = 0;
			int commentCount = 0;
			if (question.getAnswerList() != null) {
				answerCount = question.getAnswerList().size();
				for (Answer answer : question.getAnswerList()) {
					if (answer.getCommentList() != null) {
						commentCount += answer.getCommentList().size();
					}
				}
			}
			rows.add(new QuestionExcelRow(
					sequence++,
					question.getSubject(),
					question.getAuthor() != null ? question.getAuthor().getUsername() : "익명",
					question.getCreateDate(),
					answerCount,
					commentCount));
		}
		return rows;
	}

	/**
	 * 질문 엔티티를 팝업용 DTO로 변환한다.
	 * - answerList, commentList를 순회하며 작성자/작성일 등 표시 정보만 추려 JSON으로 전달한다.
	 */
	public ReplyResponse buildReplyResponse(Question question) {
		ReplyResponse response = new ReplyResponse();
		response.setQuestionTitle(question.getSubject());

		if (question.getAnswerList() != null) {
			for (Answer answer : question.getAnswerList()) {
				AnswerReplyDto answerDto = new AnswerReplyDto();
				answerDto.setId(answer.getId());
				answerDto.setContent(answer.getContent());
				answerDto.setAuthor(answer.getAuthor() != null ? answer.getAuthor().getUsername() : "익명");
				answerDto.setCreateDate(answer.getCreateDate());

				if (answer.getCommentList() != null) {
					for (Comment comment : answer.getCommentList()) {
						CommentReplyDto commentDto = new CommentReplyDto();
						commentDto.setId(comment.getId());
						commentDto.setContent(comment.getContent());
						commentDto.setAuthor(comment.getAuthor() != null ? comment.getAuthor().getUsername() : "익명");
						commentDto.setCreateDate(comment.getCreateDate());
						answerDto.getComments().add(commentDto);
					}
				}
				response.getAnswers().add(answerDto);
			}
		}
		return response;
	}

	/**
	 * 질문 아이디만 받은 경우 fetch-join으로 로드해 DTO로 치환한다.
	 */
	public ReplyResponse buildReplyResponse(Integer questionId) {
		Question question = getQuestionWithReplies(questionId);
		return buildReplyResponse(question);
	}
}
