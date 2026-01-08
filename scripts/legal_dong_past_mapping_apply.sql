-- 과거법정동코드(past_legal_dong_cd) 적용 SQL (PostgreSQL)
--
-- 목적
-- - 시행일(eff_dt) 기준으로 신규 코드(cr_dt=eff_dt)에 과거 코드(old.dlt_dt=eff_dt)를 채운다.
-- - 이미 적재된 데이터(마이그레이션 이전/중간 단계)에도 재실행 가능하도록 멱등하게 구성한다.
--
-- 실행 예시(psql)
--   psql "postgresql://postgres:root@localhost:5432/testdb" ^
--     -v eff_dt="20260201" ^
--     -v operator_id="ADMIN" ^
--     -f scripts/legal_dong_past_mapping_apply.sql
--
-- 규칙(안전 우선)
-- - old는 반드시 말소된 코드만 사용: old.dlt_dt = eff_dt
-- - new는 신규 생성만 대상: new.cr_dt = eff_dt AND new.past_legal_dong_cd IS NULL
-- - 읍면동(동 포함)은 이름 기반(동일 base_city + emndn_nm)에서 후보가 1개일 때만 자동 적용한다.
-- - 리(li)는 읍면동 매핑이 확정된 경우에만, (emndn_cd8 + tail2)로 old li를 찾아 적용한다.
--
\set ON_ERROR_STOP on

\if :{?eff_dt}
\else
  \echo 'ERROR: -v eff_dt=YYYYMMDD 가 필요합니다.'
  \quit 1
\endif

\if :{?operator_id}
\else
  \set operator_id 'SYSTEM'
\endif

BEGIN;

WITH p AS (SELECT :'eff_dt'::varchar(8) AS eff_dt),
new_emndn AS (
	SELECT
		n.legal_dong_cd AS new_cd10,
		n.ctprv_cd,
		n.emndn_cd AS new_cd8,
		btrim(coalesce(n.emndn_nm, '')) AS emndn_nm,
		CASE
			WHEN position(chr(32) in btrim(coalesce(n.sgng_nm, ''))) > 0
			THEN substring(btrim(coalesce(n.sgng_nm, '')) for position(chr(32) in btrim(coalesce(n.sgng_nm, '')))-1)
			ELSE btrim(coalesce(n.sgng_nm, ''))
		END AS base_city
	FROM tb_legal_dong_l n, p
	WHERE n.li_cd IS NULL
	  AND n.emndn_cd IS NOT NULL
	  AND n.cr_dt = p.eff_dt
	  AND n.past_legal_dong_cd IS NULL
),
old_emndn AS (
	SELECT
		o.legal_dong_cd AS old_cd10,
		o.ctprv_cd,
		o.emndn_cd AS old_cd8,
		btrim(coalesce(o.emndn_nm, '')) AS emndn_nm,
		CASE
			WHEN position(chr(32) in btrim(coalesce(o.sgng_nm, ''))) > 0
			THEN substring(btrim(coalesce(o.sgng_nm, '')) for position(chr(32) in btrim(coalesce(o.sgng_nm, '')))-1)
			ELSE btrim(coalesce(o.sgng_nm, ''))
		END AS base_city
	FROM tb_legal_dong_l o, p
	WHERE o.li_cd IS NULL
	  AND o.emndn_cd IS NOT NULL
	  AND o.dlt_dt = p.eff_dt
),
emndn_map AS (
	SELECT
		n.new_cd10,
		n.new_cd8,
		(array_agg(o.old_cd10 ORDER BY o.old_cd10))[1] AS old_cd10,
		(array_agg(o.old_cd8 ORDER BY o.old_cd10))[1] AS old_cd8,
		count(*) AS cnt
	FROM new_emndn n
	JOIN old_emndn o
	  ON o.ctprv_cd = n.ctprv_cd
	 AND o.base_city = n.base_city
	 AND o.emndn_nm = n.emndn_nm
	GROUP BY n.new_cd10, n.new_cd8
	HAVING count(*) = 1
),
emndn_updated AS (
	UPDATE tb_legal_dong_l t
	SET
		past_legal_dong_cd = m.old_cd10,
		last_updt_dtm = now(),
		last_upusr_id = :'operator_id'
	FROM emndn_map m, p
	WHERE t.legal_dong_cd = m.new_cd10
	  AND t.cr_dt = p.eff_dt
	  AND t.past_legal_dong_cd IS NULL
	RETURNING t.legal_dong_cd
)
SELECT 'emndn_updated' AS k, count(*) AS v FROM emndn_updated;

WITH p AS (SELECT :'eff_dt'::varchar(8) AS eff_dt),
map_emndn AS (
	-- 신규 읍면동 past가 이미 채워진 경우도 포함(재실행/부분실행 케이스)
	SELECT
		n.emndn_cd AS new_emndn_cd8,
		o.emndn_cd AS old_emndn_cd8
	FROM tb_legal_dong_l n
	JOIN tb_legal_dong_l o ON o.legal_dong_cd = n.past_legal_dong_cd
	WHERE n.li_cd IS NULL
	  AND n.emndn_cd IS NOT NULL
	  AND n.cr_dt = (SELECT eff_dt FROM p)
),
new_li AS (
	SELECT
		n.legal_dong_cd AS new_li_cd10,
		substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
		right(n.legal_dong_cd, 2) AS tail2
	FROM tb_legal_dong_l n, p
	WHERE n.li_cd IS NOT NULL
	  AND n.cr_dt = p.eff_dt
	  AND n.past_legal_dong_cd IS NULL
),
old_li AS (
	SELECT
		o.legal_dong_cd AS old_li_cd10,
		substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
		right(o.legal_dong_cd, 2) AS tail2
	FROM tb_legal_dong_l o, p
	WHERE o.li_cd IS NOT NULL
	  AND o.dlt_dt = p.eff_dt
),
li_targets AS (
	SELECT
		n.new_li_cd10,
		o.old_li_cd10
	FROM new_li n
	JOIN map_emndn m ON m.new_emndn_cd8 = n.new_emndn_cd8
	JOIN old_li o ON o.old_emndn_cd8 = m.old_emndn_cd8 AND o.tail2 = n.tail2
),
li_updated AS (
	UPDATE tb_legal_dong_l t
	SET
		past_legal_dong_cd = a.old_li_cd10,
		last_updt_dtm = now(),
		last_upusr_id = :'operator_id'
	FROM li_targets a, p
	WHERE t.legal_dong_cd = a.new_li_cd10
	  AND t.cr_dt = p.eff_dt
	  AND t.past_legal_dong_cd IS NULL
	RETURNING t.legal_dong_cd
)
SELECT 'li_updated' AS k, count(*) AS v FROM li_updated;

COMMIT;

