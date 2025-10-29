package com.mysite.sbb.answer;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 답변 엔티티에 대한 CRUD와 페이징 기능을 제공하는 Spring Data JPA 리포지토리.
 * 기본 메서드 외에 추가 쿼리가 필요할 경우 여기에서 정의한다.
 */
public interface AnswerRepository extends JpaRepository<Answer, Integer> {

}
