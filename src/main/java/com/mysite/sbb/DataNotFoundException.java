package com.mysite.sbb;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 요청한 엔티티가 존재하지 않을 때 발생시키는 사용자 정의 예외.
 * 컨트롤러 계층에서 404 응답을 자동으로 리턴하도록 `@ResponseStatus`를 지정했다.
 */
@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "entity not found")
public class DataNotFoundException extends RuntimeException {
	private static final long serialVersionUID =1L;
	public DataNotFoundException(String message) {
		super(message);
	}

	
}
