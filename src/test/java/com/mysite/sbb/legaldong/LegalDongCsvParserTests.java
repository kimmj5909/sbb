package com.mysite.sbb.legaldong;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class LegalDongCsvParserTests {

	@Test
	void shouldParse8ColumnCsv_whenHeaderIsPresent() {
		String csv = """
				행정동코드,시도명,시군구명,읍면동명,법정동코드,동리명,생성일자,말소일자
				2811051000,인천광역시,중구,신포동,2811010100,중앙동1가,19950101,
				2811051000,인천광역시,중구,신포동,2811010200,중앙동2가,19950101,20260701
				""";

		LegalDongCsvParser parser = new LegalDongCsvParser();
		LegalDongCsvParser.ParseResult r = parser.parse(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		assertThat(r.getErrors()).isEmpty();
		assertThat(r.getRows()).hasSize(2);
		assertThat(r.getRows().get(0).getLegalDongCd()).isEqualTo("2811010100");
		assertThat(r.getRows().get(1).getLegalDongCd()).isEqualTo("2811010200");
		assertThat(r.getRows().get(1).getDltDt()).isNotNull();
	}

	@Test
	void shouldHandleUtf8BomInHeader() {
		String csv = "\uFEFF행정동코드,시도명,시군구명,읍면동명,법정동코드,동리명,생성일자,말소일자\n"
				+ "2811051000,인천광역시,중구,신포동,2811010100,중앙동1가,19950101,\n";

		LegalDongCsvParser parser = new LegalDongCsvParser();
		LegalDongCsvParser.ParseResult r = parser.parse(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		assertThat(r.getErrors()).isEmpty();
		assertThat(r.getRows()).hasSize(1);
		assertThat(r.getRows().get(0).getCtprvNm()).isEqualTo("인천광역시");
	}
}

