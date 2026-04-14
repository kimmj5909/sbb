package com.mysite.sbb.legaldong;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
/**
 * 마이그레이션 업서트 "변경 내역" 요약을 만들기 위한 스냅샷 조회용 레포지토리.
 *
 * 목적
 * - 업서트 실행 전, 대상 코드들의 기존 값을 조회해 "신규 INSERT / 말소일자 업데이트 / 기타 업데이트 / 변경없음"을 분류한다.
 * - DB 트리거/로그/별도 이력테이블 없이도, 화면에 최소한의 작업 이력을 출력할 수 있도록 지원한다.
 */
public class LegalDongJdbcSnapshotRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public Map<String, SnapshotRow> findByLegalDongCds(List<String> legalDongCds) {
		if (legalDongCds == null || legalDongCds.isEmpty()) {
			return Map.of();
		}

		String sql = """
				SELECT
					legal_dong_cd,
					legal_dong_nm,
					ctprv_cd,
					ctprv_nm,
					sgng_cd,
					sgng_nm,
					emndn_cd,
					emndn_nm,
					li_cd,
					li_nm,
					rank,
					cr_dt,
					dlt_dt,
					past_legal_dong_cd,
					use_yn
				FROM %s
				WHERE legal_dong_cd IN (:codes)
				""".formatted(LegalDongTables.LEGAL_DONG_TABLE);

		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("codes", legalDongCds);

		Map<String, SnapshotRow> result = new LinkedHashMap<>();
		jdbcTemplate.query(sql, params, rs -> {
			String code = rs.getString("legal_dong_cd");
			if (code == null) {
				return;
			}
			result.put(code, new SnapshotRow(
					code,
					rs.getString("legal_dong_nm"),
					rs.getString("ctprv_cd"),
					rs.getString("ctprv_nm"),
					rs.getString("sgng_cd"),
					rs.getString("sgng_nm"),
					rs.getString("emndn_cd"),
					rs.getString("emndn_nm"),
					rs.getString("li_cd"),
					rs.getString("li_nm"),
					(Integer) rs.getObject("rank"),
					rs.getString("cr_dt"),
					rs.getString("dlt_dt"),
					rs.getString("past_legal_dong_cd"),
					rs.getString("use_yn")));
		});
		return result;
	}

	public record SnapshotRow(
			String legalDongCd,
			String legalDongNm,
			String ctprvCd,
			String ctprvNm,
			String sgngCd,
			String sgngNm,
			String emndnCd,
			String emndnNm,
			String liCd,
			String liNm,
			Integer rank,
			String crDt,
			String dltDt,
			String pastLegalDongCd,
			String useYn) {
	}
}
