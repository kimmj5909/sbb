package com.mysite.sbb.question.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReplyResponse {
	private String questionTitle;
	private List<AnswerReplyDto> answers = new ArrayList<>();
}
