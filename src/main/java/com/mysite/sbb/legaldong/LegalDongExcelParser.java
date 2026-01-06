package com.mysite.sbb.legaldong;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import lombok.Value;

@Component
/**
 * 법정동 엑셀(xlsx) 파일을 읽어 원본 행(List<LegalDongSourceRow>)으로 변환한다.
 *
 * 담당 역할
 * - 첫 시트에서 헤더를 찾아 컬럼 위치를 결정한다(컬럼 순서 변경 허용).
 * - 셀 값을 DataFormatter로 읽어 숫자/문자/서식 차이에도 안전하게 문자열로 취득한다.
 * - 법정동코드는 숫자만 남긴 뒤 10자리로 0패딩해 정규화한다.
 * - 날짜(생성일자/말소일자)는 yyyyMMdd로만 파싱한다(없으면 null).
 *
 * - 헤더 이름을 기반으로 컬럼 위치를 매핑한다(순서가 바뀌어도 동작).
 * - 숫자 서식/과학표기법으로 들어간 코드 값은 DataFormatter를 통해 문자열로 받아 처리한다.
 * - 날짜는 yyyyMMdd(숫자/문자)만 허용하며, 파싱 실패 시 오류로 수집한다.
 */
public class LegalDongExcelParser {

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.KOREA);

	private final DataFormatter dataFormatter = new DataFormatter(Locale.KOREA);

	@Value
	public static class ParseResult {
		List<LegalDongSourceRow> rows;
		List<String> errors;
	}

	public ParseResult parse(InputStream inputStream) throws IOException {
		try (Workbook workbook = WorkbookFactory.create(inputStream)) {
			Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
			if (sheet == null) {
				return new ParseResult(List.of(), List.of("엑셀 시트를 찾을 수 없습니다."));
			}

			HeaderMapping headerMapping = resolveHeaderMapping(sheet);
			if (headerMapping == null) {
				return new ParseResult(List.of(), List.of("헤더 행을 찾을 수 없습니다. (예: '행정동코드', '시도명', '법정동코드' 등)"));
			}

			List<String> errors = new ArrayList<>();
			List<LegalDongSourceRow> rows = new ArrayList<>();

			for (int rowIndex = headerMapping.headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
				Row row = sheet.getRow(rowIndex);
				if (row == null) {
					continue;
				}
				int rowNumber = rowIndex + 1; // 엑셀 화면은 1 기반

				String legalDongCdRaw = getCellString(row, headerMapping.legalDongCdCol).orElse("");
				if (legalDongCdRaw.isBlank()) {
					continue;
				}

				String normalizedLegalDongCd = normalizeCode(legalDongCdRaw);
				if (normalizedLegalDongCd == null) {
					errors.add("[" + rowNumber + "행] 법정동코드 값이 유효하지 않습니다: " + legalDongCdRaw);
					continue;
				}

				Optional<LocalDate> crDt = parseDate(getCellString(row, headerMapping.crDtCol).orElse(""));
				if (headerMapping.crDtCol != null && getCellString(row, headerMapping.crDtCol).orElse("").isBlank() == false && crDt.isEmpty()) {
					errors.add("[" + rowNumber + "행] 생성일자(yyyyMMdd) 파싱 실패: " + getCellString(row, headerMapping.crDtCol).orElse(""));
				}

				Optional<LocalDate> dltDt = parseDate(getCellString(row, headerMapping.dltDtCol).orElse(""));
				if (headerMapping.dltDtCol != null && getCellString(row, headerMapping.dltDtCol).orElse("").isBlank() == false && dltDt.isEmpty()) {
					errors.add("[" + rowNumber + "행] 말소일자(yyyyMMdd) 파싱 실패: " + getCellString(row, headerMapping.dltDtCol).orElse(""));
				}

				rows.add(LegalDongSourceRow.builder()
						.rowNumber(rowNumber)
						.adminDongCd(getCellString(row, headerMapping.adminDongCdCol).orElse(null))
						.ctprvNm(trimToNull(getCellString(row, headerMapping.ctprvNmCol).orElse(null)))
						.sgngNm(trimToNull(getCellString(row, headerMapping.sgngNmCol).orElse(null)))
						.emndnNm(trimToNull(getCellString(row, headerMapping.emndnNmCol).orElse(null)))
						.legalDongCd(normalizedLegalDongCd)
						.liNm(trimToNull(getCellString(row, headerMapping.liNmCol).orElse(null)))
						.crDt(crDt.orElse(null))
						.dltDt(dltDt.orElse(null))
						.build());
			}

			return new ParseResult(rows, errors);
		} catch (Exception ex) {
			// POI는 포맷 오류에서 다양한 Runtime 예외를 던질 수 있어, 관리자 화면에서 진단 가능한 메시지로 감싼다.
			return new ParseResult(List.of(), List.of("엑셀 파싱 중 오류가 발생했습니다: " + ex.getMessage()));
		}
	}

	private HeaderMapping resolveHeaderMapping(Sheet sheet) {
		for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 20); rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if (row == null) {
				continue;
			}
			Map<String, Integer> headerIndex = new HashMap<>();
			for (int col = row.getFirstCellNum(); col >= 0 && col < row.getLastCellNum(); col++) {
				String header = getCellString(row, col).orElse("").trim();
				if (header.isBlank()) {
					continue;
				}
				headerIndex.put(header, col);
			}

			Integer legalDongCdCol = firstPresent(headerIndex, "법정동코드");
			Integer ctprvNmCol = firstPresent(headerIndex, "시도명");
			if (legalDongCdCol == null || ctprvNmCol == null) {
				continue;
			}

			return new HeaderMapping(
					rowIndex,
					firstPresent(headerIndex, "행정동코드"),
					ctprvNmCol,
					firstPresent(headerIndex, "시군구명"),
					firstPresent(headerIndex, "읍면동명"),
					legalDongCdCol,
					firstPresent(headerIndex, "동리명"),
					firstPresent(headerIndex, "생성일자"),
					firstPresent(headerIndex, "말소일자"));
		}
		return null;
	}

	private Integer firstPresent(Map<String, Integer> headerIndex, String... keys) {
		for (String key : keys) {
			Integer col = headerIndex.get(key);
			if (col != null) {
				return col;
			}
		}
		return null;
	}

	private Optional<String> getCellString(Row row, Integer colIndex) {
		if (colIndex == null || colIndex < 0) {
			return Optional.empty();
		}
		return Optional.ofNullable(row.getCell(colIndex))
				.map(cell -> dataFormatter.formatCellValue(cell))
				.map(String::trim);
	}

	private Optional<LocalDate> parseDate(String raw) {
		String normalized = raw == null ? "" : raw.trim();
		if (normalized.isBlank()) {
			return Optional.empty();
		}
		normalized = normalized.replaceAll("[^0-9]", "");
		if (normalized.length() != 8) {
			return Optional.empty();
		}
		try {
			return Optional.of(LocalDate.parse(normalized, YYYYMMDD));
		} catch (DateTimeParseException ex) {
			return Optional.empty();
		}
	}

	/**
	 * 법정동 코드를 "10자리 숫자 문자열"로 정규화한다.
	 * - 숫자/문자 혼합, 공백, 하이픈 등이 섞여도 숫자만 추출한다.
	 * - 길이가 10 미만이면 왼쪽을 0으로 패딩해 10자로 맞춘다(앞 0 유실 복구).
	 */
	private String normalizeCode(String raw) {
		String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
		if (digits.isBlank()) {
			return null;
		}
		if (digits.length() > 10) {
			return null;
		}
		return String.format("%10s", digits).replace(' ', '0');
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isBlank() ? null : trimmed;
	}

	private record HeaderMapping(
			int headerRowIndex,
			Integer adminDongCdCol,
			Integer ctprvNmCol,
			Integer sgngNmCol,
			Integer emndnNmCol,
			Integer legalDongCdCol,
			Integer liNmCol,
			Integer crDtCol,
			Integer dltDtCol) {
	}
}
