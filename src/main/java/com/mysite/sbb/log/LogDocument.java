package com.mysite.sbb.log;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Elasticsearch에서 역직렬화할 로그 원문 모델.
 */
public class LogDocument {
	@JsonProperty("@timestamp")
	private Instant timestamp;
	@JsonProperty("level")
	private String level;
	@JsonProperty("service")
	private String service;
	@JsonProperty("log_type")
	private String logType;
	@JsonProperty("message")
	private String message;
	@JsonProperty("user_id")
	private String userId;
	@JsonProperty("user_ip")
	private String userIp;
	@JsonProperty("request_rul")
	private String requestUrl;
	@JsonProperty("http_method")
	private String httpMethod;
	@JsonProperty("status")
	private Integer status;
	@JsonProperty("duration_ms")
	private Long durationMs;
	@JsonProperty("user_agent")
	private String userAgent;
	@JsonProperty("trace_id")
	private String traceId;

	public Instant getTimestamp() {
		return timestamp;
	}

	public String getLevel() {
		return level;
	}

	public String getService() {
		return service;
	}

	public String getLogType() {
		return logType;
	}

	public String getMessage() {
		return message;
	}

	public String getUserId() {
		return userId;
	}

	public String getUserIp() {
		return userIp;
	}

	public String getRequestUrl() {
		return requestUrl;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public Integer getStatus() {
		return status;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public String getTraceId() {
		return traceId;
	}
}
