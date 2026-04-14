package com.mysite.sbb.log;

import org.apache.http.HttpHost;
import org.apache.http.Header;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;

/**
 * Elasticsearch Java API 클라이언트 설정.
 */
@Configuration
@EnableConfigurationProperties({LogSearchProperties.class, HttpLogProperties.class})
public class ElasticsearchConfig {

	@Bean(destroyMethod = "close")
	public RestClient elasticsearchRestClient(LogSearchProperties properties) {
		HttpHost[] hosts = HttpHostUtils.parseHosts(properties.getHosts());
		RestClientBuilder builder = RestClient.builder(hosts)
			.setRequestConfigCallback(config -> config
				.setConnectTimeout(properties.getConnectTimeoutMs())
				.setSocketTimeout(properties.getSocketTimeoutMs()));

		/*
		 * 인증 설정

		 * 2) Basic 인증 (fallback)
		 *
		 * [주의] application.properties에 토큰/비밀번호를 하드코딩하면 유출 위험이 크므로,
		 *       운영/공용 환경에서는 환경변수(ELASTIC_API_KEY / ELASTIC_PASSWORD) 사용을 권장한다.
		 */
		if (StringUtils.hasText(properties.getApiKey())) {
			Header[] defaultHeaders = new Header[] {
				new BasicHeader(HttpHeaders.AUTHORIZATION, "ApiKey " + properties.getApiKey())
			};
			builder.setDefaultHeaders(defaultHeaders);
			return builder.build();
		}

		// Basic 인증이 설정된 경우 자격 증명을 포함한다(ApiKey 미설정 시).
		if (StringUtils.hasText(properties.getUsername()) && StringUtils.hasText(properties.getPassword())) {
			CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
			credentialsProvider.setCredentials(AuthScope.ANY,
				new UsernamePasswordCredentials(properties.getUsername(), properties.getPassword()));
			builder.setHttpClientConfigCallback(httpClient -> httpClient.setDefaultCredentialsProvider(credentialsProvider));
		}
		return builder.build();
	}

	@Bean
	public ElasticsearchClient elasticsearchClient(RestClient restClient, ObjectMapper objectMapper) {
		ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
		return new ElasticsearchClient(transport);
	}
}
