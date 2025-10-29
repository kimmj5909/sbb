package com.mysite.sbb.answer;

import jakarta.validation.constraints.NotEmpty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
/**
 * 답변 작성/수정 시 입력값을 전달하고 검증하는 폼 객체.
 * - 본문 내용은 비어있을 수 없으며 유효성 메시지를 한글로 제공한다.
 */
public class AnswerForm {
	@NotEmpty(message = "내용은 필수 항목입니다.")
	private String content;
}
