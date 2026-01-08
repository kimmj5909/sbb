package com.mysite.sbb.legaldong;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;

import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * 법정동 마이그레이션 핵심 규칙(단위별 하위 없음 처리, rank 카운트)을 고정하는 테스트.
 *
 * - DB 업서트는 별도 통합 테스트가 필요하므로 여기서는 preview(파싱+파생)만 검증한다.
 * - 엑셀 셀 서식 차이(숫자/문자)를 흉내 내기 위해 숫자 값도 함께 넣는다.
 */
public class LegalDongMigrationServiceTests {

	@Test
	void shouldDeriveUnitsAndRanks_whenPreviewingXlsx() throws Exception {
		byte[] xlsx = createSampleWorkbook();

		LegalDongExcelParser parser = new LegalDongExcelParser();
		LegalDongMigrationService service = new LegalDongMigrationService(
				parser,
				new LegalDongJdbcUpsertRepository(null),
				null,
				null);

		LegalDongMigrationPreviewResult preview = service.preview(xlsx, 0, 20);

		assertThat(preview.getErrors()).isEmpty();
		assertThat(preview.getDerivedRows()).isEqualTo(4);
		assertThat(preview.getUpperRows()).isEqualTo(2);
		assertThat(preview.getLowerRows()).isEqualTo(2);

		// 1) 상위(리 없음): upperRank = 1
		assertThat(preview.getEntries().get(0).getLegalDongCd()).isEqualTo("4159125000");
		assertThat(preview.getEntries().get(0).getRank()).isEqualTo(1);
		assertThat(preview.getEntries().get(0).getLiCd()).isNull();
		assertThat(preview.getEntries().get(0).getLegalDongNm()).isEqualTo("경기도 화성시 우정읍");

		// 2) 하위(리 있음): lowerRank = 1
		assertThat(preview.getEntries().get(1).getLegalDongCd()).isEqualTo("4159125021");
		assertThat(preview.getEntries().get(1).getRank()).isEqualTo(1);
		assertThat(preview.getEntries().get(1).getLiCd()).isEqualTo("4159125021");
		assertThat(preview.getEntries().get(1).getLegalDongNm()).isEqualTo("경기도 화성시 우정읍 원안리");

		// 3) 하위(리 있음): lowerRank = 2
		assertThat(preview.getEntries().get(2).getLegalDongCd()).isEqualTo("4159125022");
		assertThat(preview.getEntries().get(2).getRank()).isEqualTo(2);

		// 4) 시도 종결(하위 없음): upperRank = 2
		assertThat(preview.getEntries().get(3).getLegalDongCd()).isEqualTo("1100000000");
		// 시도 단위(시군구/읍면동 없음)는 rank를 1로 둔다.
		assertThat(preview.getEntries().get(3).getRank()).isEqualTo(1);
		assertThat(preview.getEntries().get(3).getLegalDongNm()).isEqualTo("서울특별시");
	}

	private byte[] createSampleWorkbook() throws Exception {
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			XSSFSheet sheet = workbook.createSheet("Sheet1");

			XSSFRow header = sheet.createRow(0);
			header.createCell(0).setCellValue("행정동코드");
			header.createCell(1).setCellValue("시도명");
			header.createCell(2).setCellValue("시군구명");
			header.createCell(3).setCellValue("읍면동명");
			header.createCell(4).setCellValue("법정동코드");
			header.createCell(5).setCellValue("동리명");
			header.createCell(6).setCellValue("생성일자");
			header.createCell(7).setCellValue("말소일자");

			// 상위(리 없음): 4159125000
			XSSFRow r1 = sheet.createRow(1);
			r1.createCell(0).setCellValue("4159125000");
			r1.createCell(1).setCellValue("경기도");
			r1.createCell(2).setCellValue("화성시");
			r1.createCell(3).setCellValue("우정읍");
			r1.createCell(4).setCellValue(4159125000d); // 숫자 셀
			r1.createCell(6).setCellValue("20260201");

			// 하위(리 있음): 4159125021
			XSSFRow r2 = sheet.createRow(2);
			r2.createCell(0).setCellValue("4159125000");
			r2.createCell(1).setCellValue("경기도");
			r2.createCell(2).setCellValue("화성시");
			r2.createCell(3).setCellValue("우정읍");
			r2.createCell(4).setCellValue("4159125021");
			r2.createCell(5).setCellValue("원안리");
			r2.createCell(6).setCellValue("20260201");

			// 하위(리 있음): 4159125022
			XSSFRow r3 = sheet.createRow(3);
			r3.createCell(0).setCellValue("4159125000");
			r3.createCell(1).setCellValue("경기도");
			r3.createCell(2).setCellValue("화성시");
			r3.createCell(3).setCellValue("우정읍");
			r3.createCell(4).setCellValue("4159125022");
			r3.createCell(5).setCellValue("호곡리");
			r3.createCell(6).setCellValue("20260201");

			// 시도 종결 예시: 1100000000 (11/000/000/00)
			XSSFRow r4 = sheet.createRow(4);
			r4.createCell(1).setCellValue("서울특별시");
			r4.createCell(4).setCellValue("1100000000");
			r4.createCell(6).setCellValue("20260201");

			try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
				workbook.write(out);
				return out.toByteArray();
			}
		}
	}
}
