package com.mysite.sbb.question.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnswerReplyDto {
	private Integer id;
	private String content;
	private String author;
	private LocalDateTime createDate;
	private List<CommentReplyDto> comments = new ArrayList<>();
}
