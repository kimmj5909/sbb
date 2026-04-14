package com.mysite.sbb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
/**
 * Q&A 게시판 애플리케이션의 메인 엔트리 포인트.
 *
 * - 본 프로젝트는 화면 기반 기능과 별도로, 외부 기관 데이터(예: 법정동 변경 게시판)를 주기적으로 수집하는 배치 작업이 존재한다.
 * - 배치 작업은 @Scheduled 기반으로 동작하므로, 스케줄링을 전역으로 활성화한다.
 */
public class SbbApplication {

	public static void main(String[] args) {
		SpringApplication.run(SbbApplication.class, args);
	}

}
