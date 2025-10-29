package com.mysite.sbb.comment;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 댓글 엔티티를 영속화하는 Spring Data JPA 리포지토리.
 * 기본 CRUD, 페이징, 정렬 기능을 제공한다.
 */
public interface CommentRepository extends JpaRepository<Comment, Integer>{

}
