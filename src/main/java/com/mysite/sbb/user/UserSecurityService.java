package com.mysite.sbb.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
/**
 * Spring Security에서 사용자 정보를 로딩하기 위한 UserDetailsService 구현체.
 * 데이터베이스에서 사용자 정보를 조회해 인증에 사용한다.
 */
public class UserSecurityService implements UserDetailsService {
	
	private final UserRepository userRepository;
	
	/**
	 * 사용자명을 기준으로 SiteUser를 조회하고 Spring Security UserDetails로 변환한다.
	 * 존재하지 않으면 UsernameNotFoundException을 발생시킨다.
	 */
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		Optional<SiteUser> _siteUser = this.userRepository.findByUsername(username);
		if (_siteUser.isEmpty()) {
			throw new UsernameNotFoundException("사용자를 찾을 수 없습니다.");
		}
		SiteUser siteUser = _siteUser.get();
		siteUser.ensureRoleInitialized();
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority(UserRole.USER.getAuthority()));
		if (siteUser.isAdmin()) {
			authorities.add(new SimpleGrantedAuthority(UserRole.ADMIN.getAuthority()));
		}
		return new User(siteUser.getUsername(), siteUser.getPassword(), authorities);
	}
}
