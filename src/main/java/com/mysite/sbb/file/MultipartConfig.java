package com.mysite.sbb.file;

import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import jakarta.servlet.MultipartConfigElement;

@Configuration
/**
 * 파일 업로드 처리에 필요한 Multipart 설정을 구성한다.
 * 업로드 허용 용량 및 Resolver 구현체를 Spring Bean으로 등록한다.
 */
public class MultipartConfig {
	
	/**
	 * 업로드 가능한 단일 파일/요청의 최대 크기를 지정한다.
	 */
	@Bean
	public MultipartConfigElement multipartConfigElement() {
		MultipartConfigFactory factory = new MultipartConfigFactory();
		factory.setMaxFileSize(DataSize.ofMegabytes(10));
		factory.setMaxRequestSize(DataSize.ofMegabytes(50));
		return factory.createMultipartConfig();
	}
	
	/**
	 * Servlet 3.0 표준 기반 MultipartResolver 빈을 노출한다.
	 */
	@Bean
	public MultipartResolver multipartResolver() {
		return new StandardServletMultipartResolver();
	}

}
