package com.mysite.sbb.question.dto;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CommentReplyDto {
	private Integer id;
	private String content;
	private String author;
	private LocalDateTime createDate;
}
