package com.mysite.sbb.legaldong;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
/**
 * 법정동코드 생성/말소 기간 이력(tb_legal_dong_l_hist)을 누적 적재한다.
 *
 * 설계 의도
 * - `tb_legal_dong_l`은 1행=1법정동코드(스냅샷)로 유지한다.
 * - 생성/말소일자 조합(기간 이력)은 누적돼야 하므로 별도 테이블에 INSERT 한다.
 *
 * 데이터 전제(사용자 확인)
 * - 정정(동일 key의 수정) 케이스는 없다고 가정한다.
 * - 따라서 이력 적재는 "중복이면 무시"가 기본이며, UPDATE는 수행하지 않는다.
 */
public class LegalDongHistoryJdbcInsertRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public int insertAll(List<LegalDongDerivedRow> rows, String operatorId) {
		if (rows == null || rows.isEmpty()) {
			return 0;
		}

		String sql = """
				INSERT INTO tb_legal_dong_l_hist (
					legal_dong_cd,
					cr_dt,
					dlt_dt,
					frst_wrtng_dtm,
					frst_writr_id
				) VALUES (
					:legalDongCd,
					:crDt,
					:dltDt,
					:now,
					:operatorId
				)
				ON CONFLICT (legal_dong_cd, cr_dt, dlt_dt) DO NOTHING
				""";

		Timestamp now = Timestamp.valueOf(java.time.LocalDateTime.now());
		MapSqlParameterSource[] batchParams = rows.stream()
				.map(r -> new MapSqlParameterSource()
						.addValue("legalDongCd", r.getLegalDongCd())
						.addValue("crDt", r.getCrDt() == null ? null : java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(r.getCrDt()))
						.addValue("dltDt", r.getDltDt() == null ? null : java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(r.getDltDt()))
						.addValue("now", now)
						.addValue("operatorId", operatorId))
				.toArray(MapSqlParameterSource[]::new);

		int[] results = jdbcTemplate.batchUpdate(sql, batchParams);
		int sum = 0;
		for (int r : results) {
			if (r == 1 || r == 2 || r == -2) {
				sum += 1;
			}
		}
		return sum;
	}
}

