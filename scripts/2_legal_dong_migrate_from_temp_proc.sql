-- 법정동 마이그레이션 프로시저 (PostgreSQL / DBeaver)
--
-- 목적
-- - DBeaver에서 CSV를 TEMP 테이블(`pg_temp.tmp_legal_dong_excel_csv`)에 Import한 뒤
--   `CALL sp_legal_dong_migrate_from_temp('DBEAVER');` 한 줄로
--   업서트 + past_legal_dong_cd 자동 반영까지 수행한다.
--
-- 전제
-- - 본 프로시저는 "TEMP 테이블 생성"을 하지 않는다(세션/정책에 따라 별도로 수행).
-- - 호출 세션에 `pg_temp.tmp_legal_dong_excel_csv`가 존재해야 한다.
-- - CSV 컬럼은 엑셀 동일 8컬럼이며 TEMP 테이블 컬럼명은 아래와 같아야 한다.
--   admin_cd_raw, ctprv_nm_raw, sgng_nm_raw, emndn_nm_raw, legal_cd_raw, li_nm_raw, cr_dt_raw, dlt_dt_raw
--
-- 사용 순서(DBeaver)
-- 1) (세션에서) CREATE TEMP TABLE tmp_legal_dong_excel_csv (...)  -- 템플릿: scripts/legal_dong_migrate_from_temp_dbeaver_template.sql [STEP 1]
-- 2) DBeaver Import Data → pg_temp.tmp_legal_dong_excel_csv
-- 3) CALL sp_legal_dong_migrate_from_temp('DBEAVER');
--
-- 출력
-- - procedure는 result set을 반환하지 않으므로, 요약은 NOTICE로 출력한다.

CREATE OR REPLACE PROCEDURE sp_legal_dong_migrate_from_temp(IN p_operator_id text DEFAULT 'kimmj5909@kfca.re.kr')
LANGUAGE plpgsql
AS $$
DECLARE
	operator_id text := coalesce(nullif(btrim(p_operator_id), ''), 'kimmj5909@kfca.re.kr');
	-- 업서트 대상 테이블(운영 실제 테이블명)
	target_table constant text := 'tb_fdis_legal_dong_cd_m';
	-- 업서트 실행 전 백업(스키마.원본테이블명_YYYYMMDD_backup)
	backup_schema constant text := 'sc_fdis_backup';
	backup_table_name text := format('%s_%s_backup', target_table, to_char(current_date, 'YYYYMMDD'));
	backup_regclass text := format('%I.%I', backup_schema, backup_table_name);
	input_codes int := 0;
	upsert_rows_effected int := 0;
	past_emndn_updated int := 0;
	past_li_updated int := 0;
BEGIN
	IF to_regclass('pg_temp.tmp_legal_dong_excel_csv') IS NULL THEN
		RAISE EXCEPTION 'TEMP table pg_temp.tmp_legal_dong_excel_csv not found in this session. Create/import CSV first.';
	END IF;

	-- [백업]
	-- - 업서트 실행 전, 기존 테이블을 sc_fdis_backup 스키마에 "당일 백업 테이블"로 1회 생성한다.
	-- - 이미 같은 날짜의 백업 테이블이 존재하면 재생성하지 않는다.
	-- - 백업은 운영 정책에 따라 DDL/로그 정책 영향이 있을 수 있어, 필요 시 이 블록을 주석 처리해서 사용한다.
	IF to_regnamespace(backup_schema) IS NULL THEN
		RAISE EXCEPTION 'backup schema not found: %', backup_schema;
	END IF;
	IF to_regclass(backup_regclass) IS NULL THEN
		EXECUTE format('CREATE TABLE %I.%I (LIKE %I INCLUDING ALL);', backup_schema, backup_table_name, target_table);
		EXECUTE format('INSERT INTO %I.%I SELECT * FROM %I;', backup_schema, backup_table_name, target_table);
	END IF;

	WITH
	params AS (SELECT operator_id AS operator_id),
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
	),
	upserted AS (
		INSERT INTO tb_fdis_legal_dong_cd_m (
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
				WHEN tb_fdis_legal_dong_cd_m.cr_dt IS NULL THEN EXCLUDED.cr_dt
				WHEN EXCLUDED.cr_dt IS NULL THEN tb_fdis_legal_dong_cd_m.cr_dt
				ELSE LEAST(tb_fdis_legal_dong_cd_m.cr_dt, EXCLUDED.cr_dt)
			END,
			dlt_dt = CASE
				WHEN tb_fdis_legal_dong_cd_m.dlt_dt IS NULL THEN EXCLUDED.dlt_dt
				WHEN EXCLUDED.dlt_dt IS NULL THEN tb_fdis_legal_dong_cd_m.dlt_dt
				ELSE GREATEST(tb_fdis_legal_dong_cd_m.dlt_dt, EXCLUDED.dlt_dt)
			END,
			use_yn = CASE
				WHEN (CASE
					WHEN tb_fdis_legal_dong_cd_m.dlt_dt IS NULL THEN EXCLUDED.dlt_dt
					WHEN EXCLUDED.dlt_dt IS NULL THEN tb_fdis_legal_dong_cd_m.dlt_dt
					ELSE GREATEST(tb_fdis_legal_dong_cd_m.dlt_dt, EXCLUDED.dlt_dt)
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
	old_li_tails AS (
		SELECT
			e.eff_dt,
			substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
			array_agg(DISTINCT right(o.legal_dong_cd, 2) ORDER BY right(o.legal_dong_cd, 2)) AS old_tail2s
		FROM eff e
		JOIN tb_fdis_legal_dong_cd_m o ON o.dlt_dt = e.eff_dt
		WHERE o.li_cd IS NOT NULL
		GROUP BY e.eff_dt, substring(o.legal_dong_cd, 1, 8)
	),
	new_li_tails AS (
		SELECT
			e.eff_dt,
			substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
			array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
		FROM eff e
		JOIN tb_fdis_legal_dong_cd_m n ON n.cr_dt = e.eff_dt
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
		JOIN tb_fdis_legal_dong_cd_m o ON o.dlt_dt = e.eff_dt
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
		JOIN tb_fdis_legal_dong_cd_m n ON n.cr_dt = e.eff_dt
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
		UPDATE tb_fdis_legal_dong_cd_m t
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
	new_li AS (
		SELECT
			n.legal_dong_cd AS new_li_cd10,
			substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
			right(n.legal_dong_cd, 2) AS tail2,
			n.cr_dt AS eff_dt
		FROM tb_fdis_legal_dong_cd_m n
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
		FROM tb_fdis_legal_dong_cd_m o
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
		UPDATE tb_fdis_legal_dong_cd_m t
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
		(SELECT count(*) FROM li_updated) AS past_li_updated
	INTO input_codes, upsert_rows_effected, past_emndn_updated, past_li_updated;
END;
$$;
