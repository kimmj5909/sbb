package com.mysite.sbb.log;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mysite.sbb.user.UserRepository;

/**
 * HTTP 요청 로깅 인터셉터를 등록한다.
 * 정적 리소스/에셋 경로를 제외해 로그 노이즈를 줄인다.
 */
@Configuration
public class LogWebMvcConfig implements WebMvcConfigurer {

	private final LogIngestService logIngestService;
	private final UserRepository userRepository;
	private final String serviceName;

	public LogWebMvcConfig(LogIngestService logIngestService, UserRepository userRepository,
		@Value("${spring.application.name:sbb}") String serviceName) {
		this.logIngestService = logIngestService;
		this.userRepository = userRepository;
		this.serviceName = serviceName;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RequestLoggingInterceptor(logIngestService, userRepository, serviceName))
			.excludePathPatterns(
				"/static/**",
				"/css/**",
				"/js/**",
				"/images/**",
				"/assets/**",
				"/webjars/**",
				"/favicon.ico",
				"/**/*.css",
				"/**/*.js",
				"/**/*.map",
				"/**/*.png",
				"/**/*.jpg",
				"/**/*.jpeg",
				"/**/*.gif",
				"/**/*.svg",
				"/**/*.ico",
				"/**/*.woff",
				"/**/*.woff2",
				"/**/*.ttf",
				"/error");
	}
}
