package com.mysite.sbb.log;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.SimpleQueryStringQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;

/**
 * Elasticsearch 로그 검색과 간단한 집계를 처리한다.
 */
@Service
public class LogSearchService {

	private static final Logger log = LoggerFactory.getLogger(LogSearchService.class);
	private final ElasticsearchClient client;
	private final LogSearchProperties properties;

	public LogSearchService(ElasticsearchClient client, LogSearchProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	public LogSearchResult search(LogSearchRequest request) {
		LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
		LocalDate fromDate = request.getFromDate() != null ? request.getFromDate() : todayUtc.minusDays(1);
		LocalDate toDate = request.getToDate() != null ? request.getToDate() : todayUtc;
		int size = request.getSize() > 0 ? Math.min(request.getSize(), 500) : 100;

		Instant fromInstant = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant toInstant = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusNanos(1).toInstant();

		Query criteria = buildQuery(request, fromInstant, toInstant);

		SearchRequest.Builder builder = new SearchRequest.Builder()
			.index(indexPattern())
			.size(size)
			.query(criteria)
			.sort(s -> s.field(f -> f.field("@timestamp").order(SortOrder.Desc)))
			.trackTotalHits(t -> t.enabled(true))
			.aggregations("level_counts", agg -> agg.terms(t -> t.field("level").size(10)))
			.aggregations("service_counts", agg -> agg.terms(t -> t.field("service").size(10)));

		try {
			SearchResponse<LogDocument> response = client.search(builder.build(), LogDocument.class);
			List<LogEntry> entries = mapHits(response.hits().hits());
			long total = response.hits().total() != null ? response.hits().total().value() : entries.size();
			Map<String, Long> levelCounts = extractBuckets(response, "level_counts");
			Map<String, Long> serviceCounts = extractBuckets(response, "service_counts");
			return new LogSearchResult(entries, total, levelCounts, serviceCounts, indexPattern());
		} catch (ElasticsearchException | IOException e) {
			log.warn("Elasticsearch 검색 중 오류 - indexPattern={}, hosts={}, message={}, cause={}",
				indexPattern(), properties.getHosts(), e.getMessage(),
				e.getCause() != null ? e.getCause().getMessage() : "n/a");
			throw new RuntimeException("Elasticsearch 검색에 실패했습니다. (" + e.getMessage() + ")", e);
		}
	}

	private Query buildQuery(LogSearchRequest request, Instant fromInstant, Instant toInstant) {
		List<Query> must = new ArrayList<>();
		List<Query> filters = new ArrayList<>();

		// 기간 필터는 필수로 포함한다.
		filters.add(Query.of(q -> q.range(new RangeQuery.Builder()
			.field("@timestamp")
			.gte(JsonData.of(fromInstant))
			.lte(JsonData.of(toInstant))
			.build())));

		if (StringUtils.hasText(request.getLevel())) {
			filters.add(Query.of(q -> q.term(t -> t.field("level").value(request.getLevel()))));
		}
		if (StringUtils.hasText(request.getService())) {
			filters.add(Query.of(q -> q.term(t -> t.field("service").value(request.getService()))));
		}
		if (StringUtils.hasText(request.getQuery())) {
			must.add(buildSimpleQuery(request.getQuery()));
		}

		return Query.of(q -> q.bool(new BoolQuery.Builder()
			.filter(filters)
			.must(must)
			.build()));
	}

	private Query buildSimpleQuery(String query) {
		// message 필드 우선, 없으면 전체 필드에서 검색하는 단순 KQL 대체용.
		SimpleQueryStringQuery sqs = new SimpleQueryStringQuery.Builder()
			.query(query)
			.defaultOperator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)
			.fields("message", "service", "user_id", "user_ip", "request_rul", "log_type", "http_method", "trace_id",
				"user_agent")
			.build();
		return Query.of(q -> q.simpleQueryString(sqs));
	}

	private List<LogEntry> mapHits(List<Hit<LogDocument>> hits) {
		if (hits == null || hits.isEmpty()) {
			return Collections.emptyList();
		}
		List<LogEntry> entries = new ArrayList<>(hits.size());
		for (Hit<LogDocument> hit : hits) {
			LogDocument doc = hit.source();
			if (doc == null) {
				continue;
			}
			entries.add(new LogEntry(
				hit.id(),
				doc.getTimestamp(),
				doc.getLevel(),
				doc.getService(),
				doc.getLogType(),
				doc.getMessage(),
				doc.getUserId(),
				doc.getUserIp(),
				doc.getRequestUrl(),
				doc.getHttpMethod(),
				doc.getStatus(),
				doc.getDurationMs(),
				doc.getUserAgent(),
				doc.getTraceId()));
		}
		return entries;
	}

	private Map<String, Long> extractBuckets(SearchResponse<LogDocument> response, String aggName) {
		if (response.aggregations() == null || !response.aggregations().containsKey(aggName)) {
			return Collections.emptyMap();
		}
		StringTermsAggregate aggregate = response.aggregations().get(aggName).sterms();
		if (aggregate == null || aggregate.buckets() == null) {
			return Collections.emptyMap();
		}
		Map<String, Long> result = new HashMap<>();
		aggregate.buckets().array().forEach(b -> result.put(b.key().stringValue(), b.docCount()));
		return result;
	}

	private String indexPattern() {
		return properties.getIndexPrefix() + "*";
	}
}
