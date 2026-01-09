-- 법정동 CSV(엑셀 동일 컬럼) 기반 업서트 + 과거코드(past) 반영 스크립트 (PostgreSQL / DBeaver 실행용)
--
-- 목적
-- - DBeaver에서 CSV를 "엑셀 동일 컬럼" 형태로 임포트한 뒤,
--   1) 파생 컬럼 생성(코드/명칭 정규화, rank 산정, legal_dong_nm 생성)
--   2) tb_legal_dong_l 업서트(INSERT/UPDATE)
--   3) 시행일(=cr_dt) 기준 과거법정동코드(past_legal_dong_cd) 자동 반영
-- 을 한 번에 수행한다.
--
-- 전제
-- - tb_legal_dong_l 스키마는 애플리케이션에서 이미 생성/관리 중.
-- - cr_dt/dlt_dt는 yyyyMMdd(8자리) 문자열(varchar(8))로 관리.
-- - 본 스크립트는 "CSV 범위(=CSV에 포함된 코드)"만 대상으로 업서트/매핑한다.
--
-- CSV 컬럼(엑셀과 동일)
-- - 행정동코드, 시도명, 시군구명, 읍면동명, 법정동코드, 동리명, 생성일자, 말소일자
--
-- 사용 흐름(DBeaver)
-- 0) 아래 [0. STAGING] 섹션을 먼저 실행(스테이징 테이블 생성/초기화)
-- 1) DBeaver에서 `stg_legal_dong_excel_csv` 테이블로 CSV Import 수행
-- 2) 아래 [1. MIGRATE] 섹션을 실행(업서트 + past 자동 반영)
-- 3) 마지막 [2. REPORT]에서 결과 요약/오류를 확인
--
-- 조정 포인트
-- - operator_id: 변경 이력(마지막 수정자) 표시에 사용. 기본값은 'DBEAVER'로 둔다.
--
-- 주의
-- - DBeaver는 psql의 \set, \i 를 지원하지 않는다. (DO 블록/일반 SQL만 사용)
-- - 본 스크립트는 멱등성을 최대한 보장하지만, 운영 반영 전에는 반드시 검증 DB에서 테스트 권장.

SET client_encoding = 'UTF8';

-- =========================
-- 0. STAGING (최초 1회)
-- =========================
CREATE TABLE IF NOT EXISTS stg_legal_dong_excel_csv (
	load_id        bigserial    PRIMARY KEY,
	-- 원본 컬럼(엑셀 동일)
	admin_cd_raw   text         NULL,  -- 행정동코드(참조 불필요하더라도 입력 그대로 보관)
	ctprv_nm_raw   text         NULL,  -- 시도명
	sgng_nm_raw    text         NULL,  -- 시군구명
	emndn_nm_raw   text         NULL,  -- 읍면동명
	legal_cd_raw   text         NULL,  -- 법정동코드(10자리 기대)
	li_nm_raw      text         NULL,  -- 동리명
	cr_dt_raw      text         NULL,  -- 생성일자(yyyyMMdd 또는 yyyy-mm-dd 등 허용)
	dlt_dt_raw     text         NULL,  -- 말소일자(yyyyMMdd 또는 yyyy-mm-dd 등 허용)
	loaded_at      timestamptz  NOT NULL DEFAULT now()
);

-- (선택) 매번 새 CSV로 작업할 때 스테이징을 비우고 시작한다.
-- TRUNCATE TABLE stg_legal_dong_excel_csv;

-- =========================
-- 1. MIGRATE (업서트 + past)
-- =========================
DO $$
DECLARE
	operator_id text := 'DBEAVER';
	eff_dt text;
BEGIN
	-- 1) 파생/정규화 + 중복 병합(legal_dong_cd 기준)
	--    - cr_dt: 최소값, dlt_dt: 최대값
	--    - 나머지 메타: 가장 먼저 나온(load_id 최소) 행 기준 유지
	CREATE TEMP TABLE tmp_legal_dong_consolidated AS
	WITH normalized AS (
		SELECT
			s.load_id,
			NULLIF(btrim(s.ctprv_nm_raw), '') AS ctprv_nm,
			NULLIF(btrim(s.sgng_nm_raw), '') AS sgng_nm,
			NULLIF(btrim(s.emndn_nm_raw), '') AS emndn_nm_raw,
			NULLIF(btrim(s.li_nm_raw), '') AS li_nm_raw,
			regexp_replace(coalesce(s.legal_cd_raw, ''), '[^0-9]', '', 'g') AS legal_dong_cd,
			NULLIF(regexp_replace(coalesce(s.cr_dt_raw, ''), '[^0-9]', '', 'g'), '') AS cr_dt_digits,
			NULLIF(regexp_replace(coalesce(s.dlt_dt_raw, ''), '[^0-9]', '', 'g'), '') AS dlt_dt_digits
		FROM stg_legal_dong_excel_csv s
	),
	validated AS (
		SELECT
			load_id,
			ctprv_nm,
			sgng_nm,
			-- 읍면동명 누락 케이스 보정: 상위(리 없음) 행은 동리명에 읍면동명이 들어오는 경우가 있어 fallback
			CASE
				WHEN length(legal_dong_cd) = 10 AND right(legal_dong_cd, 2) = '00'
				THEN coalesce(emndn_nm_raw, li_nm_raw)
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
			v.load_id,
			v.legal_dong_cd,
			substring(v.legal_dong_cd, 1, 2) AS ctprv_cd,
			v.ctprv_nm,
			substring(v.legal_dong_cd, 1, 5) AS sgng_cd,
			v.sgng_nm,
			substring(v.legal_dong_cd, 1, 8) AS emndn_cd,
			v.emndn_nm,
			CASE WHEN right(v.legal_dong_cd, 2) = '00' THEN NULL ELSE v.legal_dong_cd END AS li_cd,
			CASE WHEN right(v.legal_dong_cd, 2) = '00' THEN NULL ELSE v.li_nm END AS li_nm,
			-- rank: 상위(리 없음)은 emndn_cd 기준 dense_rank, 하위(리 있음)은 emndn_cd 내 row_number
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
		SELECT DISTINCT ON (d.legal_dong_cd)
			d.legal_dong_cd,
			first_value(d.legal_dong_nm) OVER w AS legal_dong_nm,
			first_value(d.ctprv_cd) OVER w AS ctprv_cd,
			first_value(d.ctprv_nm) OVER w AS ctprv_nm,
			first_value(d.sgng_cd) OVER w AS sgng_cd,
			first_value(d.sgng_nm) OVER w AS sgng_nm,
			first_value(d.emndn_cd) OVER w AS emndn_cd,
			first_value(d.emndn_nm) OVER w AS emndn_nm,
			first_value(d.li_cd) OVER w AS li_cd,
			first_value(d.li_nm) OVER w AS li_nm,
			first_value(d.rank) OVER w AS rank,
			min(d.cr_dt) OVER (PARTITION BY d.legal_dong_cd) AS cr_dt,
			max(d.dlt_dt) OVER (PARTITION BY d.legal_dong_cd) AS dlt_dt
		FROM derived d
		WINDOW w AS (PARTITION BY d.legal_dong_cd ORDER BY d.load_id)
		ORDER BY d.legal_dong_cd, d.load_id
	)
	SELECT * FROM consolidated;

	-- 2) tb_legal_dong_l 업서트
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
		now(), operator_id,
		now(), operator_id,
		CASE WHEN c.dlt_dt IS NULL THEN 'Y' ELSE NULL END
	FROM tmp_legal_dong_consolidated c
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
		last_upusr_id = operator_id;

	-- 3) 시행일(=cr_dt)별 past_legal_dong_cd 자동 반영
	--    - CSV 범위 기준: tmp_legal_dong_consolidated에 등장한 cr_dt 목록만 처리
	FOR eff_dt IN
		SELECT DISTINCT cr_dt
		FROM tmp_legal_dong_consolidated
		WHERE cr_dt IS NOT NULL AND cr_dt <> ''
	LOOP
		-- ===== emndn(읍면동/동 포함) =====
		WITH
		old_li_tails AS (
			SELECT
				substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
				array_agg(DISTINCT right(o.legal_dong_cd, 2) ORDER BY right(o.legal_dong_cd, 2)) AS old_tail2s
			FROM tb_legal_dong_l o
			WHERE o.li_cd IS NOT NULL
			  AND o.dlt_dt = eff_dt
			GROUP BY substring(o.legal_dong_cd, 1, 8)
		),
		new_li_tails AS (
			SELECT
				substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
				array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
			FROM tb_legal_dong_l n
			WHERE n.li_cd IS NOT NULL
			  AND n.cr_dt = eff_dt
			GROUP BY substring(n.legal_dong_cd, 1, 8)
		),
		old_emndn AS (
			SELECT
				o.legal_dong_cd AS old_emndn_cd10,
				o.emndn_cd AS old_emndn_cd8,
				o.ctprv_cd,
				regexp_replace(btrim(coalesce(o.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\1') AS old_base_city,
				regexp_replace(btrim(coalesce(o.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS old_emndn_root,
				btrim(coalesce(o.emndn_nm, '')) AS old_emndn_nm
			FROM tb_legal_dong_l o
			WHERE o.li_cd IS NULL
			  AND o.emndn_cd IS NOT NULL
			  AND o.dlt_dt = eff_dt
		),
		new_emndn AS (
			SELECT
				n.legal_dong_cd AS new_emndn_cd10,
				n.emndn_cd AS new_emndn_cd8,
				n.ctprv_cd,
				regexp_replace(btrim(coalesce(n.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\1') AS new_base_city,
				regexp_replace(btrim(coalesce(n.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS new_emndn_root,
				btrim(coalesce(n.emndn_nm, '')) AS new_emndn_nm
			FROM tb_legal_dong_l n
			WHERE n.li_cd IS NULL
			  AND n.emndn_cd IS NOT NULL
			  AND n.cr_dt = eff_dt
			  AND n.past_legal_dong_cd IS NULL
		),
		emndn_candidates AS (
			SELECT
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
			  ON o.ctprv_cd = n.ctprv_cd
			 AND o.old_base_city = n.new_base_city
			LEFT JOIN old_li_tails olt ON olt.old_emndn_cd8 = o.old_emndn_cd8
			LEFT JOIN new_li_tails nlt ON nlt.new_emndn_cd8 = n.new_emndn_cd8
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
				s.new_emndn_cd10,
				s.new_emndn_cd8,
				s.old_emndn_cd10 AS chosen_old_emndn_cd10,
				s.old_emndn_cd8 AS chosen_old_emndn_cd8
			FROM emndn_scored s
			WHERE s.rn = 1
			  AND s.max_score > 0
			  AND s.top_ties = 1
		)
		UPDATE tb_legal_dong_l t
		SET
			past_legal_dong_cd = m.chosen_old_emndn_cd10,
			last_updt_dtm = now(),
			last_upusr_id = operator_id
		FROM emndn_unique_map m
		WHERE t.legal_dong_cd = m.new_emndn_cd10
		  AND t.cr_dt = eff_dt
		  AND t.past_legal_dong_cd IS NULL;

		-- ===== li(리/동 하위) =====
		WITH
		map_emndn AS (
			-- 신규 읍면동 past가 이미 채워진 경우도 포함(재실행/부분실행 케이스)
			SELECT
				n.emndn_cd AS new_emndn_cd8,
				o.emndn_cd AS old_emndn_cd8
			FROM tb_legal_dong_l n
			JOIN tb_legal_dong_l o ON o.legal_dong_cd = n.past_legal_dong_cd
			WHERE n.li_cd IS NULL
			  AND n.emndn_cd IS NOT NULL
			  AND n.cr_dt = eff_dt
		),
		new_li AS (
			SELECT
				n.legal_dong_cd AS new_li_cd10,
				substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
				right(n.legal_dong_cd, 2) AS tail2
			FROM tb_legal_dong_l n
			WHERE n.li_cd IS NOT NULL
			  AND n.cr_dt = eff_dt
			  AND n.past_legal_dong_cd IS NULL
		),
		old_li AS (
			SELECT
				o.legal_dong_cd AS old_li_cd10,
				substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
				right(o.legal_dong_cd, 2) AS tail2
			FROM tb_legal_dong_l o
			WHERE o.li_cd IS NOT NULL
			  AND o.dlt_dt = eff_dt
		),
		li_targets AS (
			SELECT
				n.new_li_cd10,
				o.old_li_cd10
			FROM new_li n
			JOIN map_emndn m ON m.new_emndn_cd8 = n.new_emndn_cd8
			JOIN old_li o ON o.old_emndn_cd8 = m.old_emndn_cd8 AND o.tail2 = n.tail2
		)
		UPDATE tb_legal_dong_l t
		SET
			past_legal_dong_cd = a.old_li_cd10,
			last_updt_dtm = now(),
			last_upusr_id = operator_id
		FROM li_targets a
		WHERE t.legal_dong_cd = a.new_li_cd10
		  AND t.cr_dt = eff_dt
		  AND t.past_legal_dong_cd IS NULL;
	END LOOP;
END $$;

-- =========================
-- 2. REPORT (요약/진단)
-- =========================
-- (1) 스테이징/파생/병합 결과
SELECT 'staging_rows' AS k, count(*) AS v FROM stg_legal_dong_excel_csv;
SELECT 'derived_rows' AS k, count(*) AS v FROM tmp_legal_dong_consolidated;
SELECT 'distinct_eff_dt' AS k, count(DISTINCT cr_dt) AS v FROM tmp_legal_dong_consolidated WHERE cr_dt IS NOT NULL;

-- (2) 형식 오류(코드/일자)
SELECT
	'invalid_legal_dong_cd' AS k,
	count(*) AS v
FROM stg_legal_dong_excel_csv
WHERE length(regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g')) <> 10;

SELECT
	'invalid_cr_dt' AS k,
	count(*) AS v
FROM stg_legal_dong_excel_csv
WHERE cr_dt_raw IS NOT NULL
  AND cr_dt_raw <> ''
  AND length(regexp_replace(coalesce(cr_dt_raw, ''), '[^0-9]', '', 'g')) NOT IN (0, 8);

SELECT
	'invalid_dlt_dt' AS k,
	count(*) AS v
FROM stg_legal_dong_excel_csv
WHERE dlt_dt_raw IS NOT NULL
  AND dlt_dt_raw <> ''
  AND length(regexp_replace(coalesce(dlt_dt_raw, ''), '[^0-9]', '', 'g')) NOT IN (0, 8);

-- (3) CSV 범위 내 신규코드(past 미입력) 잔여 확인(시행일별)
SELECT
	cr_dt AS eff_dt,
	count(*) FILTER (WHERE li_cd IS NULL) AS new_emndn_cnt,
	count(*) FILTER (WHERE li_cd IS NOT NULL) AS new_li_cnt
FROM tb_legal_dong_l
WHERE past_legal_dong_cd IS NULL
  AND cr_dt IN (SELECT DISTINCT cr_dt FROM tmp_legal_dong_consolidated WHERE cr_dt IS NOT NULL)
GROUP BY cr_dt
ORDER BY cr_dt;

