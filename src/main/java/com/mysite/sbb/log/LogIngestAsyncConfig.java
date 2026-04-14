package com.mysite.sbb.log;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Elasticsearch 요청 로그 적재를 비동기로 처리하기 위한 전용 실행기 설정.
 *
 * 목적
 * - ES 장애/지연이 웹 요청 응답 시간을 직접 늘리지 않도록, 적재 작업을 별도 스레드로 분리한다.
 *
 * 주의
 * - 로깅은 best-effort이므로 큐가 포화되면 드롭될 수 있다.
 * - 업무 트래픽 보호가 최우선이므로, 스레드/큐 크기는 보수적으로 설정한다.
 */
@Configuration
public class LogIngestAsyncConfig {

	@Bean(name = "logIngestExecutor")
	public TaskExecutor logIngestExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("log-ingest-");
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(500);
		executor.setWaitForTasksToCompleteOnShutdown(false);
		executor.initialize();
		return executor;
	}
}
