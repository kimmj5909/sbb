package com.mysite.sbb.log;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 연결 및 로그 검색 기본 설정을 보관한다.
 */
@ConfigurationProperties(prefix = "sbb.elasticsearch")
public class LogSearchProperties {
	/**
	 * 쉼표로 구분된 Elasticsearch 호스트 목록.
	 * 기본: localhost만 사용해 WSL/호스트 간 NAT에서 127.0.0.1이 닿지 않는 환경을 방지한다.
	 */
	private String hosts = "http://localhost:9200";
	private String indexPrefix = "logs-web";
	private int connectTimeoutMs = 2000;
	private int socketTimeoutMs = 5000;
	private String username;
	private String password;
	/**
	 * Elasticsearch API Key 인증 값(Base64 인코딩된 token 문자열).
	 * - 설정되면 username/password(BasicAuth)보다 우선한다.
	 * - 일반적으로 "ApiKey {apiKey}" 형태로 Authorization 헤더에 실린다.
	 */
	private String apiKey;

	public String getHosts() {
		return hosts;
	}

	public void setHosts(String hosts) {
		this.hosts = hosts;
	}

	// 과거 설정 키(sbb.elasticsearch.urls) 호환을 위해 추가.
	public void setUrls(String urls) {
		this.hosts = urls;
	}

	public String getIndexPrefix() {
		return indexPrefix;
	}

	public void setIndexPrefix(String indexPrefix) {
		// Elasticsearch 인덱스 이름에는 공백/특수문자(예: space, *, ?, / 등)가 포함될 수 없다.
		// 설정 실수로 인한 invalid_index_name_exception을 예방하기 위해 바인딩 시 정규화한다.
		if (indexPrefix == null) {
			this.indexPrefix = "logs-web";
			return;
		}
		String normalized = indexPrefix.trim();
		normalized = normalized.replaceAll("[\\\\\\s\"<>*\\?\\|,\\/]+", "-");
		normalized = normalized.replaceAll("-{2,}", "-");
		normalized = normalized.replaceAll("^-|-$", "");
		this.indexPrefix = normalized.isBlank() ? "logs-web" : normalized;
	}

	public int getConnectTimeoutMs() {
		return connectTimeoutMs;
	}

	public void setConnectTimeoutMs(int connectTimeoutMs) {
		this.connectTimeoutMs = connectTimeoutMs;
	}

	public int getSocketTimeoutMs() {
		return socketTimeoutMs;
	}

	public void setSocketTimeoutMs(int socketTimeoutMs) {
		this.socketTimeoutMs = socketTimeoutMs;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}
}
