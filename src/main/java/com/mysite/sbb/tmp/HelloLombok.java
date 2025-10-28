package com.mysite.sbb.tmp;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
/**
 * Lombok 애너테이션 사용 예제를 위한 임시 클래스.
 */

public class HelloLombok {

	private final String Hello;
	private final int lombok;
	
	public static void main(String[] args) {
		HelloLombok helloLombok = new HelloLombok("헬로", 5);

		System.out.println(helloLombok.getHello());
		System.out.println(helloLombok.getLombok());
	}
}
