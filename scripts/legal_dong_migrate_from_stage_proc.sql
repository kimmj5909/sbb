-- 법정동 CSV(엑셀 동일 컬럼) → 스테이징 Import → 업서트 + past 반영 (PostgreSQL / 운영: INSERT/UPDATE만)
--
-- 운영 제약
-- - 운영 환경에서 테이블 생성/변경(DDL)이 불가한 경우가 있어, 본 스크립트는 "데이터 변경(INSERT/UPDATE)"만 수행한다.
-- - 단, 사용자가 "프로시저/함수 생성 가능"이라고 했으므로, 실행 편의를 위해 PROCEDURE는 제공한다.
--
-- 전제(운영에 사전 준비 필요)
-- - 아래 스테이징 테이블은 운영 DB에 "미리" 존재해야 한다(이 스크립트는 생성하지 않음).
--   테이블명: stg_legal_dong_excel_csv
--   컬럼(ASCII 권장, DBeaver Import 매핑용):
--     admin_cd_raw  text,
--     ctprv_nm_raw  text,
--     sgng_nm_raw   text,
--     emndn_nm_raw  text,
--     legal_cd_raw  text,
--     li_nm_raw     text,
--     cr_dt_raw     text,
--     dlt_dt_raw    text
--
-- 사용 흐름(DBeaver)
-- 1) CSV Import → stg_legal_dong_excel_csv에 적재 (CSV 컬럼은 엑셀과 동일)
-- 2) CALL sp_legal_dong_migrate_from_stage('DBEAVER');
-- 3) 필요 시 stg_legal_dong_excel_csv 비움(TRUNCATE)은 운영 정책에 맞게 별도 수행
--
-- 범위
-- - 업서트/과거코드 반영은 "스테이징에 포함된 legal_dong_cd" 범위로만 수행한다.
--
-- 주의
-- - cr_dt/dlt_dt는 yyyyMMdd(8자리) 문자열로 관리한다. (yyyy-mm-dd 등은 숫자만 추출)
-- - past 반영은 안전 우선:
--   - old.dlt_dt = 시행일인 말소 코드만 과거 후보
--   - new.cr_dt = 시행일이고 past가 NULL인 신규 코드만 대상
--   - 최고 점수 유니크(top_ties=1)인 케이스만 자동 반영

CREATE OR REPLACE PROCEDURE sp_legal_dong_migrate_from_stage(IN p_operator_id text DEFAULT 'DBEAVER')
LANGUAGE plpgsql
AS $$
DECLARE
	operator_id text := coalesce(nullif(btrim(p_operator_id), ''), 'DBEAVER');
	eff_dt text;
BEGIN
	-- 1) 스테이징 → 파생/병합(legal_dong_cd 기준)
	--    - 병합 규칙: cr_dt 최소 / dlt_dt 최대 / 메타는 load 순서 의존 없이 임의 1행(동일 코드 전제)
	WITH normalized AS (
		SELECT
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
			ctprv_nm,
			sgng_nm,
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
	)
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
		last_upusr_id = operator_id;

	-- 2) past 자동 반영 (스테이징 범위 내 신규 코드만)
	FOR eff_dt IN
		SELECT DISTINCT
			CASE WHEN length(regexp_replace(coalesce(cr_dt_raw, ''), '[^0-9]', '', 'g')) = 8
				THEN regexp_replace(coalesce(cr_dt_raw, ''), '[^0-9]', '', 'g')
				ELSE NULL
			END AS eff_dt
		FROM stg_legal_dong_excel_csv
	LOOP
		IF eff_dt IS NULL OR eff_dt = '' THEN
			CONTINUE;
		END IF;

		-- emndn past
		WITH
		stage_codes AS (
			SELECT DISTINCT regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g') AS legal_dong_cd
			FROM stg_legal_dong_excel_csv
			WHERE length(regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g')) = 10
		),
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
			JOIN stage_codes s ON s.legal_dong_cd = n.legal_dong_cd
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

		-- li past (emndn past가 확정된 신규만)
		WITH
		stage_codes AS (
			SELECT DISTINCT regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g') AS legal_dong_cd
			FROM stg_legal_dong_excel_csv
			WHERE length(regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g')) = 10
		),
		map_emndn AS (
			SELECT
				n.emndn_cd AS new_emndn_cd8,
				o.emndn_cd AS old_emndn_cd8
			FROM tb_legal_dong_l n
			JOIN tb_legal_dong_l o ON o.legal_dong_cd = n.past_legal_dong_cd
			JOIN stage_codes s ON s.legal_dong_cd = n.legal_dong_cd
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
			JOIN stage_codes s ON s.legal_dong_cd = n.legal_dong_cd
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
END;
$$;

