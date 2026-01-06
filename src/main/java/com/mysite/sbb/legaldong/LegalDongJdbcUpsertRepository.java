package com.mysite.sbb.legaldong;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
/**
 * PostgreSQL 대상으로 법정동 데이터를 대량 업서트한다.
 *
 * 담당 역할
 * - 대량 파일(전국 단위)을 처리할 수 있도록 JDBC batch + `ON CONFLICT` 업서트를 사용한다.
 * - 충돌 키는 `legal_dong_cd`(PK)이며, 재업로드 시 최신 값으로 갱신한다.
 *
 * - 대상 파일이 전국 단위일 수 있어 JPA saveAll보다 JDBC batch가 유리하다.
 * - past_legal_dong_cd 및 최초 작성자/작성일은 "최초 입력값 유지"가 원칙이므로 UPDATE에서 제외한다.
 */
public class LegalDongJdbcUpsertRepository {

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public int upsertAll(List<LegalDongDerivedRow> rows, String operatorId, LocalDateTime now) {
		if (rows == null || rows.isEmpty()) {
			return 0;
		}

		String sql = """
				INSERT INTO tb_legal_dong_l (
					legal_dong_cd, legal_dong_nm,
					ctprv_cd, ctprv_nm,
					sgng_cd, sgng_nm,
					emndn_cd, emndn_nm,
					li_cd, li_nm,
					rank, cr_dt, dlt_dt,
					past_legal_dong_cd,
					frst_wrtng_dtm, frst_writr_id,
					last_updt_dtm, last_upusr_id,
					use_yn
				) VALUES (
					:legalDongCd, :legalDongNm,
					:ctprvCd, :ctprvNm,
					:sgngCd, :sgngNm,
					:emndnCd, :emndnNm,
					:liCd, :liNm,
					:rank, :crDt, :dltDt,
					NULL,
					:frstWrtngDtm, :frstWritrId,
					:lastUpdtDtm, :lastUpusrId,
					-- NOTE: 생성/말소일자는 yyyyMMdd 문자열(varchar)로 적재한다.
					-- 말소일자가 없으면 활성 데이터로 간주해 use_yn='Y'를 기본값으로 둔다.
					-- PostgreSQL은 `? IS NULL` 형태로만 쓰이는 바인딩 값은 타입을 추론하지 못해
					-- "오류: $N 매개 변수의 자료형을 알 수 없음"이 발생할 수 있다.
					-- 따라서 SQL 레벨에서 text/varchar로 캐스팅해 타입 힌트를 제공한다.
					CASE WHEN CAST(:dltDt AS varchar) IS NULL THEN 'Y' ELSE NULL END
				)
				ON CONFLICT (legal_dong_cd) DO UPDATE SET
					legal_dong_nm = EXCLUDED.legal_dong_nm,
					ctprv_cd = EXCLUDED.ctprv_cd,
					ctprv_nm = EXCLUDED.ctprv_nm,
					sgng_cd = EXCLUDED.sgng_cd,
					sgng_nm = EXCLUDED.sgng_nm,
					emndn_cd = EXCLUDED.emndn_cd,
					emndn_nm = EXCLUDED.emndn_nm,
					li_cd = EXCLUDED.li_cd,
					li_nm = EXCLUDED.li_nm,
					rank = EXCLUDED.rank,
					cr_dt = EXCLUDED.cr_dt,
					-- 말소일자(dlt_dt)는 기존 값이 null이고, 신규 업로드(엑셀) 행에 말소일자가 있을 때만 갱신한다.
					dlt_dt = CASE
						WHEN tb_legal_dong_l.dlt_dt IS NULL AND EXCLUDED.dlt_dt IS NOT NULL THEN EXCLUDED.dlt_dt
						ELSE tb_legal_dong_l.dlt_dt
					END,
					use_yn = CASE
						-- 기존 데이터에 말소일자를 채우는 순간(기존 use_yn='Y' 포함) null로 전환한다.
						WHEN tb_legal_dong_l.dlt_dt IS NULL AND EXCLUDED.dlt_dt IS NOT NULL THEN NULL
						-- 말소일자가 없는 활성 데이터는 Y로 유지한다.
						WHEN tb_legal_dong_l.dlt_dt IS NULL AND EXCLUDED.dlt_dt IS NULL THEN 'Y'
						ELSE tb_legal_dong_l.use_yn
					END,
					last_updt_dtm = EXCLUDED.last_updt_dtm,
					last_upusr_id = EXCLUDED.last_upusr_id
				""";

		MapSqlParameterSource[] batchParams = rows.stream()
				.map(row -> toParams(row, operatorId, now))
				.toArray(MapSqlParameterSource[]::new);

		int[] results = jdbcTemplate.batchUpdate(sql, batchParams);
		int sum = 0;
		for (int r : results) {
			// PostgreSQL batchUpdate는 영향 행 수를 -2(성공/미상)로 반환할 수 있어, 성공 개수로 카운트한다.
			if (r == 1 || r == 2) {
				sum += 1;
				continue;
			}
			if (r == -2) {
				sum += 1;
			}
		}
		return sum;
	}

	private MapSqlParameterSource toParams(LegalDongDerivedRow row, String operatorId, LocalDateTime now) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("legalDongCd", row.getLegalDongCd());
		params.addValue("legalDongNm", row.getLegalDongNm());
		params.addValue("ctprvCd", row.getCtprvCd());
		params.addValue("ctprvNm", row.getCtprvNm());
		params.addValue("sgngCd", row.getSgngCd());
		params.addValue("sgngNm", row.getSgngNm());
		params.addValue("emndnCd", row.getEmndnCd());
		params.addValue("emndnNm", row.getEmndnNm());
		params.addValue("liCd", row.getLiCd());
		params.addValue("liNm", row.getLiNm());
		params.addValue("rank", row.getRank());
		// 생성/말소일자는 yyyyMMdd 문자열로 적재한다.
		// - 엑셀 입력이 비어 있으면 null로 적재한다.
		//
		// PostgreSQL은 null 바인딩을 "unknown" 타입으로 처리할 수 있어,
		// `:dltDt IS NULL` 같은 구문에서 "매개 변수의 자료형을 알 수 없음" 오류가 발생할 수 있다.
		// 따라서 문자열 파라미터는 null이어도 VARCHAR 타입을 명시해 바인딩한다.
		params.addValue("crDt", toYyyyMmDd(row.getCrDt()), Types.VARCHAR);
		params.addValue("dltDt", toYyyyMmDd(row.getDltDt()), Types.VARCHAR);
		params.addValue("frstWrtngDtm", Timestamp.valueOf(now));
		params.addValue("frstWritrId", operatorId);
		params.addValue("lastUpdtDtm", Timestamp.valueOf(now));
		params.addValue("lastUpusrId", operatorId);
		return params;
	}

	private String toYyyyMmDd(LocalDate value) {
		return value == null ? null : YYYYMMDD.format(value);
	}
}
