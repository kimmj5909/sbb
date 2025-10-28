package com.mysite.sbb.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * 사용자 엔티티 리포지토리.
 * username 기반 조회를 추가로 제공한다.
 */
public interface UserRepository extends JpaRepository<SiteUser, Long>{
	Optional<SiteUser> findByUsername(String username);
}
