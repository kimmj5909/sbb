package com.mysite.sbb.log;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;

/**
 * Elasticsearch로 HTTP 요청 로그를 적재한다.
 * 실패해도 애플리케이션 흐름을 막지 않고 WARN 로그만 남긴다.
 * - 데이터 스트림을 사용하는 인덱스 접두사를 그대로 사용한다.
 * - 인증/보안이 필요한 ES라면 향후 BasicAuth 설정을 추가한다.
 */
@Service
public class LogIngestService {

	private static final Logger log = LoggerFactory.getLogger(LogIngestService.class);
	private final ElasticsearchClient client;
	private final LogSearchProperties properties;

	public LogIngestService(ElasticsearchClient client, LogSearchProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	/**
	 * 단일 요청 로그를 ES에 적재한다.
	 * - 실패 시 예외를 삼키고 WARN 로그만 남겨 API 응답 지연을 방지한다.
	 */
	public void ingest(HttpLogEvent event) {
		if (event == null) {
			return;
		}
		String index = resolveIndexName();
		IndexRequest<HttpLogEvent> request = new IndexRequest.Builder<HttpLogEvent>()
			.index(index)
			.document(event)
			.build();
		try {
			IndexResponse response = client.index(request);
			if (log.isDebugEnabled()) {
				log.debug("ES 로그 적재 성공 index={}, id={}", response.index(), response.id());
			}
		} catch (ElasticsearchException | IOException e) {
			log.warn("ES 로그 적재 실패 index={}, hosts={}, message={}, cause={}",
				index, properties.getHosts(), e.getMessage(),
				e.getCause() != null ? e.getCause().getMessage() : "n/a");
		}
	}

	private String resolveIndexName() {
		// 데이터 스트림 사용 시 접두사 그대로 사용하고, 필요 시 외부 설정에서 날짜 suffix를 포함해 전달한다.
		String prefix = properties.getIndexPrefix();
		if (prefix.endsWith("*")) {
			return prefix.substring(0, prefix.length() - 1);
		}
		return prefix;
	}
}
