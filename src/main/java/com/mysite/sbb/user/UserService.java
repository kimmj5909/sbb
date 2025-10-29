package com.mysite.sbb.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mysite.sbb.DataNotFoundException;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
@Transactional
/**
 * 사용자 생성 및 조회 로직을 담당하는 서비스.
 * 암호화 처리와 권한 설정을 캡슐화한다.
 */
public class UserService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	/**
	 * 관리자 계정을 생성한다.
	 */
	SiteUser createAdmin(String username, String email, String password, String phone) {
		SiteUser user = buildUser(username, email, password, phone);
		user.setRole(UserRole.ADMIN.getValue());
		return this.userRepository.save(user);
	}

	/**
	 * 일반 사용자 계정을 생성한다.
	 */
	SiteUser create(String username, String email, String password, String phone) {
		SiteUser user = buildUser(username, email, password, phone);
		user.setRole(UserRole.USER.getValue());
		return this.userRepository.save(user);
	}

	private SiteUser buildUser(String username, String email, String rawPassword, String phone) {
		SiteUser user = new SiteUser();
		user.setUsername(username);
		user.setEmail(email);
		user.setPassword(passwordEncoder.encode(rawPassword));
		user.setPhone(phone);
		user.ensureRoleInitialized();
		return user;
	}

	/**
	 * 사용자명을 기준으로 SiteUser를 조회한다.
	 */
	@Transactional(readOnly = true)
	public SiteUser getUser(String username) {
		Optional<SiteUser> siteUser = this.userRepository.findByUsername(username);
		if (siteUser.isPresent()) {
			SiteUser user = siteUser.get();
			user.ensureRoleInitialized();
			return user;
		} else {
			throw new DataNotFoundException("siteuser not found");
		}
	}

	/**
	 * 사용자 식별자로 SiteUser를 조회한다.
	 */
	@Transactional(readOnly = true)
	public SiteUser getUser(Long userId) {
		SiteUser user = this.userRepository.findById(userId)
			.orElseThrow(() -> new DataNotFoundException("siteuser not found"));
		user.ensureRoleInitialized();
		return user;
	}

	/**
	 * 전체 사용자 목록을 아이디 기준 오름차순으로 반환한다.
	 */
	@Transactional(readOnly = true)
	public List<SiteUser> getAllUsers() {
		List<SiteUser> users = this.userRepository.findAll(Sort.by(Sort.Order.asc("username")));
		users.forEach(SiteUser::ensureRoleInitialized);
		return users;
	}

	/**
	 * 사용자명을 기반으로 권한을 갱신한다.
	 */
	public SiteUser updateUserRole(String username, UserRole role) {
		SiteUser siteUser = getUser(username);
		siteUser.setRole(role.getValue());
		return this.userRepository.save(siteUser);
	}

	/**
	 * 사용자 식별자를 기반으로 권한을 갱신한다.
	 */
	public SiteUser updateUserRole(Long userId, UserRole role) {
		SiteUser siteUser = getUser(userId);
		siteUser.setRole(role.getValue());
		return this.userRepository.save(siteUser);
	}

	/**
	 * 관리자 계정을 권한만 조정해 승격한다.
	 */
	public SiteUser promoteToAdmin(String username) {
		return updateUserRole(username, UserRole.ADMIN);
	}
}
