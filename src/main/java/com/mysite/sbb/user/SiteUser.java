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

    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean role = Boolean.FALSE; //권한

    /**
     * 관리자 여부를 명확히 확인한다.
     */
    public boolean isAdmin() {
        return Boolean.TRUE.equals(this.role);
    }

    /**
     * 일반 사용자 여부를 반환한다.
     */
    public boolean isUser() {
        return !isAdmin();
    }

    /**
     * 권한 플래그가 null인 경우 안전하게 false로 초기화한다.
     */
    public void ensureRoleInitialized() {
        if (this.role == null) {
            this.role = Boolean.FALSE;
        }
    }

}
