package com.mysite.sbb.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
/**
 * 게시판 사용자를 나타내는 엔티티.
 * - 아이디, 비밀번호, 이메일, 전화번호, 권한 정보를 저장한다.
 */
public class SiteUser {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@Column(unique = true)
	private String username;
	
	private String password;
	
	@Column(unique = true)
	private String email;
	
	@Column(unique = true) //개선사항
	private String phone;
	
	private Boolean role; //권한 
	
}
