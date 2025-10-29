package com.mysite.sbb.user;

import lombok.Getter;

@Getter
/**
 * 사용자 권한을 정의하는 열거형.
 * - 관리자/일반 사용자 여부와 Spring Security 권한 문자열을 함께 보관한다.
 */
public enum UserRole {
	ADMIN(true, "ROLE_ADMIN"),
	USER(false, "ROLE_USER");
	
	private boolean value;
	private String authority;
	
	UserRole(boolean value, String authority){
		this.value = value;
		this.authority = authority;
	}

	public boolean getValue() {
		return value;
	}
	public String getAuthority() {
		return authority;
	}
}
