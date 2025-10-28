package com.mysite.sbb.user;

import java.util.Optional;
import com.mysite.sbb.DataNotFoundException;

//import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
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
		SiteUser user = new SiteUser();
		user.setUsername(username);
		user.setEmail(email);
		//BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
		user.setPassword(passwordEncoder.encode(password));
		user.setPhone(phone);
		user.setRole(UserRole.ADMIN.getValue());
		this.userRepository.save(user);
		return user;
	}
	
	/**
	 * 일반 사용자 계정을 생성한다.
	 */
	SiteUser create(String username, String email, String password, String phone) {
		SiteUser user = new SiteUser();
		user.setUsername(username);
		user.setEmail(email);
		//BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
		user.setPassword(passwordEncoder.encode(password));
		user.setPhone(phone);
		user.setRole(UserRole.USER.getValue());
		this.userRepository.save(user);
		return user;
	}
	/**
	 * 사용자명을 기준으로 SiteUser를 조회한다.
	 */
	public SiteUser getUser(String username) {
		Optional<SiteUser> siteUser = this.userRepository.findByUsername(username);
		if (siteUser.isPresent()) {
			return siteUser.get();
		}else {
			throw new DataNotFoundException("siteuser not found");
		}
	}	
}
