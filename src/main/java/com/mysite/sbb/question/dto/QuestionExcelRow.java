package com.mysite.sbb.question.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QuestionExcelRow {
	private final long sequence;
	private final String subject;
	private final String author;
	private final LocalDateTime createdAt;
	private final int answerCount;
	private final int commentCount;
}
