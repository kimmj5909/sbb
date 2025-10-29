package com.mysite.sbb.comment;

import jakarta.validation.constraints.NotEmpty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
/**
 * 댓글 작성/수정 입력값을 전달하는 DTO.
 * - 본문이 비어 있지 않은지 검증한다.
 */
public class CommentForm {
	@NotEmpty(message = "내용은 필수항목입니다.")
	private String content;

}
