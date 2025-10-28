package com.mysite.sbb;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
/**
 * 루트 경로 및 기본 인사 응답을 제공하는 컨트롤러.
 */
public class MainController {
	@GetMapping("/sbb")
	@ResponseBody
	/**
	 * 간단한 문자열을 반환해 애플리케이션이 살아 있는지 확인한다.
	 */
	public String index() {
		return "안녕하세요 sbb에 오신 것을 환영합니다";
		
	}

	/**
	 * 기본 루트 요청을 질문 목록으로 리다이렉트한다.
	 */
	@GetMapping("/")
	public String root() {
		return "redirect:/question/list";
	}
}
