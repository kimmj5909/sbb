package com.mysite.sbb.log;

import java.time.Instant;

/**
 * 화면에 표시할 단일 로그 엔트리.
 */
public class LogEntry {
	private final String id;
	private final Instant timestamp;
	private final String level;
	private final String service;
	private final String logType;
	private final String message;
	private final String userId;
	private final String userIp;
	private final String requestUrl;
	private final String httpMethod;
	private final Integer status;
	private final Long durationMs;
	private final String userAgent;
	private final String traceId;

	public LogEntry(String id, Instant timestamp, String level, String service, String logType, String message,
		String userId, String userIp, String requestUrl, String httpMethod, Integer status, Long durationMs,
		String userAgent, String traceId) {
		this.id = id;
		this.timestamp = timestamp;
		this.level = level;
		this.service = service;
		this.logType = logType;
		this.message = message;
		this.userId = userId;
		this.userIp = userIp;
		this.requestUrl = requestUrl;
		this.httpMethod = httpMethod;
		this.status = status;
		this.durationMs = durationMs;
		this.userAgent = userAgent;
		this.traceId = traceId;
	}

	public String getId() {
		return id;
	}

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
