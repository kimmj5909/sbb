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
				stats.oldEmndnCnt,
				stats.newEmndnCnt,
				emndnUpdated,
				stats.emndnAmbiguousCnt,
				stats.emndnMissingCnt,
				stats.oldLiCnt,
				stats.newLiCnt,
				liUpdated,
				stats.liMissingOldCnt);
	}

	private Stats loadStats(Map<String, Object> params) {
		String sql = """
				WITH
				old_li_tails AS (
					SELECT
						substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
						array_agg(DISTINCT right(o.legal_dong_cd, 2) ORDER BY right(o.legal_dong_cd, 2)) AS old_tail2s
					FROM tb_legal_dong_l o
					WHERE o.li_cd IS NOT NULL
					  AND o.dlt_dt = :effDt
					GROUP BY substring(o.legal_dong_cd, 1, 8)
				),
				new_li_tails AS (
					SELECT
						substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
						array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
					FROM tb_legal_dong_l n
					WHERE n.li_cd IS NOT NULL
					  AND n.cr_dt = :effDt
					GROUP BY substring(n.legal_dong_cd, 1, 8)
				),
				old_emndn AS (
					SELECT
						o.legal_dong_cd AS old_emndn_cd10,
						o.emndn_cd AS old_emndn_cd8,
						o.ctprv_cd,
						btrim(coalesce(o.sgng_nm, '')) AS old_sgng_nm,
						btrim(coalesce(o.emndn_nm, '')) AS old_emndn_nm,
						-- base_city_root: 시군구명 포맷(공백/접두어)과 군↔시 전환을 모두 커버하기 위해,
						-- "...시/군"에서 시/군 접미사를 제거한 루트명으로 정규화한다.
						-- 예: '화성시', '화성시 만세구', '경기도 화성시 만세구', '화성군' => 모두 '화성'
						regexp_replace(btrim(coalesce(o.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\\\1') AS old_base_city,
						regexp_replace(btrim(coalesce(o.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS old_emndn_root
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
						btrim(coalesce(n.sgng_nm, '')) AS new_sgng_nm,
						btrim(coalesce(n.emndn_nm, '')) AS new_emndn_nm,
						regexp_replace(btrim(coalesce(n.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\\\1') AS new_base_city,
						regexp_replace(btrim(coalesce(n.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS new_emndn_root
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
						-- 점수(정렬용)
						-- - base_city는 조인에서 이미 동일해야 한다.
						-- - root(읍/면/동/리/가 제거) 일치: 강한 신호
						-- - emndn_nm 완전일치: 보조 신호
						-- - li tail2 교집합 개수: 이름 오타/변경이 있어도 연결 가능한 핵심 신호
						(
							CASE WHEN o.old_emndn_root = n.new_emndn_root THEN 100 ELSE 0 END
							+ CASE WHEN o.old_emndn_nm = n.new_emndn_nm THEN 20 ELSE 0 END
							+ COALESCE((
								SELECT count(*)
								FROM unnest(COALESCE(olt.old_tail2s, ARRAY[]::text[])) a
								JOIN unnest(COALESCE(nlt.new_tail2s, ARRAY[]::text[])) b ON a = b
							), 0) * 5
						) AS score
					FROM new_emndn n
					JOIN old_emndn o
					  ON o.ctprv_cd = n.ctprv_cd
					 AND o.old_base_city = n.new_base_city
					LEFT JOIN old_li_tails olt ON olt.old_emndn_cd8 = o.old_emndn_cd8
					LEFT JOIN new_li_tails nlt ON nlt.new_emndn_cd8 = n.new_emndn_cd8
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
				emndn_ranked AS (
					SELECT
						c.*,
						row_number() OVER (PARTITION BY c.new_emndn_cd10 ORDER BY c.score DESC, c.old_emndn_cd10) AS rn,
						max(c.score) OVER (PARTITION BY c.new_emndn_cd10) AS max_score
					FROM emndn_candidates c
				),
				emndn_scored AS (
					SELECT
						r.*,
						sum(CASE WHEN r.score = r.max_score THEN 1 ELSE 0 END) OVER (PARTITION BY r.new_emndn_cd10) AS top_ties
					FROM emndn_ranked r
				),
				emndn_unique_map AS (
					SELECT
						s.new_emndn_cd8,
						s.old_emndn_cd10 AS chosen_old_emndn_cd10,
						s.old_emndn_cd8 AS chosen_old_emndn_cd8
					FROM emndn_scored s
					WHERE s.rn = 1
					  AND s.max_score > 0
					  AND s.top_ties = 1
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
					(SELECT count(*) FROM old_emndn) AS old_emndn_cnt,
					(SELECT count(*) FROM new_emndn) AS new_emndn_cnt,
					(SELECT count(*) FROM emndn_agg WHERE candidate_cnt = 0) AS emndn_missing_cnt,
					(SELECT count(*) FROM emndn_agg WHERE candidate_cnt > 1) AS emndn_ambiguous_cnt,
					(SELECT count(*) FROM old_li) AS old_li_cnt,
					(SELECT count(*) FROM new_li) AS new_li_cnt,
					(SELECT missing_cnt FROM li_missing_old) AS li_missing_old_cnt
				""";

		return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new Stats(
				rs.getInt("old_emndn_cnt"),
				rs.getInt("new_emndn_cnt"),
				rs.getInt("emndn_ambiguous_cnt"),
				rs.getInt("emndn_missing_cnt"),
				rs.getInt("old_li_cnt"),
				rs.getInt("new_li_cnt"),
				rs.getInt("li_missing_old_cnt")));
	}

	private int updateEmndn(Map<String, Object> params) {
		String sql = """
				WITH
				old_li_tails AS (
					SELECT
						substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
						array_agg(DISTINCT right(o.legal_dong_cd, 2) ORDER BY right(o.legal_dong_cd, 2)) AS old_tail2s
					FROM tb_legal_dong_l o
					WHERE o.li_cd IS NOT NULL
					  AND o.dlt_dt = :effDt
					GROUP BY substring(o.legal_dong_cd, 1, 8)
				),
				new_li_tails AS (
					SELECT
						substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
						array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
					FROM tb_legal_dong_l n
					WHERE n.li_cd IS NOT NULL
					  AND n.cr_dt = :effDt
					GROUP BY substring(n.legal_dong_cd, 1, 8)
				),
				old_emndn AS (
					SELECT
						o.legal_dong_cd AS old_emndn_cd10,
						o.emndn_cd AS old_emndn_cd8,
						o.ctprv_cd,
						btrim(coalesce(o.emndn_nm, '')) AS old_emndn_nm,
						regexp_replace(btrim(coalesce(o.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\\\1') AS old_base_city,
						regexp_replace(btrim(coalesce(o.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS old_emndn_root
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
						btrim(coalesce(n.emndn_nm, '')) AS new_emndn_nm,
						regexp_replace(btrim(coalesce(n.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\\\1') AS new_base_city,
						regexp_replace(btrim(coalesce(n.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS new_emndn_root
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
							WHEN n.new_base_city <> '' AND o.old_base_city <> '' AND n.new_base_city = o.old_base_city THEN (
								CASE WHEN o.old_emndn_root = n.new_emndn_root THEN 100 ELSE 0 END
								+ CASE WHEN o.old_emndn_nm = n.new_emndn_nm THEN 20 ELSE 0 END
								+ COALESCE((
									SELECT count(*)
									FROM unnest(COALESCE(olt.old_tail2s, ARRAY[]::text[])) a
									JOIN unnest(COALESCE(nlt.new_tail2s, ARRAY[]::text[])) b ON a = b
								), 0) * 5
							)
							ELSE 0
						END AS score
					FROM new_emndn n
					JOIN old_emndn o
					  ON o.ctprv_cd = n.ctprv_cd
					 AND o.old_base_city = n.new_base_city
					LEFT JOIN old_li_tails olt ON olt.old_emndn_cd8 = o.old_emndn_cd8
					LEFT JOIN new_li_tails nlt ON nlt.new_emndn_cd8 = n.new_emndn_cd8
				),
				emndn_ranked AS (
					SELECT
						c.new_emndn_cd10,
						c.old_emndn_cd10,
						c.score,
						row_number() OVER (PARTITION BY c.new_emndn_cd10 ORDER BY c.score DESC, c.old_emndn_cd10) AS rn,
						max(c.score) OVER (PARTITION BY c.new_emndn_cd10) AS max_score
					FROM emndn_candidates c
				),
				emndn_scored AS (
					SELECT
						r.*,
						sum(CASE WHEN r.score = r.max_score THEN 1 ELSE 0 END) OVER (PARTITION BY r.new_emndn_cd10) AS top_ties
					FROM emndn_ranked r
				),
				apply_targets AS (
					SELECT
						s.new_emndn_cd10 AS new_legal_dong_cd,
						s.old_emndn_cd10 AS past_legal_dong_cd
					FROM emndn_scored s
					WHERE s.rn = 1
					  AND s.max_score > 0
					  AND s.top_ties = 1
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
				old_li_tails AS (
					SELECT
						substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
						array_agg(DISTINCT right(o.legal_dong_cd, 2) ORDER BY right(o.legal_dong_cd, 2)) AS old_tail2s
					FROM tb_legal_dong_l o
					WHERE o.li_cd IS NOT NULL
					  AND o.dlt_dt = :effDt
					GROUP BY substring(o.legal_dong_cd, 1, 8)
				),
				new_li_tails AS (
					SELECT
						substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
						array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
					FROM tb_legal_dong_l n
					WHERE n.li_cd IS NOT NULL
					  AND n.cr_dt = :effDt
					GROUP BY substring(n.legal_dong_cd, 1, 8)
				),
				old_emndn AS (
					SELECT
						o.emndn_cd AS old_emndn_cd8,
						o.ctprv_cd,
						btrim(coalesce(o.emndn_nm, '')) AS old_emndn_nm,
						regexp_replace(btrim(coalesce(o.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\\\1') AS old_base_city,
						regexp_replace(btrim(coalesce(o.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS old_emndn_root
					FROM tb_legal_dong_l o
					WHERE o.li_cd IS NULL
					  AND o.emndn_cd IS NOT NULL
					  AND o.dlt_dt = :effDt
				),
				new_emndn AS (
					SELECT
						n.emndn_cd AS new_emndn_cd8,
						n.ctprv_cd,
						btrim(coalesce(n.emndn_nm, '')) AS new_emndn_nm,
						regexp_replace(btrim(coalesce(n.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\\\1') AS new_base_city,
						regexp_replace(btrim(coalesce(n.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS new_emndn_root
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
							WHEN n.new_base_city <> '' AND o.old_base_city <> '' AND n.new_base_city = o.old_base_city THEN (
								CASE WHEN o.old_emndn_root = n.new_emndn_root THEN 100 ELSE 0 END
								+ CASE WHEN o.old_emndn_nm = n.new_emndn_nm THEN 20 ELSE 0 END
								+ COALESCE((
									SELECT count(*)
									FROM unnest(COALESCE(olt.old_tail2s, ARRAY[]::text[])) a
									JOIN unnest(COALESCE(nlt.new_tail2s, ARRAY[]::text[])) b ON a = b
								), 0) * 5
							)
							ELSE 0
						END AS score
					FROM new_emndn n
					JOIN old_emndn o
					  ON o.ctprv_cd = n.ctprv_cd
					 AND o.old_base_city = n.new_base_city
					LEFT JOIN old_li_tails olt ON olt.old_emndn_cd8 = o.old_emndn_cd8
					LEFT JOIN new_li_tails nlt ON nlt.new_emndn_cd8 = n.new_emndn_cd8
				),
				emndn_ranked AS (
					SELECT
						c.new_emndn_cd8,
						c.old_emndn_cd8,
						c.score,
						row_number() OVER (PARTITION BY c.new_emndn_cd8 ORDER BY c.score DESC, c.old_emndn_cd8) AS rn,
						max(c.score) OVER (PARTITION BY c.new_emndn_cd8) AS max_score
					FROM emndn_candidates c
				),
				emndn_scored AS (
					SELECT
						r.*,
						sum(CASE WHEN r.score = r.max_score THEN 1 ELSE 0 END) OVER (PARTITION BY r.new_emndn_cd8) AS top_ties
					FROM emndn_ranked r
				),
				emndn_unique_map AS (
					SELECT
						s.new_emndn_cd8,
						s.old_emndn_cd8 AS chosen_old_emndn_cd8
					FROM emndn_scored s
					WHERE s.rn = 1
					  AND s.max_score > 0
					  AND s.top_ties = 1
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

	private record Stats(
			int oldEmndnCnt,
			int newEmndnCnt,
			int emndnAmbiguousCnt,
			int emndnMissingCnt,
			int oldLiCnt,
			int newLiCnt,
			int liMissingOldCnt) {
	}
}
