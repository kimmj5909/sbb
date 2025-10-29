package com.mysite.sbb.tmp;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
/**
 * 점프 투 스프링 부트 실습용 컨트롤러.
 */
public class Jump {
	
	@GetMapping("/jump")
	@ResponseBody
	
	/**
	 * 점프 투 스프링 부트 안내 문구를 반환한다.
	 */
	public String hello() {
		return "점프 투 스프링 부트";		
	}
}
