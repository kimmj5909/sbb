-- 과거법정동코드(past_legal_dong_cd) 일괄 적용 SQL (PostgreSQL)
--
-- 목적
-- - DB에 이미 적재된 데이터 중, 시행일(cr_dt) 기준으로 신규 코드의 past_legal_dong_cd가 비어있는 건을 찾아
--   가능한 범위(유니크 매핑)에서 자동으로 채운다.
--
-- 특징
-- - eff_dt(시행일)를 외부에서 주지 않아도, DB에서 "신규 생성(cr_dt) + past NULL" 데이터가 존재하는 날짜를 자동 탐색한다.
-- - old는 반드시 말소된 코드만 사용: old.dlt_dt = eff_dt
-- - 명칭 변경/오타를 고려해, 읍면동(동 포함) 매핑은 "base_city + emndn_nm" 유니크 매핑만 적용한다.
-- - 리(li) 매핑은 읍면동 매핑이 확정된 경우에만 (emndn_cd8 + tail2)로 연결한다.
--
-- 실행 예시(psql)
--   psql "postgresql://postgres:root@localhost:5432/testdb" -v operator_id="ADMIN" -f scripts/legal_dong_past_mapping_apply_all.sql
--
\set ON_ERROR_STOP on

\if :{?operator_id}
\else
  \set operator_id 'SYSTEM'
\endif

BEGIN;

WITH eff_dates AS (
	SELECT DISTINCT cr_dt AS eff_dt
	FROM tb_legal_dong_l
	WHERE cr_dt IS NOT NULL
	  AND length(cr_dt) = 8
	  AND translate(cr_dt, '0123456789', '') = ''
	  AND past_legal_dong_cd IS NULL
),
new_emndn AS (
	SELECT
		n.legal_dong_cd AS new_cd10,
		n.ctprv_cd,
		n.emndn_cd AS new_cd8,
		n.cr_dt AS eff_dt,
		btrim(coalesce(n.emndn_nm, '')) AS emndn_nm,
		CASE
			WHEN position(chr(32) in btrim(coalesce(n.sgng_nm, ''))) > 0
			THEN substring(btrim(coalesce(n.sgng_nm, '')) for position(chr(32) in btrim(coalesce(n.sgng_nm, '')))-1)
			ELSE btrim(coalesce(n.sgng_nm, ''))
		END AS base_city
	FROM tb_legal_dong_l n
	JOIN eff_dates e ON e.eff_dt = n.cr_dt
	WHERE n.li_cd IS NULL
	  AND n.emndn_cd IS NOT NULL
	  AND n.past_legal_dong_cd IS NULL
),
old_emndn AS (
	SELECT
		o.legal_dong_cd AS old_cd10,
		o.ctprv_cd,
		o.emndn_cd AS old_cd8,
		o.dlt_dt AS eff_dt,
		btrim(coalesce(o.emndn_nm, '')) AS emndn_nm,
		CASE
			WHEN position(chr(32) in btrim(coalesce(o.sgng_nm, ''))) > 0
			THEN substring(btrim(coalesce(o.sgng_nm, '')) for position(chr(32) in btrim(coalesce(o.sgng_nm, '')))-1)
			ELSE btrim(coalesce(o.sgng_nm, ''))
		END AS base_city
	FROM tb_legal_dong_l o
	JOIN eff_dates e ON e.eff_dt = o.dlt_dt
	WHERE o.li_cd IS NULL
	  AND o.emndn_cd IS NOT NULL
),
emndn_map AS (
	SELECT
		n.eff_dt,
		n.new_cd10,
		n.new_cd8,
		(array_agg(o.old_cd10 ORDER BY o.old_cd10))[1] AS old_cd10,
		(array_agg(o.old_cd8 ORDER BY o.old_cd10))[1] AS old_cd8,
		count(*) AS cnt
	FROM new_emndn n
	JOIN old_emndn o
	  ON o.eff_dt = n.eff_dt
	 AND o.ctprv_cd = n.ctprv_cd
	 AND o.base_city = n.base_city
	 AND o.emndn_nm = n.emndn_nm
	GROUP BY n.eff_dt, n.new_cd10, n.new_cd8
	HAVING count(*) = 1
),
emndn_updated AS (
	UPDATE tb_legal_dong_l t
	SET
		past_legal_dong_cd = m.old_cd10,
		last_updt_dtm = now(),
		last_upusr_id = :'operator_id'
	FROM emndn_map m
	WHERE t.legal_dong_cd = m.new_cd10
	  AND t.cr_dt = m.eff_dt
	  AND t.past_legal_dong_cd IS NULL
	RETURNING t.cr_dt AS eff_dt
)
SELECT 'emndn_updated_total' AS k, count(*) AS v FROM emndn_updated;

WITH eff_dates AS (
	SELECT DISTINCT cr_dt AS eff_dt
	FROM tb_legal_dong_l
	WHERE cr_dt IS NOT NULL
	  AND length(cr_dt) = 8
	  AND translate(cr_dt, '0123456789', '') = ''
	  AND past_legal_dong_cd IS NULL
),
map_emndn AS (
	-- 신규 읍면동 past가 이미 채워진 경우도 포함(일부만 적용된 상태에서 재실행 가능)
	SELECT
		n.cr_dt AS eff_dt,
		n.emndn_cd AS new_emndn_cd8,
		o.emndn_cd AS old_emndn_cd8
	FROM tb_legal_dong_l n
	JOIN tb_legal_dong_l o ON o.legal_dong_cd = n.past_legal_dong_cd
	JOIN eff_dates e ON e.eff_dt = n.cr_dt
	WHERE n.li_cd IS NULL
	  AND n.emndn_cd IS NOT NULL
),
new_li AS (
	SELECT
		n.legal_dong_cd AS new_li_cd10,
		substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
		right(n.legal_dong_cd, 2) AS tail2,
		n.cr_dt AS eff_dt
	FROM tb_legal_dong_l n
	JOIN eff_dates e ON e.eff_dt = n.cr_dt
	WHERE n.li_cd IS NOT NULL
	  AND n.past_legal_dong_cd IS NULL
),
old_li AS (
	SELECT
		o.legal_dong_cd AS old_li_cd10,
		substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
		right(o.legal_dong_cd, 2) AS tail2,
		o.dlt_dt AS eff_dt
	FROM tb_legal_dong_l o
	JOIN eff_dates e ON e.eff_dt = o.dlt_dt
	WHERE o.li_cd IS NOT NULL
),
li_targets AS (
	SELECT
		n.eff_dt,
		n.new_li_cd10,
		o.old_li_cd10
	FROM new_li n
	JOIN map_emndn m ON m.eff_dt = n.eff_dt AND m.new_emndn_cd8 = n.new_emndn_cd8
	JOIN old_li o ON o.eff_dt = n.eff_dt AND o.old_emndn_cd8 = m.old_emndn_cd8 AND o.tail2 = n.tail2
),
li_updated AS (
	UPDATE tb_legal_dong_l t
	SET
		past_legal_dong_cd = a.old_li_cd10,
		last_updt_dtm = now(),
		last_upusr_id = :'operator_id'
	FROM li_targets a
	WHERE t.legal_dong_cd = a.new_li_cd10
	  AND t.cr_dt = a.eff_dt
	  AND t.past_legal_dong_cd IS NULL
	RETURNING t.cr_dt AS eff_dt
)
SELECT 'li_updated_total' AS k, count(*) AS v FROM li_updated;

COMMIT;

