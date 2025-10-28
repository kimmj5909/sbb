package com.mysite.sbb.comment;

import java.time.LocalDateTime;
import java.util.Set;

import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.user.SiteUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.ManyToMany;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
/**
 * 답변에 달리는 댓글 엔티티.
 * - 댓글 본문과 작성/수정 시각을 저장하며 작성자, 대상 답변, 추천 사용자 집합을 참조한다.
 */
public class Comment {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer Id;
	
	@Column(columnDefinition = "TEXT")
	private String content;
	
	private LocalDateTime createDate;
	private LocalDateTime modifyDate;
	
	@ManyToOne
	private Answer answer;
	
	@ManyToOne
	private SiteUser author;
	
	@ManyToMany
	Set<SiteUser> voter;
	
}
