-- 법정동 마이그레이션 템플릿 (PostgreSQL / DBeaver / TEMP TABLE + CSV Import)
--
-- 목적
-- - 운영에서 영구 스테이징 테이블 없이도, 테스트/점검 환경에서
--   1) TEMP 테이블 생성
--   2) DBeaver CSV Import
--   3) tb_legal_dong_l 업서트 + past 자동 반영
-- 을 같은 세션(연결)에서 수행하기 위한 템플릿이다.
--
-- 중요
-- - TEMP TABLE은 세션(연결) 종료 시 자동 삭제된다.
-- - DBeaver에서 아래 순서를 지키면 "명시적 DROP" 없이도 작업이 끝난다.
--
-- 사용 순서(DBeaver)
-- 1) 이 파일의 [STEP 1]만 실행 → TEMP 테이블 생성
-- 2) DBeaver "Import Data"로 CSV를 `pg_temp.tmp_legal_dong_excel_csv`에 Import
--    - CSV 컬럼은 엑셀과 동일(8컬럼)
--    - 매핑:
--      행정동코드→admin_cd_raw
--      시도명→ctprv_nm_raw
--      시군구명→sgng_nm_raw
--      읍면동명→emndn_nm_raw
--      법정동코드→legal_cd_raw
--      동리명→li_nm_raw
--      생성일자→cr_dt_raw
--      말소일자→dlt_dt_raw
-- 3) 이 파일의 [STEP 2]를 실행 → 업서트 + past 자동 반영
-- 4) 필요 시 [STEP 3] 리포트로 요약 확인
--
-- 작업 범위
-- - TEMP에 포함된 legal_dong_cd(=법정동코드 10자리) 범위만 대상으로 한다.
--
SET client_encoding = 'UTF8';

-- =========================================================
-- [STEP 1] TEMP 테이블 생성 (세션 내에서만 존재)
-- =========================================================
CREATE TEMP TABLE IF NOT EXISTS tmp_legal_dong_excel_csv (
	-- 원본 컬럼(엑셀 동일)
	admin_cd_raw  text NULL,  -- 행정동코드
	ctprv_nm_raw  text NULL,  -- 시도명
	sgng_nm_raw   text NULL,  -- 시군구명
	emndn_nm_raw  text NULL,  -- 읍면동명
	legal_cd_raw  text NULL,  -- 법정동코드(10자리 기대)
	li_nm_raw     text NULL,  -- 동리명
	cr_dt_raw     text NULL,  -- 생성일자(yyyyMMdd 또는 yyyy-mm-dd 등)
	dlt_dt_raw    text NULL   -- 말소일자(yyyyMMdd 또는 yyyy-mm-dd 등)
);

-- (선택) 같은 세션에서 재실행 시 TEMP를 비워서 다시 Import하고 싶으면 아래 실행
-- TRUNCATE TABLE tmp_legal_dong_excel_csv;

-- =========================================================
-- [STEP 2] 업서트 + past 자동 반영
-- =========================================================
-- operator_id는 last_upusr_id 등에 기록된다.
WITH
params AS (SELECT 'DBEAVER'::text AS operator_id),
normalized AS (
	SELECT
		NULLIF(btrim(ctprv_nm_raw), '') AS ctprv_nm,
		NULLIF(btrim(sgng_nm_raw), '') AS sgng_nm,
		NULLIF(btrim(emndn_nm_raw), '') AS emndn_nm_raw,
		NULLIF(btrim(li_nm_raw), '') AS li_nm_raw,
		regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g') AS legal_dong_cd,
		NULLIF(regexp_replace(coalesce(cr_dt_raw, ''), '[^0-9]', '', 'g'), '') AS cr_dt_digits,
		NULLIF(regexp_replace(coalesce(dlt_dt_raw, ''), '[^0-9]', '', 'g'), '') AS dlt_dt_digits
	FROM pg_temp.tmp_legal_dong_excel_csv
),
validated AS (
	SELECT
		ctprv_nm,
		sgng_nm,
		CASE
			WHEN length(legal_dong_cd) = 10 AND right(legal_dong_cd, 2) = '00'
			THEN coalesce(emndn_nm_raw, li_nm_raw)  -- 상위(리 없음) 행 보정
			ELSE emndn_nm_raw
		END AS emndn_nm,
		CASE
			WHEN length(legal_dong_cd) = 10 AND right(legal_dong_cd, 2) <> '00'
			THEN li_nm_raw
			ELSE NULL
		END AS li_nm,
		CASE WHEN length(legal_dong_cd) = 10 THEN legal_dong_cd ELSE NULL END AS legal_dong_cd,
		CASE WHEN cr_dt_digits IS NOT NULL AND length(cr_dt_digits) = 8 THEN cr_dt_digits ELSE NULL END AS cr_dt,
		CASE WHEN dlt_dt_digits IS NOT NULL AND length(dlt_dt_digits) = 8 THEN dlt_dt_digits ELSE NULL END AS dlt_dt
	FROM normalized
),
derived AS (
	SELECT
		v.legal_dong_cd,
		substring(v.legal_dong_cd, 1, 2) AS ctprv_cd,
		v.ctprv_nm,
		substring(v.legal_dong_cd, 1, 5) AS sgng_cd,
		v.sgng_nm,
		substring(v.legal_dong_cd, 1, 8) AS emndn_cd,
		v.emndn_nm,
		CASE WHEN right(v.legal_dong_cd, 2) = '00' THEN NULL ELSE v.legal_dong_cd END AS li_cd,
		CASE WHEN right(v.legal_dong_cd, 2) = '00' THEN NULL ELSE v.li_nm END AS li_nm,
		CASE
			WHEN right(v.legal_dong_cd, 2) = '00'
			THEN dense_rank() OVER (ORDER BY substring(v.legal_dong_cd, 1, 8))
			ELSE row_number() OVER (PARTITION BY substring(v.legal_dong_cd, 1, 8) ORDER BY v.legal_dong_cd)
		END AS rank,
		v.cr_dt,
		v.dlt_dt,
		concat_ws(' ', v.ctprv_nm, v.sgng_nm, v.emndn_nm, CASE WHEN right(v.legal_dong_cd, 2) = '00' THEN NULL ELSE v.li_nm END) AS legal_dong_nm
	FROM validated v
	WHERE v.legal_dong_cd IS NOT NULL
),
consolidated AS (
	SELECT
		d.legal_dong_cd,
		max(d.legal_dong_nm) AS legal_dong_nm,
		max(d.ctprv_cd) AS ctprv_cd,
		max(d.ctprv_nm) AS ctprv_nm,
		max(d.sgng_cd) AS sgng_cd,
		max(d.sgng_nm) AS sgng_nm,
		max(d.emndn_cd) AS emndn_cd,
		max(d.emndn_nm) AS emndn_nm,
		max(d.li_cd) AS li_cd,
		max(d.li_nm) AS li_nm,
		max(d.rank) AS rank,
		min(d.cr_dt) AS cr_dt,
		max(d.dlt_dt) AS dlt_dt
	FROM derived d
	GROUP BY d.legal_dong_cd
),
upserted AS (
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
	)
	SELECT
		c.legal_dong_cd, c.legal_dong_nm,
		c.ctprv_cd, c.ctprv_nm,
		c.sgng_cd, c.sgng_nm,
		c.emndn_cd, c.emndn_nm,
		c.li_cd, c.li_nm,
		c.rank, c.cr_dt, c.dlt_dt,
		NULL,
		now(), (SELECT operator_id FROM params),
		now(), (SELECT operator_id FROM params),
		CASE WHEN c.dlt_dt IS NULL THEN 'Y' ELSE NULL END
	FROM consolidated c
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
		cr_dt = CASE
			WHEN tb_legal_dong_l.cr_dt IS NULL THEN EXCLUDED.cr_dt
			WHEN EXCLUDED.cr_dt IS NULL THEN tb_legal_dong_l.cr_dt
			ELSE LEAST(tb_legal_dong_l.cr_dt, EXCLUDED.cr_dt)
		END,
		dlt_dt = CASE
			WHEN tb_legal_dong_l.dlt_dt IS NULL THEN EXCLUDED.dlt_dt
			WHEN EXCLUDED.dlt_dt IS NULL THEN tb_legal_dong_l.dlt_dt
			ELSE GREATEST(tb_legal_dong_l.dlt_dt, EXCLUDED.dlt_dt)
		END,
		use_yn = CASE
			WHEN (CASE
				WHEN tb_legal_dong_l.dlt_dt IS NULL THEN EXCLUDED.dlt_dt
				WHEN EXCLUDED.dlt_dt IS NULL THEN tb_legal_dong_l.dlt_dt
				ELSE GREATEST(tb_legal_dong_l.dlt_dt, EXCLUDED.dlt_dt)
			END) IS NULL THEN 'Y'
			ELSE NULL
		END,
		last_updt_dtm = now(),
		last_upusr_id = (SELECT operator_id FROM params)
	RETURNING legal_dong_cd
),
eff AS (
	SELECT DISTINCT cr_dt AS eff_dt
	FROM consolidated
	WHERE cr_dt IS NOT NULL AND cr_dt <> ''
),
stage_codes AS (
	SELECT legal_dong_cd FROM consolidated
),
-- emndn past (score/top_ties 유니크만)
old_li_tails AS (
	SELECT
		e.eff_dt,
		substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
		array_agg(DISTINCT right(o.legal_dong_cd, 2) ORDER BY right(o.legal_dong_cd, 2)) AS old_tail2s
	FROM eff e
	JOIN tb_legal_dong_l o ON o.dlt_dt = e.eff_dt
	WHERE o.li_cd IS NOT NULL
	GROUP BY e.eff_dt, substring(o.legal_dong_cd, 1, 8)
),
new_li_tails AS (
	SELECT
		e.eff_dt,
		substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
		array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
	FROM eff e
	JOIN tb_legal_dong_l n ON n.cr_dt = e.eff_dt
	WHERE n.li_cd IS NOT NULL
	GROUP BY e.eff_dt, substring(n.legal_dong_cd, 1, 8)
),
old_emndn AS (
	SELECT
		e.eff_dt,
		o.legal_dong_cd AS old_emndn_cd10,
		o.emndn_cd AS old_emndn_cd8,
		o.ctprv_cd,
		regexp_replace(btrim(coalesce(o.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\1') AS old_base_city,
		regexp_replace(btrim(coalesce(o.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS old_emndn_root,
		btrim(coalesce(o.emndn_nm, '')) AS old_emndn_nm
	FROM eff e
	JOIN tb_legal_dong_l o ON o.dlt_dt = e.eff_dt
	WHERE o.li_cd IS NULL
	  AND o.emndn_cd IS NOT NULL
),
new_emndn AS (
	SELECT
		e.eff_dt,
		n.legal_dong_cd AS new_emndn_cd10,
		n.emndn_cd AS new_emndn_cd8,
		n.ctprv_cd,
		regexp_replace(btrim(coalesce(n.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\1') AS new_base_city,
		regexp_replace(btrim(coalesce(n.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS new_emndn_root,
		btrim(coalesce(n.emndn_nm, '')) AS new_emndn_nm
	FROM eff e
	JOIN tb_legal_dong_l n ON n.cr_dt = e.eff_dt
	JOIN stage_codes s ON s.legal_dong_cd = n.legal_dong_cd
	WHERE n.li_cd IS NULL
	  AND n.emndn_cd IS NOT NULL
	  AND n.past_legal_dong_cd IS NULL
),
emndn_candidates AS (
	SELECT
		n.eff_dt,
		n.new_emndn_cd10,
		n.new_emndn_cd8,
		o.old_emndn_cd10,
		o.old_emndn_cd8,
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
	  ON o.eff_dt = n.eff_dt
	 AND o.ctprv_cd = n.ctprv_cd
	 AND o.old_base_city = n.new_base_city
	LEFT JOIN old_li_tails olt ON olt.eff_dt = n.eff_dt AND olt.old_emndn_cd8 = o.old_emndn_cd8
	LEFT JOIN new_li_tails nlt ON nlt.eff_dt = n.eff_dt AND nlt.new_emndn_cd8 = n.new_emndn_cd8
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
		s.eff_dt,
		s.new_emndn_cd10,
		s.new_emndn_cd8,
		s.old_emndn_cd10 AS chosen_old_emndn_cd10,
		s.old_emndn_cd8 AS chosen_old_emndn_cd8
	FROM emndn_scored s
	WHERE s.rn = 1
	  AND s.max_score > 0
	  AND s.top_ties = 1
),
emndn_updated AS (
	UPDATE tb_legal_dong_l t
	SET
		past_legal_dong_cd = m.chosen_old_emndn_cd10,
		last_updt_dtm = now(),
		last_upusr_id = (SELECT operator_id FROM params)
	FROM emndn_unique_map m
	WHERE t.legal_dong_cd = m.new_emndn_cd10
	  AND t.cr_dt = m.eff_dt
	  AND t.past_legal_dong_cd IS NULL
	RETURNING t.legal_dong_cd
),
-- li past
new_li AS (
	SELECT
		n.legal_dong_cd AS new_li_cd10,
		substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
		right(n.legal_dong_cd, 2) AS tail2,
		n.cr_dt AS eff_dt
	FROM tb_legal_dong_l n
	JOIN stage_codes s ON s.legal_dong_cd = n.legal_dong_cd
	WHERE n.li_cd IS NOT NULL
	  AND n.cr_dt IN (SELECT eff_dt FROM eff)
	  AND n.past_legal_dong_cd IS NULL
),
old_li AS (
	SELECT
		o.legal_dong_cd AS old_li_cd10,
		substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
		right(o.legal_dong_cd, 2) AS tail2,
		o.dlt_dt AS eff_dt
	FROM tb_legal_dong_l o
	WHERE o.li_cd IS NOT NULL
	  AND o.dlt_dt IN (SELECT eff_dt FROM eff)
),
li_targets AS (
	SELECT
		n.new_li_cd10,
		o.old_li_cd10
	FROM new_li n
	JOIN emndn_unique_map m ON m.eff_dt = n.eff_dt AND m.new_emndn_cd8 = n.new_emndn_cd8
	JOIN old_li o ON o.eff_dt = n.eff_dt AND o.old_emndn_cd8 = m.chosen_old_emndn_cd8 AND o.tail2 = n.tail2
),
li_updated AS (
	UPDATE tb_legal_dong_l t
	SET
		past_legal_dong_cd = a.old_li_cd10,
		last_updt_dtm = now(),
		last_upusr_id = (SELECT operator_id FROM params)
	FROM li_targets a
	WHERE t.legal_dong_cd = a.new_li_cd10
	  AND t.past_legal_dong_cd IS NULL
	RETURNING t.legal_dong_cd
)
SELECT
	(SELECT count(*) FROM consolidated) AS input_codes,
	(SELECT count(*) FROM upserted) AS upsert_rows_effected,
	(SELECT count(*) FROM emndn_updated) AS past_emndn_updated,
	(SELECT count(*) FROM li_updated) AS past_li_updated;

-- =========================================================
-- [STEP 3] 간단 리포트/진단(선택)
-- =========================================================
-- (1) TEMP 입력 로우 수
SELECT 'temp_rows' AS k, count(*) AS v FROM pg_temp.tmp_legal_dong_excel_csv;
-- (2) 입력 범위 내 10자리 코드 형식 오류(수정 필요)
SELECT 'invalid_legal_dong_cd' AS k, count(*) AS v
FROM pg_temp.tmp_legal_dong_excel_csv
WHERE length(regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g')) <> 10;

