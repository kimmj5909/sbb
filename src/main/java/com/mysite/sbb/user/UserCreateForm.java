package com.mysite.sbb.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
/**
 * 회원 가입 폼 데이터를 검증하고 전달하는 DTO.
 * 사용자명, 비밀번호, 이메일, 연락처 입력값을 검증한다.
 */
public class UserCreateForm {
	@Size(min = 3, max = 25)
	@NotEmpty(message = "사용자 ID는 필수 항목입니다.")
	private String username;
	
	@NotEmpty(message = "비밀번호는 필수 항목입니다.")
	private String password1;
	
	@NotEmpty(message = "비밀번호 확인은 필수 항목입니다.")
	private String password2;
	
	@NotEmpty(message = "이메일은 필수 항목입니다.")
	@Email
	private String email;
	
	// 연락처는 숫자만으로 10~11자리 입력을 강제한다.
	@NotEmpty(message = "연락처는 필수 항목입니다.")
	@Size(min = 10, max = 11)
	@Pattern(regexp = "\\d{10,11}", message = "연락처는 숫자만 10~11자리로 입력하세요.")
	private String phone;
}
