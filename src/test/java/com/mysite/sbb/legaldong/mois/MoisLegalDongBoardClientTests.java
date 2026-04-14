package com.mysite.sbb.legaldong.mois;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

/**
 * MOIS 게시판 파서의 핵심 동작을 "샘플 HTML"로 검증한다.
 *
 * - 실제 MOIS 사이트에 네트워크 호출을 하지 않는다.
 * - HTML 구조가 바뀌더라도, 최소한 URL 패턴 기반 추출 로직이 유지되는지 확인하는 안전장치 역할을 한다.
 */
public class MoisLegalDongBoardClientTests {

	@Test
	void shouldExtractNttId_fromArticleHref() {
		MoisLegalDongBoardClient client = newClient();
		Long nttId = client.extractNttId("/frt/bbs/type001/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000052&nttId=122595");
		assertEquals(122595L, nttId);
	}

	@Test
	void shouldParsePostSummaries_withDateFiltering() {
		MoisLegalDongBoardClient client = newClient();

		String html = """
				<html>
				<body>
				  <table>
				    <tbody>
				      <tr>
				        <td>373</td>
				        <td>
				          <a href="/frt/bbs/type001/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000052&nttId=122595">테스트 게시물</a>
				        </td>
				        <td>홍길동</td>
				        <td>2026-01-29</td>
				      </tr>
				      <tr>
				        <td>372</td>
				        <td>
				          <a href="/frt/bbs/type001/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000052&nttId=122594">과거 게시물</a>
				        </td>
				        <td>홍길동</td>
				        <td>2026-01-10</td>
				      </tr>
				    </tbody>
				  </table>
				</body>
				</html>
				""";

		LocalDate from = LocalDate.of(2026, 1, 28);
		LocalDate to = LocalDate.of(2026, 1, 29);
		List<MoisLegalDongBoardClient.PostSummary> summaries = client.parsePostSummariesFromHtml(
				html,
				from,
				to,
				URI.create("https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardList.do?bbsId=BBSMSTR_000000000052"));

		assertEquals(1, summaries.size());
		assertEquals(122595L, summaries.get(0).getNttId());
		assertEquals("테스트 게시물", summaries.get(0).getTitle());
		assertEquals(LocalDate.of(2026, 1, 29), summaries.get(0).getRegisteredDate());
		assertNotNull(summaries.get(0).getArticleUrl());
	}

	private MoisLegalDongBoardClient newClient() {
		MoisLegalDongProperties properties = new MoisLegalDongProperties();
		return new MoisLegalDongBoardClient(properties, new RestTemplateBuilder());
	}
}

