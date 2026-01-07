package com.mysite.sbb.legaldong;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
/**
 * 법정동 관리자 수정(검색 목록에서 인라인 편집).
 *
 * 제공 기능
 * - 과거법정동코드(past_legal_dong_cd) 수정/삭제
 * - 말소일자(dlt_dt) 수정/삭제 (yyyyMMdd 8자리 문자열)
 *
 * 처리 규칙
 * - 말소일자가 null이면 use_yn='Y', 말소일자가 존재하면 use_yn=NULL 로 자동 정리한다.
 * - 수정 시 last_updt_dtm/last_upusr_id 를 갱신한다.
 */
public class LegalDongAdminUpdateService {

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public void updateAdminFields(String legalDongCd, String pastLegalDongCd, String dltDt, String operatorId) {
		if (StringUtils.hasText(legalDongCd) == false) {
			throw new IllegalArgumentException("법정동코드(legalDongCd)가 비어 있습니다.");
		}

		String normalizedLegalDongCd = legalDongCd.trim();
		String normalizedPastCd = normalizeOptionalCode10(pastLegalDongCd);
		String normalizedDltDt = normalizeOptionalYyyyMmDd(dltDt);
		String normalizedOperatorId = StringUtils.hasText(operatorId) ? operatorId.trim() : "SYSTEM";

		String sql = """
				UPDATE tb_legal_dong_l
				SET
					past_legal_dong_cd = :pastLegalDongCd,
					dlt_dt = :dltDt,
					use_yn = CASE WHEN :dltDt IS NULL THEN 'Y' ELSE NULL END,
					last_updt_dtm = :now,
					last_upusr_id = :operatorId
				WHERE legal_dong_cd = :legalDongCd
				""";

		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("pastLegalDongCd", normalizedPastCd)
				.addValue("dltDt", normalizedDltDt)
				.addValue("now", Timestamp.valueOf(LocalDateTime.now()))
				.addValue("operatorId", normalizedOperatorId)
				.addValue("legalDongCd", normalizedLegalDongCd);

		int updated = jdbcTemplate.update(sql, params);
		if (updated != 1) {
			throw new IllegalStateException("업데이트 대상이 존재하지 않습니다: " + normalizedLegalDongCd);
		}
	}

	private String normalizeOptionalCode10(String raw) {
		if (StringUtils.hasText(raw) == false) {
			return null;
		}
		String digits = raw.trim().replaceAll("[^0-9]", "");
		if (digits.isBlank()) {
			return null;
		}
		if (digits.length() != 10) {
			throw new IllegalArgumentException("과거법정동코드는 10자리여야 합니다: " + raw);
		}
		return digits;
	}

	private String normalizeOptionalYyyyMmDd(String raw) {
		if (StringUtils.hasText(raw) == false) {
			return null;
		}
		String digits = raw.trim().replaceAll("[^0-9]", "");
		if (digits.isBlank()) {
			return null;
		}
		if (digits.length() != 8) {
			throw new IllegalArgumentException("말소일자는 yyyyMMdd(8자리)여야 합니다: " + raw);
		}
		try {
			LocalDate.parse(digits, YYYYMMDD);
		} catch (DateTimeParseException ex) {
			throw new IllegalArgumentException("말소일자 형식이 올바르지 않습니다(yyyyMMdd): " + raw);
		}
		return digits;
	}
}

