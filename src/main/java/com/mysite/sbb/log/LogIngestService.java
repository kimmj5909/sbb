package com.mysite.sbb.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.core.task.TaskExecutor;

import com.fasterxml.jackson.databind.ObjectMapper;

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
	private final TaskExecutor ingestExecutor;
	private final ObjectMapper objectMapper;
	private final HttpLogProperties httpLogProperties;
	private final Lock fileWriteLock = new ReentrantLock();

	public LogIngestService(ElasticsearchClient client, LogSearchProperties properties,
			@Qualifier("logIngestExecutor") TaskExecutor ingestExecutor,
			ObjectMapper objectMapper,
			HttpLogProperties httpLogProperties) {
		this.client = client;
		this.properties = properties;
		this.ingestExecutor = ingestExecutor;
		this.objectMapper = objectMapper;
		this.httpLogProperties = httpLogProperties;
	}

	/**
	 * 단일 요청 로그를 ES에 적재한다.
	 * - 실패 시 예외를 삼키고 WARN 로그만 남겨 API 응답 지연을 방지한다.
	 */
	public void ingest(HttpLogEvent event) {
		if (event == null) {
			return;
		}
		/*
		 * 중요: ES 적재는 "관측(옵저버빌리티)" 기능이므로 웹 요청을 절대 블로킹하면 안 된다.
		 * - afterCompletion()에서 동기 호출되면, ES 장애/지연(연결 타임아웃 등)만큼 화면 응답이 늦어진다.
		 * - 따라서 비동기 TaskExecutor로 분리해, 적재 실패/지연이 업무 트래픽에 영향을 주지 않게 한다.
		 */
		try {
			ingestExecutor.execute(() -> {
				/*
				 * HTTP 로그 처리 순서
				 * 1) 파일(JSONL) 저장: Filebeat 수집 대상
				 * 2) (기존) Elasticsearch 직접 적재: 유지
				 *
				 * 운영에서는 이중 적재를 피하기 위해 파일 저장만 사용하고 싶을 수 있다.
				 * - 그 경우 Logstash 파이프라인을 통해 ES로 적재되므로, 애플리케이션 direct ingest는 비활성화하도록
				 *   별도 토글을 두는 편이 낫다(필요 시 확장).
				 */
				writeToFileIfEnabled(event);
				doIngest(event);
			});
		} catch (RejectedExecutionException ex) {
			// 큐가 가득 차거나 스레드 풀이 포화된 경우: 로깅은 best-effort로 드롭한다.
			if (log.isDebugEnabled()) {
				log.debug("ES 로그 적재 스킵(큐 포화) message={}", ex.getMessage());
			}
		}
	}

	private void doIngest(HttpLogEvent event) {
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
		} catch (RuntimeException e) {
			// ES 클라이언트 내부 예외(예: serialization)도 업무 흐름을 막지 않도록 방어한다.
			log.warn("ES 로그 적재 실패(RuntimeException) index={}, message={}", index, e.getMessage());
		}
	}

	private void writeToFileIfEnabled(HttpLogEvent event) {
		if (httpLogProperties == null || !httpLogProperties.isEnabled()) {
			return;
		}
		String filePath = httpLogProperties.getFile();
		if (filePath == null || filePath.isBlank()) {
			return;
		}
		Path path = Path.of(filePath);
		try {
			/*
			 * 파일(JSONL) 저장 주의사항
			 * - 한 줄 = 한 이벤트(JSON)
			 * - Filebeat가 멀티라인 처리를 하지 않아도 되도록, 줄바꿈이 포함되지 않게 직렬화한다.
			 * - 동시 쓰기 경쟁을 막기 위해 단일 락으로 보호한다.
			 */
			String json = objectMapper.writeValueAsString(event);
			fileWriteLock.lock();
			try {
				Path parent = path.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				Files.writeString(path, json + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
			} finally {
				fileWriteLock.unlock();
			}
		} catch (Exception e) {
			// 관측 기능이므로 실패해도 업무 흐름을 막지 않는다.
			log.warn("HTTP 로그 파일 저장 실패 path={}, message={}", filePath, e.getMessage());
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
