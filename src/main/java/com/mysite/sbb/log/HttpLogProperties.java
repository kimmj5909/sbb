package com.mysite.sbb.log;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP 요청/응답 로그를 파일로 남기기 위한 설정.
 * <p>
 * 목적:
 * - 애플리케이션에서 생성한 "요청 단위" 구조화 로그({@link HttpLogEvent})를 JSON 라인으로 파일에 저장한다.
 * - Filebeat가 해당 파일을 읽어 Redis/Logstash 등을 통해 Elasticsearch로 적재할 수 있다.
 * </p>
 */
@ConfigurationProperties(prefix = "sbb.http-log")
public class HttpLogProperties {

	/**
	 * 파일 저장 활성화 여부.
	 */
	private boolean enabled = false;

	/**
	 * JSON 라인 로그 파일 경로.
	 * - 예: ./logs/http-log.jsonl
	 * - Filebeat input paths에 이 경로를 추가해 수집한다.
	 */
	private String file = "./logs/http-log.jsonl";

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getFile() {
		return file;
	}

	public void setFile(String file) {
		this.file = file;
	}
}

