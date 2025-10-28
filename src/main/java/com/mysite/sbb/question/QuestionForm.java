package com.mysite.sbb.question;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.web.multipart.MultipartFile;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
/**
 * 질문 작성/수정 폼 데이터를 운반하는 DTO.
 * - 제목/내용 유효성 검증과 첨부파일 배열을 포함한다.
 */
public class QuestionForm {
	@NotEmpty(message="제목은 필수 항목입니다.")
	@Size(max=200)
	private String subject;
	
	@NotEmpty(message="내용은 필수 항목입니다.")
	private String content;
	
	private MultipartFile[] files;
 }
