package com.mysite.sbb.tmp;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
/**
 * Sample 엔드포인트를 제공하는 임시 컨트롤러.
 */
public class HelloController {
	
	@GetMapping("/hello")
	@ResponseBody
	
	/**
	 * 단순 문자열 응답을 반환한다.
	 */
	public String hello() {
		return "Hello Spring Boot Board";		
	}
}
