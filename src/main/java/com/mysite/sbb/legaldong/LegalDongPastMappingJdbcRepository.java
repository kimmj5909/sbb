package com.mysite.sbb.legaldong;

import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
/**
 * 과거법정동코드(past_legal_dong_cd) 자동 매핑(미리보기/적용)용 JDBC 레포지토리.
 *
 * 매핑 규칙(요구사항 정리)
 * - "반드시 말소된 코드"만 과거 코드로 사용:
 *   - old.dlt_dt = eff_dt 인 데이터만 후보로 사용한다.
 * - 신규 생성 코드만 갱신:
 *   - new.cr_dt = eff_dt 이고 new.past_legal_dong_cd IS NULL 인 데이터만 갱신한다.
 * - 명칭 완전일치에 의존하지 않도록, 다음 조건으로 방어적으로 매칭한다.
 *   - ctprv_cd 동일
 *   - base_city 동일: sgng_nm에서 첫 토큰(공백 전)만 비교(예: '화성시', '용인시')
 *   - emndn_root 동일: emndn_nm에서 마지막 접미사(읍/면/동/리/가) 제거 후 비교
 *
 * 주의
 * - 후보가 2개 이상인 "애매 케이스"는 자동 적용하지 않는다(=업데이트 제외).
 * - li(리/동 하위) 매핑은 emndn(읍면동) 단위 매핑이 유니크(후보 1개)인 경우에만 적용한다.
 *   - past_legal_dong_cd = (old_emndn_cd || li_tail2)
 */
public class LegalDongPastMappingJdbcRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public LegalDongPastMappingApplyResult applyForEffectiveDate(String effDt, String operatorId) {
		Map<String, Object> params = Map.of(
				"effDt", effDt,
				"operatorId", operatorId == null ? "SYSTEM" : operatorId);

		Stats stats = loadStats(params);

		int emndnUpdated = updateEmndn(params);
		int liUpdated = updateLi(params);

		return new LegalDongPastMappingApplyResult(
				effDt,
				stats.newEmndnCnt,
				emndnUpdated,
				stats.emndnAmbiguousCnt,
				stats.emndnMissingCnt,
				stats.newLiCnt,
				liUpdated,
				stats.liMissingOldCnt);
	}

	private Stats loadStats(Map<String, Object> params) {
		String sql = """
				WITH
				old_emndn AS (
					SELECT
						o.legal_dong_cd AS old_emndn_cd10,
						o.emndn_cd AS old_emndn_cd8,
						o.ctprv_cd,
						regexp_replace(coalesce(o.sgng_nm, ''), '\\\\s.*$', '') AS old_base_city,
						regexp_replace(coalesce(o.emndn_nm, ''), '(읍|면|동|리|가)$', '') AS old_emndn_root
					FROM tb_legal_dong_l o
					WHERE o.li_cd IS NULL
					  AND o.emndn_cd IS NOT NULL
					  AND o.dlt_dt = :effDt
				),
				new_emndn AS (
					SELECT
						n.legal_dong_cd AS new_emndn_cd10,
						n.emndn_cd AS new_emndn_cd8,
						n.ctprv_cd,
						regexp_replace(coalesce(n.sgng_nm, ''), '\\\\s.*$', '') AS new_base_city,
						regexp_replace(coalesce(n.emndn_nm, ''), '(읍|면|동|리|가)$', '') AS new_emndn_root
					FROM tb_legal_dong_l n
					WHERE n.li_cd IS NULL
					  AND n.emndn_cd IS NOT NULL
					  AND n.cr_dt = :effDt
					  AND n.past_legal_dong_cd IS NULL
				),
				emndn_candidates AS (
					SELECT
						n.new_emndn_cd10,
						n.new_emndn_cd8,
						o.old_emndn_cd10,
						o.old_emndn_cd8,
						CASE
							WHEN n.new_base_city <> '' AND o.old_base_city <> '' AND n.new_base_city = o.old_base_city THEN 10
							ELSE 0
						END AS score
					FROM new_emndn n
					JOIN old_emndn o
					  ON o.ctprv_cd = n.ctprv_cd
					 AND o.old_base_city = n.new_base_city
					 AND o.old_emndn_root = n.new_emndn_root
				),
				emndn_agg AS (
					SELECT
						n.new_emndn_cd10,
						count(c.old_emndn_cd10) AS candidate_cnt
					FROM new_emndn n
					LEFT JOIN emndn_candidates c ON c.new_emndn_cd10 = n.new_emndn_cd10
					GROUP BY n.new_emndn_cd10
				),
				new_li AS (
					SELECT
						n.legal_dong_cd AS new_li_cd,
						substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
						right(n.legal_dong_cd, 2) AS li_tail2
					FROM tb_legal_dong_l n
					WHERE n.li_cd IS NOT NULL
					  AND n.cr_dt = :effDt
					  AND n.past_legal_dong_cd IS NULL
				),
				emndn_unique_map AS (
					SELECT
						n.new_emndn_cd8,
						(array_agg(c.old_emndn_cd10 ORDER BY c.score DESC, c.old_emndn_cd10))[1] AS chosen_old_emndn_cd10,
						(array_agg(c.old_emndn_cd8 ORDER BY c.score DESC, c.old_emndn_cd10))[1] AS chosen_old_emndn_cd8
					FROM new_emndn n
					JOIN emndn_candidates c ON c.new_emndn_cd10 = n.new_emndn_cd10
					GROUP BY n.new_emndn_cd8
					HAVING count(c.old_emndn_cd10) = 1
				),
				old_li AS (
					SELECT
						o.legal_dong_cd AS old_li_cd,
						substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd,
						right(o.legal_dong_cd, 2) AS li_tail2
					FROM tb_legal_dong_l o
					WHERE o.li_cd IS NOT NULL
					  AND o.dlt_dt = :effDt
				),
				li_expected AS (
					SELECT
						n.new_li_cd,
						(m.chosen_old_emndn_cd8 || n.li_tail2) AS expected_old_li_cd
					FROM new_li n
					JOIN emndn_unique_map m ON m.new_emndn_cd8 = n.new_emndn_cd8
				),
				li_missing_old AS (
					SELECT
						count(*) AS missing_cnt
					FROM li_expected e
					LEFT JOIN old_li o
					  ON o.old_emndn_cd = substring(e.expected_old_li_cd, 1, 8)
					 AND o.li_tail2 = right(e.expected_old_li_cd, 2)
					WHERE o.old_li_cd IS NULL
				)
				SELECT
					(SELECT count(*) FROM new_emndn) AS new_emndn_cnt,
					(SELECT count(*) FROM emndn_agg WHERE candidate_cnt = 0) AS emndn_missing_cnt,
					(SELECT count(*) FROM emndn_agg WHERE candidate_cnt > 1) AS emndn_ambiguous_cnt,
					(SELECT count(*) FROM new_li) AS new_li_cnt,
					(SELECT missing_cnt FROM li_missing_old) AS li_missing_old_cnt
				""";

		return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new Stats(
				rs.getInt("new_emndn_cnt"),
				rs.getInt("emndn_ambiguous_cnt"),
				rs.getInt("emndn_missing_cnt"),
				rs.getInt("new_li_cnt"),
				rs.getInt("li_missing_old_cnt")));
	}

	private int updateEmndn(Map<String, Object> params) {
		String sql = """
				WITH
				old_emndn AS (
					SELECT
						o.legal_dong_cd AS old_emndn_cd10,
						o.ctprv_cd,
						regexp_replace(coalesce(o.sgng_nm, ''), '\\\\s.*$', '') AS old_base_city,
						regexp_replace(coalesce(o.emndn_nm, ''), '(읍|면|동|리|가)$', '') AS old_emndn_root
					FROM tb_legal_dong_l o
					WHERE o.li_cd IS NULL
					  AND o.emndn_cd IS NOT NULL
					  AND o.dlt_dt = :effDt
				),
				new_emndn AS (
					SELECT
						n.legal_dong_cd AS new_emndn_cd10,
						n.ctprv_cd,
						regexp_replace(coalesce(n.sgng_nm, ''), '\\\\s.*$', '') AS new_base_city,
						regexp_replace(coalesce(n.emndn_nm, ''), '(읍|면|동|리|가)$', '') AS new_emndn_root
					FROM tb_legal_dong_l n
					WHERE n.li_cd IS NULL
					  AND n.emndn_cd IS NOT NULL
					  AND n.cr_dt = :effDt
					  AND n.past_legal_dong_cd IS NULL
				),
				emndn_candidates AS (
					SELECT
						n.new_emndn_cd10,
						o.old_emndn_cd10,
						CASE
							WHEN n.new_base_city <> '' AND o.old_base_city <> '' AND n.new_base_city = o.old_base_city THEN 10
							ELSE 0
						END AS score
					FROM new_emndn n
					JOIN old_emndn o
					  ON o.ctprv_cd = n.ctprv_cd
					 AND o.old_base_city = n.new_base_city
					 AND o.old_emndn_root = n.new_emndn_root
				),
				emndn_preview AS (
					SELECT
						n.new_emndn_cd10,
						count(c.old_emndn_cd10) AS candidate_cnt,
						(array_agg(c.old_emndn_cd10 ORDER BY c.score DESC, c.old_emndn_cd10))[1] AS chosen_old_emndn_cd10
					FROM new_emndn n
					LEFT JOIN emndn_candidates c ON c.new_emndn_cd10 = n.new_emndn_cd10
					GROUP BY n.new_emndn_cd10
				),
				apply_targets AS (
					SELECT
						e.new_emndn_cd10 AS new_legal_dong_cd,
						e.chosen_old_emndn_cd10 AS past_legal_dong_cd
					FROM emndn_preview e
					WHERE e.candidate_cnt = 1
				)
				UPDATE tb_legal_dong_l t
				SET
					past_legal_dong_cd = a.past_legal_dong_cd,
					last_updt_dtm = now(),
					last_upusr_id = :operatorId
				FROM apply_targets a
				WHERE t.legal_dong_cd = a.new_legal_dong_cd
				  AND t.cr_dt = :effDt
				  AND t.past_legal_dong_cd IS NULL
				""";

		return jdbcTemplate.update(sql, params);
	}

	private int updateLi(Map<String, Object> params) {
		String sql = """
				WITH
				old_emndn AS (
					SELECT
						o.emndn_cd AS old_emndn_cd8,
						o.ctprv_cd,
						regexp_replace(coalesce(o.sgng_nm, ''), '\\\\s.*$', '') AS old_base_city,
						regexp_replace(coalesce(o.emndn_nm, ''), '(읍|면|동|리|가)$', '') AS old_emndn_root
					FROM tb_legal_dong_l o
					WHERE o.li_cd IS NULL
					  AND o.emndn_cd IS NOT NULL
					  AND o.dlt_dt = :effDt
				),
				new_emndn AS (
					SELECT
						n.emndn_cd AS new_emndn_cd8,
						n.ctprv_cd,
						regexp_replace(coalesce(n.sgng_nm, ''), '\\\\s.*$', '') AS new_base_city,
						regexp_replace(coalesce(n.emndn_nm, ''), '(읍|면|동|리|가)$', '') AS new_emndn_root
					FROM tb_legal_dong_l n
					WHERE n.li_cd IS NULL
					  AND n.emndn_cd IS NOT NULL
					  AND n.cr_dt = :effDt
				),
				emndn_candidates AS (
					SELECT
						n.new_emndn_cd8,
						o.old_emndn_cd8,
						CASE
							WHEN n.new_base_city <> '' AND o.old_base_city <> '' AND n.new_base_city = o.old_base_city THEN 10
							ELSE 0
						END AS score
					FROM new_emndn n
					JOIN old_emndn o
					  ON o.ctprv_cd = n.ctprv_cd
					 AND o.old_base_city = n.new_base_city
					 AND o.old_emndn_root = n.new_emndn_root
				),
				emndn_unique_map AS (
					SELECT
						n.new_emndn_cd8,
						(array_agg(c.old_emndn_cd8 ORDER BY c.score DESC, c.old_emndn_cd8))[1] AS chosen_old_emndn_cd8
					FROM new_emndn n
					JOIN emndn_candidates c ON c.new_emndn_cd8 = n.new_emndn_cd8
					GROUP BY n.new_emndn_cd8
					HAVING count(c.old_emndn_cd8) = 1
				),
				new_li AS (
					SELECT
						n.legal_dong_cd AS new_li_cd,
						substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
						right(n.legal_dong_cd, 2) AS li_tail2
					FROM tb_legal_dong_l n
					WHERE n.li_cd IS NOT NULL
					  AND n.cr_dt = :effDt
					  AND n.past_legal_dong_cd IS NULL
				),
				old_li AS (
					SELECT
						o.legal_dong_cd AS old_li_cd,
						substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd,
						right(o.legal_dong_cd, 2) AS li_tail2
					FROM tb_legal_dong_l o
					WHERE o.li_cd IS NOT NULL
					  AND o.dlt_dt = :effDt
				),
				apply_targets AS (
					SELECT
						n.new_li_cd AS new_legal_dong_cd,
						ol.old_li_cd AS past_legal_dong_cd
					FROM new_li n
					JOIN emndn_unique_map m ON m.new_emndn_cd8 = n.new_emndn_cd8
					JOIN old_li ol
					  ON ol.old_emndn_cd = m.chosen_old_emndn_cd8
					 AND ol.li_tail2 = n.li_tail2
				)
				UPDATE tb_legal_dong_l t
				SET
					past_legal_dong_cd = a.past_legal_dong_cd,
					last_updt_dtm = now(),
					last_upusr_id = :operatorId
				FROM apply_targets a
				WHERE t.legal_dong_cd = a.new_legal_dong_cd
				  AND t.cr_dt = :effDt
				  AND t.past_legal_dong_cd IS NULL
				""";

		return jdbcTemplate.update(sql, params);
	}

	private record Stats(int newEmndnCnt, int emndnAmbiguousCnt, int emndnMissingCnt, int newLiCnt, int liMissingOldCnt) {
	}
}
