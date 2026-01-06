package com.mysite.sbb.log;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * HTTP 요청-응답 단위 로그 이벤트 모델.
 * ES 문서 스키마에 맞춘 필드 이름(@JsonProperty)으로 직렬화한다.
 */
public class HttpLogEvent {
	@JsonProperty("@timestamp")
	private final Instant timestamp;
	private final String level;
	private final String service;
	@JsonProperty("log_type")
	private final String logType;
	private final String message;
	@JsonProperty("user_id")
	private final String userId;
	@JsonProperty("user_ip")
	private final String userIp;
	@JsonProperty("request_rul")
	private final String requestUrl;
	@JsonProperty("http_method")
	private final String httpMethod;
	private final Integer status;
	@JsonProperty("duration_ms")
	private final Long durationMs;
	@JsonProperty("user_agent")
	private final String userAgent;
	@JsonProperty("trace_id")
	private final String traceId;

	private HttpLogEvent(Builder builder) {
		this.timestamp = builder.timestamp;
		this.level = builder.level;
		this.service = builder.service;
		this.logType = builder.logType;
		this.message = builder.message;
		this.userId = builder.userId;
		this.userIp = builder.userIp;
		this.requestUrl = builder.requestUrl;
		this.httpMethod = builder.httpMethod;
		this.status = builder.status;
		this.durationMs = builder.durationMs;
		this.userAgent = builder.userAgent;
		this.traceId = builder.traceId;
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

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 필수/선택 필드를 명시적으로 세팅하기 위한 빌더.
	 */
	public static class Builder {
		private Instant timestamp;
		private String level;
		private String service;
		private String logType = "http";
		private String message;
		private String userId;
		private String userIp;
		private String requestUrl;
		private String httpMethod;
		private Integer status;
		private Long durationMs;
		private String userAgent;
		private String traceId;

		public Builder timestamp(Instant timestamp) {
			this.timestamp = timestamp;
			return this;
		}

		public Builder level(String level) {
			this.level = level;
			return this;
		}

		public Builder service(String service) {
			this.service = service;
			return this;
		}

		public Builder logType(String logType) {
			this.logType = logType;
			return this;
		}

		public Builder message(String message) {
			this.message = message;
			return this;
		}

		public Builder userId(String userId) {
			this.userId = userId;
			return this;
		}

		public Builder userIp(String userIp) {
			this.userIp = userIp;
			return this;
		}

		public Builder requestUrl(String requestUrl) {
			this.requestUrl = requestUrl;
			return this;
		}

		public Builder httpMethod(String httpMethod) {
			this.httpMethod = httpMethod;
			return this;
		}

		public Builder status(Integer status) {
			this.status = status;
			return this;
		}

		public Builder durationMs(Long durationMs) {
			this.durationMs = durationMs;
			return this;
		}

		public Builder userAgent(String userAgent) {
			this.userAgent = userAgent;
			return this;
		}

		public Builder traceId(String traceId) {
			this.traceId = traceId;
			return this;
		}

		public HttpLogEvent build() {
			return new HttpLogEvent(this);
		}
	}
}
