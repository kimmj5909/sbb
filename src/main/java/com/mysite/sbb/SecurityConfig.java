package com.mysite.sbb;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
/**
 * 전체 애플리케이션의 Spring Security 설정.
 * - URL 접근 제어, H2 콘솔 허용, 로그인/로그아웃 정책, 암호화 빈을 정의한다.
 */
public class SecurityConfig {
	/**
	 * HTTP 보안 구성을 정의한다.
	 * - 관리자 경로 권한 제한, CSRF 예외, 프레임 옵션, 커스텀 로그인/로그아웃을 설정한다.
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests((authorizeHttpRequests) -> authorizeHttpRequests
					.requestMatchers(new AntPathRequestMatcher("/admin/**")).hasRole("ADMIN") //관리자 페이지 접근 권한부여
					.requestMatchers(new AntPathRequestMatcher("/**")).permitAll())
					 .csrf((csrf) -> csrf
							.ignoringRequestMatchers(new AntPathRequestMatcher("/h2-console/**"))
							.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())) 
					 .headers((headers) -> headers
							.addHeaderWriter(new XFrameOptionsHeaderWriter(
							XFrameOptionsHeaderWriter.XFrameOptionsMode.SAMEORIGIN)))
					 .formLogin((formLogin) -> formLogin
							 .loginPage("/user/login")
							 .defaultSuccessUrl("/"))
					 		.logout((logout)-> logout
					 			.logoutRequestMatcher(new AntPathRequestMatcher("/user/logout"))
					 			.logoutSuccessUrl("/")
					 			.invalidateHttpSession(true))
			;
		return http.build();
		
	}
	
	/**
	 * BCrypt 기반 PasswordEncoder 빈 등록.
	 */
		@Bean
		public PasswordEncoder passwordEncoder() {
			return new BCryptPasswordEncoder();
		}
	
	/**
	 * AuthenticationManager를 Spring Context에서 추출해 노출한다.
	 */
		@Bean
		public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
			return authenticationConfiguration.getAuthenticationManager();
		}
	}
