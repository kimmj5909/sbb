package com.mysite.sbb.legaldong.mois;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * MOIS 게시판 HTML을 수집/파싱해 게시물/첨부 목록을 추출하고, 첨부파일을 다운로드한다.
 *
 * 설계 포인트
 * - MOIS 게시판은 공식 API가 아닌 "HTML 게시판" 형태이므로, HTML 구조 변경에 상대적으로 취약하다.
 * - 따라서 파싱 로직은 "특정 CSS 클래스"에 의존하기보다는,
 *   - 게시물 링크(commonSelectBoardArticle.do)
 *   - 첨부 링크(FileDown.do)
 *   같은 비교적 안정적인 URL 패턴을 기반으로 추출한다.
 *
 * 주의사항
 * - 게시물 "작성 시간"은 알 수 없다고 가정하므로, 목록의 "등록일(yyyy-MM-dd)"을 기준으로 윈도우 필터링한다.
 */
@Component
public class MoisLegalDongBoardClient {

	private static final Pattern NTT_ID = Pattern.compile("(?i)(?:\\?|&)nttId=(\\d+)");
	private static final Pattern YYYY_MM_DD = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
	private static final Pattern YYYY_DOT_MM_DOT_DD = Pattern.compile("\\b(\\d{4})\\.(\\d{2})\\.(\\d{2})\\.?\\b");
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final MoisLegalDongProperties properties;
	private final RestTemplate restTemplate;

	public MoisLegalDongBoardClient(MoisLegalDongProperties properties, RestTemplateBuilder restTemplateBuilder) {
		this.properties = properties;
		int timeoutMs = Math.toIntExact(properties.getHttpTimeout().toMillis());
		this.restTemplate = restTemplateBuilder
				.requestFactory(() -> {
					// MOIS 응답 헤더(파일 다운로드)의 파일명이 비ASCII/깨진 인코딩으로 내려오는 케이스가 있어,
					// JDK HttpClient 기반 구현체는 ProtocolException(Invalid header value)을 발생시킬 수 있다.
					// HttpURLConnection 기반(SimpleClientHttpRequestFactory)은 상대적으로 관대해 실서비스 다운로드에 적합하다.
					SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
					factory.setConnectTimeout(timeoutMs);
					factory.setReadTimeout(timeoutMs);
					return factory;
				})
				.additionalInterceptors((request, body, execution) -> {
					request.getHeaders().set(HttpHeaders.USER_AGENT, properties.getUserAgent());
					return execution.execute(request, body);
				})
				.build();
	}

	/**
	 * 등록일(LocalDate) 기준으로 [fromDate, toDate] 범위에 포함되는 게시물 목록을 수집한다.
	 *
	 * - 목록은 최신순(등록일 내림차순, nttId 내림차순)으로 정렬한다.
	 * - 게시판에 하루치 게시물이 10개를 넘어갈 수 있어, 기본적으로 1페이지부터 순차 페이지를 조회한다.
	 * - 구현은 'pageIndex' 파라미터를 사용한다(전자정부 프레임워크 게시판 계열에서 흔한 방식).
	 */
	public List<PostSummary> fetchPostSummaries(LocalDate fromDate, LocalDate toDate) {
		List<PostSummary> summaries = new ArrayList<>();
		for (int pageIndex = 1; pageIndex <= 50; pageIndex++) {
			URI pageUrl = buildListUrl(pageIndex);
			String html = getHtml(pageUrl);

			List<PostSummary> pageSummaries = parsePostSummariesFromHtml(html, fromDate, toDate, pageUrl);
			if (pageSummaries.isEmpty()) {
				// 범위에 해당하는 글이 더 이상 없다고 판단되면 중단한다.
				// (게시판이 최신순 정렬이라면 뒤 페이지로 갈수록 등록일이 오래된다)
				break;
			}

			summaries.addAll(pageSummaries);

			// 페이지 하단이 오래된 날짜로 넘어갔는지 확인해 불필요한 요청을 줄인다.
			Optional<LocalDate> oldest = pageSummaries.stream()
					.map(PostSummary::getRegisteredDate)
					.min(Comparator.naturalOrder());
			if (oldest.isPresent() && oldest.get().isBefore(fromDate)) {
				break;
			}
		}

		summaries.sort(Comparator
				.comparing(PostSummary::getRegisteredDate).reversed()
				.thenComparing(PostSummary::getNttId).reversed());
		return summaries;
	}

	/**
	 * 목록 1페이지에서 "가장 최신" 게시물을 하나 선택해 반환한다.
	 *
	 * - "마지막 게시물(최신 게시물)" 다운로드 테스트용 편의 메서드
	 * - 작성 시간은 알 수 없다고 가정하므로, 등록일(yyyy-MM-dd)과 nttId(게시물 번호)를 기준으로 최신을 판별한다.
	 */
	public PostSummary fetchLatestPostSummary() {
		URI pageUrl = buildListUrl(1);
		String html = getHtml(pageUrl);
		List<PostSummary> all = parseAllPostSummariesFromHtml(html, pageUrl);
		return all.stream()
				.max(Comparator
						.comparing(PostSummary::getRegisteredDate)
						.thenComparing(PostSummary::getNttId))
				.orElseThrow(() -> new IllegalStateException("게시판 목록에서 게시물을 찾을 수 없습니다."));
	}

	/**
	 * 게시물 상세 HTML을 파싱해 첨부파일 다운로드 링크 목록을 반환한다.
	 */
	public PostDetail fetchPostDetail(URI articleUrl, long nttId, String title, LocalDate registeredDate) {
		String html = getHtml(articleUrl);
		Document doc = Jsoup.parse(html, articleUrl.toString());

		List<Attachment> attachments = new ArrayList<>();
		Elements links = doc.select("a[href*='/cmm/fms/FileDown.do']");
		for (Element link : links) {
			String href = link.attr("href");
			if (!StringUtils.hasText(href)) {
				continue;
			}
			URI downloadUrl = articleUrl.resolve(href);
			String suggestedName = link.text();
			attachments.add(new Attachment(downloadUrl, suggestedName));
		}

		return new PostDetail(nttId, title, registeredDate, articleUrl, attachments);
	}

	/**
	 * 첨부파일을 targetDir 하위로 다운로드한다.
	 *
	 * - 파일명은 (1) Content-Disposition 헤더의 filename (2) 링크 텍스트 (3) fallback 순으로 결정한다.
	 * - 파일명은 OS 파일시스템에 안전하도록 일부 문자를 치환한다.
	 * - 다운로드 중 네트워크 오류가 발생하면 IOException을 throw한다(상위에서 게시물 FAILURE로 기록).
	 */
	public Path downloadAttachment(URI downloadUrl, URI refererUrl, Path targetDir, String suggestedName) throws IOException {
		Files.createDirectories(targetDir);

		return restTemplate.execute(
				downloadUrl,
				HttpMethod.GET,
				request -> {
					request.getHeaders().set(HttpHeaders.REFERER, refererUrl.toString());
					request.getHeaders().setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
				},
				response -> writeResponseToFile(response, targetDir, suggestedName));
	}

	Path writeResponseToFile(ClientHttpResponse response, Path targetDir, String suggestedName) throws IOException {
		// MOIS 다운로드 응답의 Content-Disposition 파일명은 인코딩이 깨진 상태로 내려오는 경우가 있어
		// 파일명이 깨지거나(제어문자 포함), Java HTTP 클라이언트에서 예외를 유발할 수 있다.
		// 따라서 "링크 텍스트(HTML)"를 우선하고, 비어있을 때만 헤더 파일명을 사용한다.
		String fileName = StringUtils.hasText(suggestedName)
				? suggestedName
				: extractFileName(response.getHeaders()).orElse(null);
		if (!StringUtils.hasText(fileName)) {
			fileName = "attachment.bin";
		}
		fileName = sanitizeFileName(fileName);

		Path targetFile = targetDir.resolve(fileName);
		if (Files.exists(targetFile)) {
			return targetFile;
		}

		try (InputStream in = response.getBody()) {
			Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
		}
		return targetFile;
	}

	Optional<String> extractFileName(HttpHeaders headers) {
		// Content-Disposition: attachment; filename="...."
		String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
		if (!StringUtils.hasText(disposition)) {
			return Optional.empty();
		}
		Matcher matcher = Pattern.compile("filename\\*?=(?:UTF-8''|\\\")?([^\\\";]+)").matcher(disposition);
		if (!matcher.find()) {
			return Optional.empty();
		}
		String raw = matcher.group(1).trim();
		return Optional.of(raw);
	}

	URI buildListUrl(int pageIndex) {
		return UriComponentsBuilder.fromHttpUrl(properties.getBoardListUrl())
				.queryParam("bbsId", properties.getBbsId())
				.queryParam("pageIndex", pageIndex)
				.build(true)
				.toUri();
	}

	String getHtml(URI url) {
		return restTemplate.getForObject(url, String.class);
	}

	List<PostSummary> parsePostSummariesFromHtml(String html, LocalDate fromDate, LocalDate toDate, URI baseUrl) {
		Document doc = Jsoup.parse(html, baseUrl.toString());
		Elements rows = doc.select("table tbody tr");

		List<PostSummary> results = new ArrayList<>();
		for (Element row : rows) {
			PostSummary summary = parseRow(row, baseUrl);
			if (summary == null) {
				continue;
			}
			if (summary.getRegisteredDate().isBefore(fromDate) || summary.getRegisteredDate().isAfter(toDate)) {
				continue;
			}
			results.add(summary);
		}
		return results;
	}

	List<PostSummary> parseAllPostSummariesFromHtml(String html, URI baseUrl) {
		Document doc = Jsoup.parse(html, baseUrl.toString());
		Elements rows = doc.select("table tbody tr");

		List<PostSummary> results = new ArrayList<>();
		for (Element row : rows) {
			PostSummary summary = parseRow(row, baseUrl);
			if (summary == null) {
				continue;
			}
			results.add(summary);
		}
		return results;
	}

	PostSummary parseRow(Element row, URI baseUrl) {
		Element link = row.selectFirst("a[href*='commonSelectBoardArticle.do']");
		if (link == null) {
			return null;
		}

		String href = link.attr("href");
		if (!StringUtils.hasText(href)) {
			return null;
		}

		Long nttId = extractNttId(href);
		if (nttId == null) {
			return null;
		}

		LocalDate registeredDate = extractRegisteredDate(row.text());
		if (registeredDate == null) {
			return null;
		}

		String title = link.text();
		if (!StringUtils.hasText(title)) {
			title = "(제목 없음)";
		}

		URI articleUrl = baseUrl.resolve(href);
		return new PostSummary(nttId, title, registeredDate, articleUrl);
	}

	Long extractNttId(String href) {
		Matcher matcher = NTT_ID.matcher(href);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Long.parseLong(matcher.group(1));
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	LocalDate extractRegisteredDate(String text) {
		Matcher matcher = YYYY_MM_DD.matcher(text);
		if (!matcher.find()) {
			Matcher dot = YYYY_DOT_MM_DOT_DD.matcher(text);
			if (!dot.find()) {
				return null;
			}
			try {
				int y = Integer.parseInt(dot.group(1));
				int m = Integer.parseInt(dot.group(2));
				int d = Integer.parseInt(dot.group(3));
				return LocalDate.of(y, m, d);
			} catch (RuntimeException ex) {
				return null;
			}
		}
		String raw = matcher.group(1);
		try {
			return LocalDate.parse(raw);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	String sanitizeFileName(String fileName) {
		// Windows/Unix 공통으로 문제 되는 문자들을 최대한 단순 치환한다.
		String cleaned = fileName
				.replace("\\", "_")
				.replace("/", "_")
				.replace(":", "_")
				.replace("*", "_")
				.replace("?", "_")
				.replace("\"", "_")
				.replace("<", "_")
				.replace(">", "_")
				.replace("|", "_")
				.trim();

		// 일부 링크 텍스트는 "파일명 (123KB)"처럼 부가 정보가 섞인다. 괄호 표기는 제거한다.
		cleaned = cleaned.replaceAll("\\s*\\([^)]*\\)\\s*$", "");
		// MOIS 게시판 링크 텍스트는 "파일명.ext [ 59.6 KB ]" 형태로 용량 표기가 붙는 경우가 많아 제거한다.
		cleaned = cleaned.replaceAll("\\s*\\[[^\\]]*\\]\\s*$", "");

		if (!StringUtils.hasText(cleaned)) {
			return "attachment.bin";
		}

		// 윈도우 예약 파일명 방지(간단 처리).
		String upper = cleaned.toUpperCase(Locale.ROOT);
		if (upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL")) {
			return cleaned + "_" + LocalDate.now().format(YYYYMMDD);
		}
		return cleaned;
	}

	public static class PostSummary {
		private final long nttId;
		private final String title;
		private final LocalDate registeredDate;
		private final URI articleUrl;

		public PostSummary(long nttId, String title, LocalDate registeredDate, URI articleUrl) {
			this.nttId = nttId;
			this.title = title;
			this.registeredDate = registeredDate;
			this.articleUrl = articleUrl;
		}

		public long getNttId() {
			return nttId;
		}

		public String getTitle() {
			return title;
		}

		public LocalDate getRegisteredDate() {
			return registeredDate;
		}

		public URI getArticleUrl() {
			return articleUrl;
		}
	}

	public static class PostDetail {
		private final long nttId;
		private final String title;
		private final LocalDate registeredDate;
		private final URI articleUrl;
		private final List<Attachment> attachments;

		public PostDetail(long nttId, String title, LocalDate registeredDate, URI articleUrl, List<Attachment> attachments) {
			this.nttId = nttId;
			this.title = title;
			this.registeredDate = registeredDate;
			this.articleUrl = articleUrl;
			this.attachments = attachments;
		}

		public long getNttId() {
			return nttId;
		}

		public String getTitle() {
			return title;
		}

		public LocalDate getRegisteredDate() {
			return registeredDate;
		}

		public URI getArticleUrl() {
			return articleUrl;
		}

		public List<Attachment> getAttachments() {
			return attachments;
		}
	}

	public static class Attachment {
		private final URI downloadUrl;
		private final String suggestedName;

		public Attachment(URI downloadUrl, String suggestedName) {
			this.downloadUrl = downloadUrl;
			this.suggestedName = suggestedName;
		}

		public URI getDownloadUrl() {
			return downloadUrl;
		}

		public String getSuggestedName() {
			return suggestedName;
		}
	}
}
