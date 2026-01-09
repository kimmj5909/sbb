-- Past legal_dong_cd mapping debug (single new code) for PostgreSQL.
--
-- IMPORTANT (Windows psql.exe)
-- - This file is ASCII-only and forces UTF-8 client encoding first to avoid UHC/UTF-8 conversion errors.
\encoding UTF8
SET client_encoding = 'UTF8';

-- Usage (psql):
--   \set eff_dt '20260201'
--   \set new_cd '4159125600'
--   \i scripts/legal_dong_past_mapping_debug_one.sql

DROP TABLE IF EXISTS tmp_ld_pm_new_row;
DROP TABLE IF EXISTS tmp_ld_pm_old_emndn;
DROP TABLE IF EXISTS tmp_ld_pm_old_li_tails;
DROP TABLE IF EXISTS tmp_ld_pm_new_li_tails;
DROP TABLE IF EXISTS tmp_ld_pm_scored;

CREATE TEMP TABLE tmp_ld_pm_new_row AS
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
FROM tb_legal_dong_l n
WHERE n.legal_dong_cd = :'new_cd'::varchar(10);

CREATE TEMP TABLE tmp_ld_pm_old_li_tails AS
SELECT
	substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
	array_agg(DISTINCT right(o.legal_dong_cd, 2) ORDER BY right(o.legal_dong_cd, 2)) AS old_tail2s
FROM tb_legal_dong_l o
WHERE o.li_cd IS NOT NULL
  AND o.dlt_dt = :'eff_dt'::varchar(8)
GROUP BY substring(o.legal_dong_cd, 1, 8);

CREATE TEMP TABLE tmp_ld_pm_new_li_tails AS
SELECT
	substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
	array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
FROM tb_legal_dong_l n
WHERE n.li_cd IS NOT NULL
  AND n.cr_dt = :'eff_dt'::varchar(8)
GROUP BY substring(n.legal_dong_cd, 1, 8);

CREATE TEMP TABLE tmp_ld_pm_old_emndn AS
SELECT
	o.legal_dong_cd AS old_emndn_cd10,
	o.ctprv_cd,
	o.sgng_cd AS old_sgng_cd,
	btrim(coalesce(o.sgng_nm, '')) AS old_sgng_nm,
	o.emndn_cd AS old_emndn_cd8,
	btrim(coalesce(o.emndn_nm, '')) AS old_emndn_nm,
	regexp_replace(btrim(coalesce(o.sgng_nm, '')), '\\s.*$', '') AS old_base_city,
	regexp_replace(btrim(coalesce(o.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS old_emndn_root,
	o.cr_dt AS old_cr_dt,
	o.dlt_dt AS old_dlt_dt
FROM tb_legal_dong_l o
WHERE o.li_cd IS NULL
  AND o.emndn_cd IS NOT NULL
  AND o.dlt_dt = :'eff_dt'::varchar(8);

CREATE TEMP TABLE tmp_ld_pm_scored AS
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
FROM tmp_ld_pm_new_row n
JOIN tmp_ld_pm_old_emndn o
  ON o.ctprv_cd = n.ctprv_cd
 AND o.old_base_city = n.base_city
LEFT JOIN tmp_ld_pm_old_li_tails olt ON olt.old_emndn_cd8 = o.old_emndn_cd8
LEFT JOIN tmp_ld_pm_new_li_tails nlt ON nlt.new_emndn_cd8 = n.emndn_cd;

-- 1) New row check (target 조건)
SELECT
	:'eff_dt'::varchar(8) AS eff_dt,
	n.legal_dong_cd AS new_legal_dong_cd,
	n.sgng_cd AS new_sgng_cd,
	n.sgng_nm AS new_sgng_nm,
	n.emndn_cd AS new_emndn_cd8,
	n.emndn_nm AS new_emndn_nm,
	n.cr_dt AS new_cr_dt,
	n.dlt_dt AS new_dlt_dt,
	n.past_legal_dong_cd AS new_past_legal_dong_cd,
	CASE WHEN n.cr_dt = :'eff_dt'::varchar(8) THEN 'Y' ELSE 'N' END AS is_new_on_eff_dt,
	CASE WHEN n.past_legal_dong_cd IS NULL THEN 'Y' ELSE 'N' END AS is_past_null,
	CASE WHEN n.li_cd IS NULL THEN 'Y' ELSE 'N' END AS is_emndn_level
FROM tmp_ld_pm_new_row n;

-- 2) Candidate list + score
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
FROM tmp_ld_pm_scored s
ORDER BY s.score DESC, s.old_emndn_cd10;

-- 3) Uniqueness decision (rule: max_score>0 AND top_ties=1)
WITH ranked AS (
	SELECT
		s.*,
		max(s.score) OVER () AS max_score
	FROM tmp_ld_pm_scored s
),
tied AS (
	SELECT
		r.*,
		sum(CASE WHEN r.score = r.max_score THEN 1 ELSE 0 END) OVER () AS top_ties
	FROM ranked r
)
SELECT
	COALESCE(max(max_score), 0) AS max_score,
	COALESCE(max(top_ties), 0) AS top_ties,
	CASE WHEN COALESCE(max(max_score), 0) > 0 AND COALESCE(max(top_ties), 0) = 1 THEN 'Y' ELSE 'N' END AS can_auto_map,
	(array_agg(old_emndn_cd10 ORDER BY score DESC, old_emndn_cd10))[1] AS chosen_old_emndn_cd10
FROM tied;
