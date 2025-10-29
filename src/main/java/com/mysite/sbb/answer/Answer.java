package com.mysite.sbb.answer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.mysite.sbb.question.Question;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.comment.Comment;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
/**
 * 질문에 대한 답변을 표현하는 엔티티.
 * - 질문(`Question`)과 작성자(`SiteUser`)에 대한 다대일 관계를 유지한다.
 * - 추천 사용자(`voter`)와 연결된 다대다, 답변에 달린 댓글 목록을 함께 보관한다.
 */
public class Answer {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	@Column(columnDefinition = "TEXT")
	private String content;
	
	private LocalDateTime createDate;
	
 	@ManyToOne
	private Question question;
 	
	@ManyToOne
	private SiteUser author;
	
	private LocalDateTime modifyDate;
	
	@ManyToMany
	Set<SiteUser> voter;
	
	@OneToMany(mappedBy = "answer", cascade = CascadeType.REMOVE)
	private List<Comment> commentList;

}
