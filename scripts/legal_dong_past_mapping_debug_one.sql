-- 과거법정동코드(past_legal_dong_cd) 자동 매핑 디버그(1건) SQL (PostgreSQL)
--
-- 목적
-- - 특정 신규 법정동코드(10자리) 1건에 대해,
--   * 신규 row가 매핑 대상 조건(new.cr_dt=eff_dt AND past IS NULL)을 만족하는지
--   * 구 row 후보가 존재하는지(old.dlt_dt=eff_dt)
--   * 후보 점수(=현재 애플리케이션 매핑 로직)를 기준으로 유니크 매핑이 가능한지
--   를 한 번에 확인한다.
--
-- 사용법(psql)
--   \set eff_dt '20260201'
--   \set new_cd '4159125600'
--   \i scripts/legal_dong_past_mapping_debug_one.sql
--
-- 참고
-- - "시군구코드(sgng_cd) 41590 -> 41591" 변경은 비교 조건(필터)로 사용하지 않는다.
--   현재 매핑의 필수 조건은 ctprv_cd + base_city(=sgng_nm 첫 토큰)이며,
--   sgng_cd 변경이 있어도 base_city가 같으면 후보로 포함된다.
-- - 따라서 매핑이 안 되는 원인은 대체로 다음 중 하나다.
--   1) 신규 row가 new.cr_dt != eff_dt 이거나 past가 이미 채워짐
--   2) 구 row가 old.dlt_dt != eff_dt (말소일자 최신값 누락/미반영)
--   3) emndn_nm 누락/비정상으로 root 비교가 0점 처리되어 max_score=0
--
WITH
params AS (
	SELECT
		:'eff_dt'::varchar(8) AS eff_dt,
		:'new_cd'::varchar(10) AS new_cd
),
new_row AS (
	SELECT
		n.legal_dong_cd,
		n.ctprv_cd,
		btrim(coalesce(n.ctprv_nm, '')) AS ctprv_nm,
		n.sgng_cd,
		btrim(coalesce(n.sgng_nm, '')) AS sgng_nm,
		regexp_replace(btrim(coalesce(n.sgng_nm, '')), '\\s.*$', '') AS base_city,
		n.emndn_cd,
		btrim(coalesce(n.emndn_nm, '')) AS emndn_nm,
		regexp_replace(btrim(coalesce(n.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS emndn_root,
		n.li_cd,
		btrim(coalesce(n.li_nm, '')) AS li_nm,
		n.rank,
		n.cr_dt,
		n.dlt_dt,
		n.past_legal_dong_cd
	FROM tb_legal_dong_l n, params p
	WHERE n.legal_dong_cd = p.new_cd
),
old_li_tails AS (
	SELECT
		substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
		array_agg(DISTINCT right(o.legal_dong_cd, 2) ORDER BY right(o.legal_dong_cd, 2)) AS old_tail2s
	FROM tb_legal_dong_l o, params p
	WHERE o.li_cd IS NOT NULL
	  AND o.dlt_dt = p.eff_dt
	GROUP BY substring(o.legal_dong_cd, 1, 8)
),
new_li_tails AS (
	SELECT
		substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
		array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
	FROM tb_legal_dong_l n, params p
	WHERE n.li_cd IS NOT NULL
	  AND n.cr_dt = p.eff_dt
	GROUP BY substring(n.legal_dong_cd, 1, 8)
),
old_emndn_candidates AS (
	SELECT
		o.legal_dong_cd AS old_emndn_cd10,
		o.sgng_cd AS old_sgng_cd,
		btrim(coalesce(o.sgng_nm, '')) AS old_sgng_nm,
		o.emndn_cd AS old_emndn_cd8,
		btrim(coalesce(o.emndn_nm, '')) AS old_emndn_nm,
		regexp_replace(btrim(coalesce(o.sgng_nm, '')), '\\s.*$', '') AS old_base_city,
		regexp_replace(btrim(coalesce(o.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS old_emndn_root,
		o.cr_dt AS old_cr_dt,
		o.dlt_dt AS old_dlt_dt
	FROM tb_legal_dong_l o, params p
	WHERE o.li_cd IS NULL
	  AND o.emndn_cd IS NOT NULL
	  AND o.dlt_dt = p.eff_dt
),
scored AS (
	SELECT
		n.legal_dong_cd AS new_emndn_cd10,
		n.sgng_cd AS new_sgng_cd,
		n.sgng_nm AS new_sgng_nm,
		n.emndn_cd AS new_emndn_cd8,
		n.emndn_nm AS new_emndn_nm,
		n.base_city AS new_base_city,
		n.emndn_root AS new_emndn_root,
		o.old_emndn_cd10,
		o.old_sgng_cd,
		o.old_sgng_nm,
		o.old_emndn_cd8,
		o.old_emndn_nm,
		o.old_base_city,
		o.old_emndn_root,
		(
			CASE WHEN o.old_emndn_root = n.emndn_root THEN 100 ELSE 0 END
			+ CASE WHEN o.old_emndn_nm = n.emndn_nm THEN 20 ELSE 0 END
			+ COALESCE((
				SELECT count(*)
				FROM unnest(COALESCE(olt.old_tail2s, ARRAY[]::text[])) a
				JOIN unnest(COALESCE(nlt.new_tail2s, ARRAY[]::text[])) b ON a = b
			), 0) * 5
		) AS score
	FROM new_row n
	JOIN old_emndn_candidates o
	  ON o.old_base_city = n.base_city
	 AND o.ctprv_cd = n.ctprv_cd
	LEFT JOIN old_li_tails olt ON olt.old_emndn_cd8 = o.old_emndn_cd8
	LEFT JOIN new_li_tails nlt ON nlt.new_emndn_cd8 = n.emndn_cd
)
-- 1) 신규 row(매핑 타겟 조건 점검)
SELECT
	p.eff_dt,
	n.legal_dong_cd AS new_legal_dong_cd,
	n.sgng_cd AS new_sgng_cd,
	n.sgng_nm AS new_sgng_nm,
	n.emndn_cd AS new_emndn_cd8,
	n.emndn_nm AS new_emndn_nm,
	n.cr_dt AS new_cr_dt,
	n.dlt_dt AS new_dlt_dt,
	n.past_legal_dong_cd AS new_past_legal_dong_cd,
	CASE WHEN n.cr_dt = p.eff_dt THEN 'Y' ELSE 'N' END AS is_new_on_eff_dt,
	CASE WHEN n.past_legal_dong_cd IS NULL THEN 'Y' ELSE 'N' END AS is_past_null
FROM params p
LEFT JOIN new_row n ON 1=1;

-- 2) 후보 목록(필터: old.dlt_dt=eff_dt AND ctprv_cd + base_city 일치) + 점수
SELECT
	s.old_emndn_cd10,
	s.old_sgng_cd,
	s.old_sgng_nm,
	s.old_emndn_cd8,
	s.old_emndn_nm,
	s.score,
	s.new_base_city,
	s.old_base_city,
	s.new_emndn_root,
	s.old_emndn_root
FROM scored s
ORDER BY s.score DESC, s.old_emndn_cd10;

-- 3) 점수 기준 유니크 선택 가능 여부(애플리케이션 규칙: max_score>0 AND top_ties=1)
WITH ranked AS (
	SELECT
		s.*,
		max(s.score) OVER () AS max_score
	FROM scored s
),
tied AS (
	SELECT
		r.*,
		sum(CASE WHEN r.score = r.max_score THEN 1 ELSE 0 END) OVER () AS top_ties
	FROM ranked r
)
SELECT
	max_score,
	top_ties,
	CASE WHEN max_score > 0 AND top_ties = 1 THEN 'Y' ELSE 'N' END AS can_auto_map,
	(array_agg(old_emndn_cd10 ORDER BY score DESC, old_emndn_cd10))[1] AS chosen_old_emndn_cd10
FROM tied;

