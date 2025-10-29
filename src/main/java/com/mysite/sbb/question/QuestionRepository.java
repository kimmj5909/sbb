package com.mysite.sbb.question;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page; //페이징을 위한 클래스
import org.springframework.data.domain.Pageable;  //페이징을 처리하는 인터페이스
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Sort;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 질문 엔티티용 리포지토리.
 * - 제목, 내용, 작성자 등 다양한 조건으로 검색할 수 있는 메서드를 정의한다.
 */
public interface QuestionRepository extends	JpaRepository<Question, Integer> {

	Question findBySubject(String subject); // subject 조회를 위한 쿼리
	Question findBySubjectAndContent(String subject, String content);
	List<Question> findBySubjectLike(String subject); // ~~가 포함된 데이터 조회를 위한 쿼리
	Page<Question> findAll(Pageable pageable);
	Page<Question> findAll(Specification<Question> spec, Pageable pageable);
	List<Question> findAll(Specification<Question> spec, Sort sort);
	List<Question> findAllByOrderByIdAsc();
	List<Question> findBySubjectContainingIgnoreCase(String kw);
	@Query("select "
			+ "distinct q "
			+ "from Question q "
			+ "left outer join SiteUser u1 on q.author=u1 "
			+ "left outer join Answer a on a.question=q "
			+ "left outer join SiteUser u2 on a.author=u2 "
			+ "where " 
			+ "    q.subject like %:kw$ "
			+ "    or q.content like %:kw% "
			+ "    or u1.username like %:kw% "
			+ "    or a.content like %:kw% "
			+ "    or u2.username like %:kw% ")
	Page<Question> findAllByKeyword(@Param("kw") String kw, Pageable pageable);
	
	@Query("SELECT DISTINCT q FROM Question q LEFT JOIN FETCH q.answerList WHERE q.id = :id")
	Optional<Question> findByIdWithAnswersAndComments(@Param("id") Integer id);
	
}
