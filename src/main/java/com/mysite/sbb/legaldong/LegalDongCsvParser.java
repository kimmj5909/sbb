package com.mysite.sbb.legaldong;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.Value;

@Component
/**
 * 법정동 CSV(8컬럼) 파일을 읽어 원본 행(List<LegalDongSourceRow>)으로 변환한다.
 *
 * 배경
 * - 관리자 화면(법정동 마이그레이션)은 기본 입력이 xlsx이지만,
 *   운영에서는 KIKmix/델타 데이터를 CSV로 가공해 검증/적재하는 워크플로우가 많다.
 * - KIKmix.XXXXXX 엑셀을 인천만 필터링한 CSV 등을, 앱에서 바로 업로드/미리보기/DB적용까지 확인할 수 있도록
 *   CSV 파서를 제공한다.
 *
 * 입력 전제(헤더)
 * - 첫 줄에 헤더가 있어야 하며, 아래 키 이름을 기반으로 컬럼 위치를 매핑한다(순서 변경 허용).
 *   - 행정동코드, 시도명, 시군구명, 읍면동명, 법정동코드, 동리명, 생성일자, 말소일자
 *
 * 처리
 * - 법정동코드는 숫자만 남긴 뒤 10자리로 0패딩해 정규화한다.
 * - 날짜(생성일자/말소일자)는 yyyyMMdd로만 파싱한다(없으면 null).
 * - UTF-8 BOM이 있으면 헤더 첫 컬럼에서 제거한다.
 */
public class LegalDongCsvParser {

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.KOREA);

	@Value
	public static class ParseResult {
		List<LegalDongSourceRow> rows;
		List<String> errors;
	}

	public ParseResult parse(byte[] csvBytes) {
		if (csvBytes == null || csvBytes.length == 0) {
			return new ParseResult(List.of(), List.of("CSV 데이터가 비어 있습니다."));
		}

		List<String> errors = new ArrayList<>();
		List<LegalDongSourceRow> rows = new ArrayList<>();

		try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8))) {
			String headerLine = null;
			int headerLineNumber = 0;

			String line;
			int lineNumber = 0;
			while ((line = br.readLine()) != null) {
				lineNumber++;
				if (line == null) {
					continue;
				}
				String trimmed = line.trim();
				if (trimmed.isBlank()) {
					continue;
				}
				// psql/미리보기 CSV에서 #WARN 주석 라인이 올 수 있어, 선행 # 라인은 스킵한다.
				if (trimmed.startsWith("#")) {
					continue;
				}
				headerLine = line;
				headerLineNumber = lineNumber;
				break;
			}

			if (headerLine == null) {
				return new ParseResult(List.of(), List.of("헤더 행을 찾을 수 없습니다. (예: '행정동코드', '시도명', '법정동코드' 등)"));
			}

			List<String> headerFields = parseCsvLine(headerLine);
			if (headerFields.isEmpty()) {
				return new ParseResult(List.of(), List.of("헤더 행을 파싱할 수 없습니다."));
			}

			// UTF-8 BOM 제거(첫 컬럼)
			if (headerFields.get(0) != null) {
				headerFields.set(0, headerFields.get(0).replace("\uFEFF", ""));
			}

			HeaderMapping headerMapping = resolveHeaderMapping(headerFields);
			if (headerMapping == null) {
				return new ParseResult(List.of(), List.of("헤더 행에서 필수 컬럼을 찾을 수 없습니다. (예: '시도명', '법정동코드')"));
			}

			// 본문 파싱
			while ((line = br.readLine()) != null) {
				lineNumber++;
				if (line == null) {
					continue;
				}
				String trimmed = line.trim();
				if (trimmed.isBlank() || trimmed.startsWith("#")) {
					continue;
				}

				List<String> fields = parseCsvLine(line);
				// 엑셀 화면은 1 기반이므로, CSV도 "파일 라인 번호"를 그대로 노출한다.
				int rowNumber = lineNumber;

				String legalDongCdRaw = getField(fields, headerMapping.legalDongCdCol).orElse("");
				if (legalDongCdRaw.isBlank()) {
					continue;
				}

				String normalizedLegalDongCd = normalizeCode(legalDongCdRaw);
				if (normalizedLegalDongCd == null) {
					errors.add("[" + rowNumber + "행] 법정동코드 값이 유효하지 않습니다: " + legalDongCdRaw);
					continue;
				}

				Optional<LocalDate> crDt = parseDate(getField(fields, headerMapping.crDtCol).orElse(""));
				String crRaw = getField(fields, headerMapping.crDtCol).orElse("");
				if (headerMapping.crDtCol != null && crRaw.isBlank() == false && crDt.isEmpty()) {
					errors.add("[" + rowNumber + "행] 생성일자(yyyyMMdd) 파싱 실패: " + crRaw);
				}

				Optional<LocalDate> dltDt = parseDate(getField(fields, headerMapping.dltDtCol).orElse(""));
				String dltRaw = getField(fields, headerMapping.dltDtCol).orElse("");
				if (headerMapping.dltDtCol != null && dltRaw.isBlank() == false && dltDt.isEmpty()) {
					errors.add("[" + rowNumber + "행] 말소일자(yyyyMMdd) 파싱 실패: " + dltRaw);
				}

				rows.add(LegalDongSourceRow.builder()
						.rowNumber(rowNumber)
						.adminDongCd(getField(fields, headerMapping.adminDongCdCol).map(this::trimToNull).orElse(null))
						.ctprvNm(getField(fields, headerMapping.ctprvNmCol).map(this::trimToNull).orElse(null))
						.sgngNm(getField(fields, headerMapping.sgngNmCol).map(this::trimToNull).orElse(null))
						.emndnNm(getField(fields, headerMapping.emndnNmCol).map(this::trimToNull).orElse(null))
						.legalDongCd(normalizedLegalDongCd)
						.liNm(getField(fields, headerMapping.liNmCol).map(this::trimToNull).orElse(null))
						.crDt(crDt.orElse(null))
						.dltDt(dltDt.orElse(null))
						.build());
			}

			if (rows.isEmpty() && errors.isEmpty()) {
				errors.add("CSV에서 적재할 행을 찾지 못했습니다. (법정동코드가 비어있지 않은지 확인)");
			}

			// 헤더 라인도 "원본 행"으로 카운트하지 않으므로, 원본 행 수는 rows.size()가 된다.
			return new ParseResult(rows, errors);
		} catch (Exception ex) {
			return new ParseResult(List.of(), List.of("CSV 파싱 중 오류가 발생했습니다: " + ex.getMessage()));
		}
	}

	private HeaderMapping resolveHeaderMapping(List<String> headers) {
		Map<String, Integer> headerIndex = new HashMap<>();
		for (int i = 0; i < headers.size(); i++) {
			String h = headers.get(i) == null ? "" : headers.get(i).trim();
			if (h.isBlank()) {
				continue;
			}
			headerIndex.put(h, i);
		}

		Integer legalDongCdCol = firstPresent(headerIndex, "법정동코드");
		Integer ctprvNmCol = firstPresent(headerIndex, "시도명");
		if (legalDongCdCol == null || ctprvNmCol == null) {
			return null;
		}

		return new HeaderMapping(
				firstPresent(headerIndex, "행정동코드"),
				ctprvNmCol,
				firstPresent(headerIndex, "시군구명"),
				firstPresent(headerIndex, "읍면동명"),
				legalDongCdCol,
				firstPresent(headerIndex, "동리명"),
				firstPresent(headerIndex, "생성일자"),
				firstPresent(headerIndex, "말소일자"));
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

	private Optional<String> getField(List<String> fields, Integer colIndex) {
		if (colIndex == null || colIndex < 0) {
			return Optional.empty();
		}
		if (colIndex >= fields.size()) {
			return Optional.empty();
		}
		return Optional.ofNullable(fields.get(colIndex)).map(String::trim);
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

	private List<String> parseCsvLine(String line) {
		// RFC4180 수준의 간단 파서
		// - 따옴표 안에서는 콤마/개행을 문자로 취급(본 파일은 readLine() 단위이므로 개행 포함 셀은 지원하지 않는다).
		// - "" 는 " 로 해석
		List<String> fields = new ArrayList<>();
		if (line == null) {
			return fields;
		}
		StringBuilder sb = new StringBuilder();
		boolean inQuotes = false;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (ch == '"') {
				if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					sb.append('"');
					i++;
					continue;
				}
				inQuotes = !inQuotes;
				continue;
			}
			if (ch == ',' && inQuotes == false) {
				fields.add(sb.toString());
				sb.setLength(0);
				continue;
			}
			sb.append(ch);
		}
		fields.add(sb.toString());
		return fields;
	}

	private record HeaderMapping(
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

