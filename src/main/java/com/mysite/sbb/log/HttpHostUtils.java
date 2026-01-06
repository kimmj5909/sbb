package com.mysite.sbb.log;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.http.HttpHost;
import org.springframework.util.StringUtils;

/**
 * Elasticsearch 호스트 문자열을 HttpHost 배열로 변환하는 유틸리티.
 */
public final class HttpHostUtils {
	private HttpHostUtils() {
	}

	public static HttpHost[] parseHosts(String hosts) {
		if (!StringUtils.hasText(hosts)) {
			throw new IllegalArgumentException("Elasticsearch hosts가 비어 있습니다. sbb.elasticsearch.hosts를 설정하세요.");
		}
		List<HttpHost> parsed = Arrays.stream(hosts.split(","))
			.map(String::trim)
			.filter(StringUtils::hasText)
			.map(HttpHost::create)
			.collect(Collectors.toList());
		if (parsed.isEmpty()) {
			throw new IllegalArgumentException("Elasticsearch hosts를 파싱할 수 없습니다. 포맷을 확인하세요.");
		}
		return parsed.toArray(HttpHost[]::new);
	}
}
