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
-- - 본 환경에서는 PROCEDURE OUT 파라미터를 지원하지 않아, 요약은 NOTICE 및 TEMP 결과 테이블로 제공한다.
-- - 결과 확인:
--   - 건수 요약: SELECT * FROM pg_temp.tmp_legal_dong_migrate_result;
--   - 미리보기: SELECT * FROM pg_temp.tmp_legal_dong_migrate_preview;  (p_preview_only=true 실행 시)
-- - 호출 예시
--   - 기본(미리보기): CALL sp_legal_dong_migrate_from_temp();
--   - 실테이블 적용: CALL sp_legal_dong_migrate_from_temp(false);  -- 작성자 기본값(ADMIN) 자동 적용
--   - 실테이블 적용(작성자 지정): CALL sp_legal_dong_migrate_from_temp(false, 'SOMEONE');

CREATE OR REPLACE PROCEDURE sp_legal_dong_migrate_from_temp(
	-- (선택) 미리보기 모드:
	-- - true  : 실테이블을 건드리지 않고, 실테이블을 복사한 TEMP 테이블에서 동일 로직을 수행한 뒤
	--          결과 확인용 TEMP 테이블(`pg_temp.tmp_legal_dong_migrate_preview`)을 생성한다.
	--          프로시저 종료 시 shadow TEMP 테이블은 DROP하여 같은 세션에서 실수로 TEMP에 반영되는 것을 방지한다.
	-- - false : 기존 방식대로 실테이블에 적용한다(백업은 수동 수행).
	p_preview_only boolean DEFAULT true,
	-- 작성자 기본값은 정책상 ADMIN으로 고정한다.
	-- - 실테이블 적용(p_preview_only=false) 시에도 작성자는 생략 가능(기본값 ADMIN 적용).
	p_operator_id text DEFAULT 'ADMIN'
)
LANGUAGE plpgsql
AS $$
DECLARE
	-- PL/pgSQL 변수명이 SQL 컬럼명과 혼동되어 42703(column does not exist)이 발생할 수 있어 `v_` 접두사로 분리한다.
	v_operator_id text := coalesce(nullif(btrim(p_operator_id), ''), 'ADMIN');
	-- 업서트 대상 테이블(운영 실제 테이블명)
	target_table constant text := 'tb_fdis_legal_dong_cd_m';
	input_codes int := 0;
	upsert_rows_effected int := 0;
	past_emndn_updated int := 0;
	past_li_updated int := 0;
	emndn_manual_rank_updated_cnt int := 0;
	li_manual_rank_updated_cnt int := 0;
	-- past 매핑이 동률(top_ties>1)로 스킵되는 케이스 진단용
	emndn_tie_cnt int := 0;
	-- 진단용 카운트
	eff_dt_count int := 0;
	old_eff_mapped_count int := 0;
	old_eff_dt_min text := NULL;
	old_eff_dt_max text := NULL;
	old_emndn_cnt int := 0;
	new_emndn_cnt int := 0;
	emndn_candidates_cnt int := 0;
	emndn_unique_map_cnt int := 0;
	old_li_cnt int := 0;
	new_li_cnt int := 0;
	li_targets_cnt int := 0;
	-- 주소 정규화 기반 매핑(stage) 진단용
	addr_map_candidates_cnt int := 0;
	addr_map_updated_cnt int := 0;
	-- 미리보기 모드에서 shadow TEMP 테이블을 만들기 위해, 실테이블 regclass를 확보한다.
	real_target regclass := 'tb_fdis_legal_dong_cd_m'::regclass;
BEGIN
	IF to_regclass('pg_temp.tmp_legal_dong_excel_csv') IS NULL THEN
		RAISE EXCEPTION 'TEMP table pg_temp.tmp_legal_dong_excel_csv not found in this session. Create/import CSV first.';
	END IF;

	-- 입력 범위(스테이징) 코드 목록: 이후 매핑/미리보기 대상 필터로 공통 사용한다.
	-- - 프로시저 실행 전/후 어디서든 조회 가능하도록 TEMP로 만든다.
	DROP TABLE IF EXISTS pg_temp.tmp_legal_dong_migrate_stage_codes;
	CREATE TEMP TABLE tmp_legal_dong_migrate_stage_codes AS
	SELECT DISTINCT
		regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g') AS legal_dong_cd
	FROM pg_temp.tmp_legal_dong_excel_csv
	WHERE length(regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g')) = 10;

	-- (추가) '...구' 시군구가 포함된 입력의 경우, sgng_cd||'00000'(시군구 레벨) 코드를 stage_codes에도 포함시킨다.
	-- - CSV에 하위 읍면동/리만 있고 시군구 레벨 코드가 누락된 경우, 업서트 단계에서 시군구 레벨 행을 생성해도
	--   미리보기/스테이징 조회(=stage_codes 조인)에서는 "추가 안됨"처럼 보일 수 있어 진단/확인을 돕기 위함.
	INSERT INTO pg_temp.tmp_legal_dong_migrate_stage_codes (legal_dong_cd)
	SELECT DISTINCT
		(substring(code.legal_dong_cd, 1, 5) || '00000') AS legal_dong_cd
	FROM (
		SELECT
			regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g') AS legal_dong_cd,
			btrim(coalesce(sgng_nm_raw, '')) AS sgng_nm
		FROM pg_temp.tmp_legal_dong_excel_csv
	) code
	LEFT JOIN pg_temp.tmp_legal_dong_migrate_stage_codes s
	  ON s.legal_dong_cd = (substring(code.legal_dong_cd, 1, 5) || '00000')
	WHERE length(code.legal_dong_cd) = 10
	  AND code.sgng_nm ~ '[가-힣]+구$'
	  AND s.legal_dong_cd IS NULL;

		IF p_preview_only THEN
		-- =========================================================
		-- [미리보기 모드] shadow TEMP 테이블 준비
		-- =========================================================
		-- 핵심 아이디어
		-- - PostgreSQL은 `pg_temp` 스키마가 search_path 선두에 오므로,
		--   동일한 이름의 TEMP 테이블을 만들면 실테이블을 shadowing 할 수 있다.
		-- - 아래에서는 `pg_temp.tb_fdis_legal_dong_cd_m`를 생성/복사한 뒤,
		--   이후의 모든 `tb_fdis_legal_dong_cd_m` 참조가 TEMP 테이블을 가리키게 된다.
		-- - 프로시저 종료 직전 shadow TEMP 테이블을 DROP해서,
		--   같은 세션에서 실수로 TEMP에만 적용되는 사고를 방지한다.
			EXECUTE format('DROP TABLE IF EXISTS pg_temp.%I;', target_table);
			DROP TABLE IF EXISTS pg_temp.tmp_legal_dong_migrate_preview;

		EXECUTE format('CREATE TEMP TABLE %I (LIKE %s INCLUDING ALL);', target_table, real_target);
		EXECUTE format('INSERT INTO %I SELECT * FROM %s;', target_table, real_target);

		RAISE NOTICE '[preview] shadow TEMP table created: pg_temp.%', target_table;
		RAISE NOTICE '[preview] after CALL, check: SELECT legal_dong_cd, legal_dong_nm, rank, past_legal_dong_cd, cr_dt, dlt_dt FROM pg_temp.tmp_legal_dong_migrate_preview;';
	END IF;

	WITH
	params AS (SELECT v_operator_id AS operator_id),
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
			-- 정책: CSV의 읍면동명(emndn_nm_raw)은 행정동명(예: 반송1동/병점2동)일 수 있으므로 사용하지 않는다.
			-- - 상위코드(끝 '00')의 법정동명은 동리명(li_nm_raw)을 사용한다.
			-- - 리(끝 '00' 아님)의 읍면동명은 같은 emndn_cd의 상위코드에서 파생한다(derived에서 조인).
			CASE
				WHEN length(legal_dong_cd) = 10 AND right(legal_dong_cd, 2) = '00'
				THEN NULLIF(btrim(li_nm_raw), '')
				ELSE NULL
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
		parent_emndn AS (
			-- 상위코드(끝 '00')에서 emndn_cd(앞 8자리) → 법정 읍면동명 매핑을 만든다.
			SELECT
				substring(v.legal_dong_cd, 1, 8) AS emndn_cd,
				max(v.emndn_nm) AS emndn_nm
			FROM validated v
			WHERE v.legal_dong_cd IS NOT NULL
			  AND right(v.legal_dong_cd, 2) = '00'
			  AND v.emndn_nm IS NOT NULL
			GROUP BY substring(v.legal_dong_cd, 1, 8)
		),
		derived AS (
			SELECT
				v.legal_dong_cd,
				substring(v.legal_dong_cd, 1, 2) AS ctprv_cd,
				v.ctprv_nm,
				substring(v.legal_dong_cd, 1, 5) AS sgng_cd,
				v.sgng_nm,
				substring(v.legal_dong_cd, 1, 8) AS emndn_cd,
				CASE
					WHEN right(v.legal_dong_cd, 2) = '00'
					THEN v.emndn_nm
					ELSE p.emndn_nm
				END AS emndn_nm,
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
			LEFT JOIN parent_emndn p ON p.emndn_cd = substring(v.legal_dong_cd, 1, 8)
			WHERE v.legal_dong_cd IS NOT NULL
		),
		sgng_derived AS (
			-- =========================================================
			-- (추가) 시군구 레벨(끝 '00000') 행 생성
			-- =========================================================
			-- 배경
			-- - 일부 CSV(특히 델타/부분 추출)에는 '화성시 만세구/효행구/병점구/동탄구' 같은 신규 시군구 레벨 코드
			--   (sgng_cd||'00000')가 포함되지 않고, 하위 읍면동/리 코드만 포함될 수 있다.
			-- - 이 경우 운영 테이블에 시군구 레벨 행이 누락되어 조회/검색/매핑에서 혼선이 생긴다.
			--
			-- 처리
			-- - 입력에 등장한 sgng_cd 중 sgng_nm이 '...구'로 끝나는 케이스에 한해,
			--   sgng_cd||'00000' 레벨 행을 추가로 생성해 업서트 대상에 포함시킨다.
			SELECT
				(substring(v.legal_dong_cd, 1, 5) || '00000') AS legal_dong_cd,
				substring(v.legal_dong_cd, 1, 2) AS ctprv_cd,
				max(v.ctprv_nm) AS ctprv_nm,
				substring(v.legal_dong_cd, 1, 5) AS sgng_cd,
				max(v.sgng_nm) AS sgng_nm,
				(substring(v.legal_dong_cd, 1, 5) || '000') AS emndn_cd,
				NULL::text AS emndn_nm,
				NULL::text AS li_cd,
				NULL::text AS li_nm,
				0::int AS rank,
				min(v.cr_dt) AS cr_dt,
				max(v.dlt_dt) AS dlt_dt,
				concat_ws(' ', max(v.ctprv_nm), max(v.sgng_nm)) AS legal_dong_nm
			FROM validated v
			WHERE v.legal_dong_cd IS NOT NULL
			  -- '...구' 신규 시군구만 대상(기존 '화성시' 같은 시/군은 CSV에 직접 포함되는 경우가 많아 불필요한 스냅샷 갱신 위험을 줄임)
			  AND btrim(coalesce(v.sgng_nm, '')) ~ '[가-힣]+구$'
			GROUP BY substring(v.legal_dong_cd, 1, 5), substring(v.legal_dong_cd, 1, 2)
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
		FROM (
			SELECT * FROM derived
			UNION ALL
			SELECT * FROM sgng_derived
		) d
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
				-- 정책: 기존 데이터의 주소/명칭/rank는 수정하지 않는다.
				-- - 신규 코드 INSERT는 허용하되, 기존 코드(legal_dong_cd 충돌) 갱신은 일자/사용여부/최종수정자만 반영한다.
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
	old_eff_map AS (
		-- 과거 스냅샷 시행일 매핑
		-- - 원칙: old dlt_dt = eff_dt
		-- - 보강: 해당 eff_dt로 말소된 데이터가 없으면, eff_dt보다 작은 dlt_dt 중 가장 큰 값(가장 가까운 과거)을 사용
		SELECT
			e.eff_dt,
			CASE
				WHEN EXISTS (
					SELECT 1
					FROM tb_fdis_legal_dong_cd_m o
					WHERE nullif(btrim(coalesce(o.dlt_dt, '')), '') = e.eff_dt
				) THEN e.eff_dt
				ELSE (
					SELECT max(nullif(btrim(coalesce(o.dlt_dt, '')), ''))
					FROM tb_fdis_legal_dong_cd_m o
					WHERE nullif(btrim(coalesce(o.dlt_dt, '')), '') IS NOT NULL
					  AND nullif(btrim(coalesce(o.dlt_dt, '')), '') < e.eff_dt
				)
			END AS old_eff_dt
		FROM eff e
	),
	stage_codes AS (
		SELECT legal_dong_cd FROM consolidated
	),
	old_li_tails AS (
		SELECT
			e.eff_dt,
			substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
			array_agg(DISTINCT right(o.legal_dong_cd, 2) ORDER BY right(o.legal_dong_cd, 2)) AS old_tail2s
		FROM old_eff_map e
		JOIN tb_fdis_legal_dong_cd_m o ON nullif(btrim(coalesce(o.dlt_dt, '')), '') = e.old_eff_dt
		-- 빈문자('')는 NULL과 동일하게 취급
		WHERE nullif(btrim(coalesce(o.li_cd, '')), '') IS NOT NULL
		GROUP BY e.eff_dt, substring(o.legal_dong_cd, 1, 8)
	),
	new_li_tails AS (
		SELECT
			e.eff_dt,
			substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
			array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
		FROM eff e
		JOIN tb_fdis_legal_dong_cd_m n ON nullif(btrim(coalesce(n.cr_dt, '')), '') = e.eff_dt
		-- 빈문자('')는 NULL과 동일하게 취급
		WHERE nullif(btrim(coalesce(n.li_cd, '')), '') IS NOT NULL
		GROUP BY e.eff_dt, substring(n.legal_dong_cd, 1, 8)
	),
	old_emndn AS (
		SELECT
			e.eff_dt,
			o.legal_dong_cd AS old_emndn_cd10,
			o.emndn_cd AS old_emndn_cd8,
			o.ctprv_cd,
			-- (참고) 시군구명(sgng_nm)에 신규로 '...구'가 붙는 케이스(예: '화성시' → '화성시 만세구')가 많아,
			-- 과거코드 매핑 점수 계산 시 활용할 수 있도록 '구 제거 전/후' 정규화 값을 함께 만든다.
			regexp_replace(regexp_replace(btrim(coalesce(o.sgng_nm, '')), '\\s+', '', 'g'), '[가-힣]+구$', '') AS old_sgng_city_only_norm,
			regexp_replace(btrim(coalesce(o.sgng_nm, '')), '\\s+', '', 'g') AS old_sgng_norm,
			regexp_replace(btrim(coalesce(o.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\1') AS old_base_city,
			regexp_replace(btrim(coalesce(o.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS old_emndn_root,
			btrim(coalesce(o.emndn_nm, '')) AS old_emndn_nm
		FROM old_eff_map e
		JOIN tb_fdis_legal_dong_cd_m o ON nullif(btrim(coalesce(o.dlt_dt, '')), '') = e.old_eff_dt
		WHERE nullif(btrim(coalesce(o.li_cd, '')), '') IS NULL
		  AND nullif(btrim(coalesce(o.emndn_cd, '')), '') IS NOT NULL
		  -- 시군구(예: 4159000000 = '화성시') 레벨의 10자리 코드는 emndn_cd8의 읍면동 3자리가 '000'이다.
		  -- - 이런 코드는 읍면동 매핑 후보로 포함되면, 구 신설 등으로 sgng_nm이 변할 때
		  --   '새솔동' 같은 실제 읍면동이 과거 시군구 코드로 잘못 매핑될 수 있다.
		  -- - 따라서 past 매핑 후보군에서는 제외한다.
		  AND right(o.emndn_cd, 3) <> '000'
	),
	new_emndn AS (
		SELECT
			e.eff_dt,
			n.legal_dong_cd AS new_emndn_cd10,
			n.emndn_cd AS new_emndn_cd8,
			n.ctprv_cd,
			regexp_replace(regexp_replace(btrim(coalesce(n.sgng_nm, '')), '\\s+', '', 'g'), '[가-힣]+구$', '') AS new_sgng_city_only_norm,
			regexp_replace(btrim(coalesce(n.sgng_nm, '')), '\\s+', '', 'g') AS new_sgng_norm,
			regexp_replace(btrim(coalesce(n.sgng_nm, '')), '.*?([가-힣]+)(시|군).*$', '\\1') AS new_base_city,
			regexp_replace(btrim(coalesce(n.emndn_nm, '')), '(읍|면|동|리|가)$', '') AS new_emndn_root,
			btrim(coalesce(n.emndn_nm, '')) AS new_emndn_nm
		FROM eff e
		JOIN tb_fdis_legal_dong_cd_m n ON nullif(btrim(coalesce(n.cr_dt, '')), '') = e.eff_dt
		JOIN stage_codes s ON s.legal_dong_cd = n.legal_dong_cd
		WHERE nullif(btrim(coalesce(n.li_cd, '')), '') IS NULL
		  AND nullif(btrim(coalesce(n.emndn_cd, '')), '') IS NOT NULL
		  -- 신규 데이터에도 시군구(읍면동 000) 레벨 코드가 포함될 수 있어, 읍면동 past 매핑 대상에서 제외한다.
		  AND right(n.emndn_cd, 3) <> '000'
		  -- past_legal_dong_cd는 NULL 뿐 아니라 공백/특수문자 등이 섞인 "사실상 미설정" 값이 있을 수 있어,
		  -- '숫자만 남겼을 때 비어있으면 미설정'으로 판정한다.
		  -- - 예: 화면상 빈값처럼 보이지만 실제로는 NBSP/제로폭 공백 등으로 인해 btrim이 실패하는 케이스 방지
		  AND nullif(regexp_replace(coalesce(n.past_legal_dong_cd, ''), '[^0-9]', '', 'g'), '') IS NULL
	),
	emndn_manual_map AS (
		-- =========================================================
		-- [관리자(수동) past 매핑 반영 - 읍면동]
		-- =========================================================
		-- 배경
		-- - 관리자 화면(`/admin/legal-dong/search`)에서 `past_legal_dong_cd`를 수동으로 입력하는 케이스가 있다.
		-- - 기존 로직은 신규 읍면동의 past_legal_dong_cd가 NULL일 때만 자동 매핑을 수행하므로,
		--   이미 past가 채워진 행은 "읍면동 매핑 목록(emndn_unique_map)"에 포함되지 않아 리(li) 매핑이 연쇄적으로 스킵될 수 있다.
		--
		-- 처리
		-- - 스테이징 범위(stage_codes) 내에서 past_legal_dong_cd가 이미 채워진 읍면동 행을 찾아
		--   `emndn_unique_map`에 포함시켜 리 매핑까지 이어지도록 한다.
		-- - 또한 rank는 "과거 코드의 rank를 신규 코드에 그대로 적용"하는 정책을 유지하기 위해,
		--   별도의 UPDATE 단계에서 past 코드의 rank를 신규 코드에 복사한다.
		SELECT
			e.eff_dt,
			n.legal_dong_cd AS new_emndn_cd10,
			n.emndn_cd AS new_emndn_cd8,
			n.past_legal_dong_cd AS chosen_old_emndn_cd10,
			substring(n.past_legal_dong_cd, 1, 8) AS chosen_old_emndn_cd8
		FROM eff e
		JOIN old_eff_map om ON om.eff_dt = e.eff_dt AND om.old_eff_dt IS NOT NULL
		JOIN tb_fdis_legal_dong_cd_m n ON nullif(btrim(coalesce(n.cr_dt, '')), '') = e.eff_dt
		JOIN stage_codes s ON s.legal_dong_cd = n.legal_dong_cd
		JOIN tb_fdis_legal_dong_cd_m o ON o.legal_dong_cd = n.past_legal_dong_cd
		WHERE nullif(btrim(coalesce(n.li_cd, '')), '') IS NULL
		  AND nullif(btrim(coalesce(n.emndn_cd, '')), '') IS NOT NULL
		  AND n.past_legal_dong_cd ~ '^[0-9]{10}$'
		  AND nullif(btrim(coalesce(o.li_cd, '')), '') IS NULL
		  AND nullif(btrim(coalesce(o.dlt_dt, '')), '') = om.old_eff_dt
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
					-- (화성시 구 신설 대응) 시군구명이 '화성시' → '화성시 만세구'처럼 바뀌는 케이스에서
					-- '구'를 제거한 시군구명 기준이 동일하면 추가 점수를 부여해 동률/오매칭을 줄인다.
					+ CASE WHEN o.old_sgng_city_only_norm <> '' AND o.old_sgng_city_only_norm = n.new_sgng_city_only_norm THEN 10 ELSE 0 END
					-- 시군구명이 완전히 동일(공백 제거 기준)하면 추가 가점
					+ CASE WHEN o.old_sgng_norm <> '' AND o.old_sgng_norm = n.new_sgng_norm THEN 5 ELSE 0 END
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
		-- past_legal_dong_cd 매핑 규칙(읍면동)
		-- - 기존에는 최고 점수 후보가 유니크(top_ties=1)일 때만 반영했다.
		-- - 하지만 실제 데이터에서는 표기 차이 등으로 최고 점수가 동률이 되는 경우가 있어,
		--   top_ties>1이면 past_legal_dong_cd가 전부 NULL로 남는 문제가 생길 수 있다.
		-- - 따라서 동률인 경우에도 `row_number() ... ORDER BY score DESC, old_emndn_cd10`의 tie-break 규칙에 따라
		--   old_emndn_cd10 최소값 1건을 선택해 결정적으로 매핑한다.
		SELECT
			s.eff_dt,
			s.new_emndn_cd10,
			s.new_emndn_cd8,
			s.old_emndn_cd10 AS chosen_old_emndn_cd10,
			s.old_emndn_cd8 AS chosen_old_emndn_cd8
			FROM emndn_scored s
			WHERE s.rn = 1
			  AND s.max_score > 0
			UNION ALL
			-- 수동(past_legal_dong_cd 직접 입력) 매핑은 자동 점수 계산과 무관하게 우선 반영한다.
			-- - new_emndn은 past가 NULL인 행만 포함하므로 중복은 발생하지 않는다.
			SELECT
				m.eff_dt,
				m.new_emndn_cd10,
				m.new_emndn_cd8,
				m.chosen_old_emndn_cd10,
				m.chosen_old_emndn_cd8
			FROM emndn_manual_map m
		),
	emndn_ties AS (
		SELECT count(*)::int AS cnt
		FROM emndn_scored
		WHERE rn = 1
		  AND max_score > 0
		  AND top_ties > 1
	),
	emndn_updated AS (
		UPDATE tb_fdis_legal_dong_cd_m t
		SET
			past_legal_dong_cd = m.chosen_old_emndn_cd10,
			-- rank 적용 방식 변경:
			-- - 기존 데이터(tb_fdis_legal_dong_cd_m)의 rank는 절대 수정하지 않는다.
			-- - 신규 코드에 대해서만, 매핑된 과거 코드의 rank 값을 그대로 복사해서 적용한다.
			-- - past 매핑이 되지 않는(과거 코드가 없거나 점수가 0인) 신규 코드는 기존 산정값(INSERT 시 값)을 유지한다.
			rank = COALESCE(o.rank, t.rank),
			last_updt_dtm = now(),
			last_upusr_id = (SELECT operator_id FROM params)
		FROM emndn_unique_map m
		LEFT JOIN tb_fdis_legal_dong_cd_m o ON o.legal_dong_cd = m.chosen_old_emndn_cd10
		WHERE t.legal_dong_cd = m.new_emndn_cd10
		  AND t.cr_dt = m.eff_dt
		  -- 과거코드가 공백/비숫자 혼입으로 "사실상 미설정"인 케이스도 자동 매핑 대상으로 포함한다.
		  AND nullif(regexp_replace(coalesce(t.past_legal_dong_cd, ''), '[^0-9]', '', 'g'), '') IS NULL
		RETURNING t.legal_dong_cd
	),
	emndn_manual_rank_updated AS (
		-- past가 이미 채워진(수동 입력) 신규 읍면동에 대해서도 rank는 과거 코드 기준으로 맞춘다.
		UPDATE tb_fdis_legal_dong_cd_m t
		SET
			rank = COALESCE(o.rank, t.rank),
			last_updt_dtm = now(),
			last_upusr_id = (SELECT operator_id FROM params)
		FROM emndn_manual_map m
		JOIN tb_fdis_legal_dong_cd_m o ON o.legal_dong_cd = m.chosen_old_emndn_cd10
		WHERE t.legal_dong_cd = m.new_emndn_cd10
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
		WHERE nullif(btrim(coalesce(n.li_cd, '')), '') IS NOT NULL
		  AND n.cr_dt IN (SELECT eff_dt FROM eff)
		  -- past가 공백/비숫자 혼입으로 "사실상 미설정"인 케이스도 자동 매핑 대상으로 포함한다.
		  AND nullif(regexp_replace(coalesce(n.past_legal_dong_cd, ''), '[^0-9]', '', 'g'), '') IS NULL
	),
	old_li AS (
		SELECT
			o.legal_dong_cd AS old_li_cd10,
			substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd8,
			right(o.legal_dong_cd, 2) AS tail2,
			o.dlt_dt AS eff_dt
		FROM tb_fdis_legal_dong_cd_m o
		WHERE nullif(btrim(coalesce(o.li_cd, '')), '') IS NOT NULL
		  AND nullif(btrim(coalesce(o.dlt_dt, '')), '') IN (SELECT old_eff_dt FROM old_eff_map WHERE old_eff_dt IS NOT NULL)
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
			-- rank 적용 방식 변경(리):
			-- - 신규 리 코드는 매핑된 과거 리 코드의 rank를 그대로 복사한다.
			rank = COALESCE(o.rank, t.rank),
			last_updt_dtm = now(),
			last_upusr_id = (SELECT operator_id FROM params)
		FROM li_targets a
		LEFT JOIN tb_fdis_legal_dong_cd_m o ON o.legal_dong_cd = a.old_li_cd10
		WHERE t.legal_dong_cd = a.new_li_cd10
		  -- 과거코드가 공백/비숫자 혼입으로 "사실상 미설정"인 케이스도 자동 매핑 대상으로 포함한다.
		  AND nullif(regexp_replace(coalesce(t.past_legal_dong_cd, ''), '[^0-9]', '', 'g'), '') IS NULL
		RETURNING t.legal_dong_cd
	),
	li_manual_rank_updated AS (
		-- past가 이미 채워진(수동 입력) 신규 리에 대해서도 rank는 과거 코드 기준으로 맞춘다.
		UPDATE tb_fdis_legal_dong_cd_m t
		SET
			rank = COALESCE(o.rank, t.rank),
			last_updt_dtm = now(),
			last_upusr_id = (SELECT operator_id FROM params)
		FROM stage_codes s
		JOIN tb_fdis_legal_dong_cd_m o ON true
		WHERE t.legal_dong_cd = s.legal_dong_cd
		  AND o.legal_dong_cd = regexp_replace(coalesce(t.past_legal_dong_cd, ''), '[^0-9]', '', 'g')
		  AND nullif(btrim(coalesce(t.li_cd, '')), '') IS NOT NULL
		  AND regexp_replace(coalesce(t.past_legal_dong_cd, ''), '[^0-9]', '', 'g') ~ '^[0-9]{10}$'
		RETURNING t.legal_dong_cd
	)
	SELECT
		(SELECT count(*) FROM consolidated) AS input_codes,
		(SELECT count(*) FROM upserted) AS upsert_rows_effected,
		(SELECT count(*) FROM emndn_updated) AS past_emndn_updated,
		(SELECT count(*) FROM li_updated) AS past_li_updated,
		(SELECT count(*) FROM emndn_manual_rank_updated) AS emndn_manual_rank_updated_cnt,
		(SELECT count(*) FROM li_manual_rank_updated) AS li_manual_rank_updated_cnt,
		(SELECT cnt FROM emndn_ties) AS emndn_tie_cnt,
		(SELECT count(*) FROM eff) AS eff_dt_count,
		(SELECT count(*) FROM old_eff_map WHERE old_eff_dt IS NOT NULL) AS old_eff_mapped_count,
		(SELECT min(old_eff_dt) FROM old_eff_map) AS old_eff_dt_min,
		(SELECT max(old_eff_dt) FROM old_eff_map) AS old_eff_dt_max,
		(SELECT count(*) FROM old_emndn) AS old_emndn_cnt,
		(SELECT count(*) FROM new_emndn) AS new_emndn_cnt,
		(SELECT count(*) FROM emndn_candidates) AS emndn_candidates_cnt,
		(SELECT count(*) FROM emndn_unique_map) AS emndn_unique_map_cnt,
		(SELECT count(*) FROM old_li) AS old_li_cnt,
		(SELECT count(*) FROM new_li) AS new_li_cnt,
		(SELECT count(*) FROM li_targets) AS li_targets_cnt
		INTO
			input_codes, upsert_rows_effected, past_emndn_updated, past_li_updated, emndn_manual_rank_updated_cnt, li_manual_rank_updated_cnt, emndn_tie_cnt,
			eff_dt_count, old_eff_mapped_count, old_eff_dt_min, old_eff_dt_max,
			old_emndn_cnt, new_emndn_cnt, emndn_candidates_cnt, emndn_unique_map_cnt,
			old_li_cnt, new_li_cnt, li_targets_cnt;

		-- =========================================================
		-- (추가) 주소 정규화 기반 past_legal_dong_cd / rank 매핑(stage → 적용)
		-- =========================================================
		-- 전제
		-- - 운영 데이터에 `dlt_dt` 스냅샷이 없으면(대부분 NULL), "신규 cr_dt = 기존 dlt_dt" 방식은 매핑이 0건이 된다.
		-- - 대신 스테이징 범위(입력 CSV로 생성된 stage_codes)의 신규 코드(new_cd)에 대해,
		--   주소를 정규화해서(공백 제거 + '...구' 제거 + '제1동/1동' → '동') 기존 코드(old_cd)를 찾고
		--   past_legal_dong_cd 및 rank(과거 rank 계승)를 반영한다.
		DROP TABLE IF EXISTS pg_temp.tmp_legal_dong_past_map_stage;
		CREATE TEMP TABLE tmp_legal_dong_past_map_stage AS
		WITH
		new_rows AS (
			SELECT
				t.legal_dong_cd AS new_cd,
				t.legal_dong_nm AS new_nm,
				nullif(btrim(coalesce(t.cr_dt, '')), '') AS new_cr_dt,
				-- 공백/특수문자(유니코드 공백 포함) 차이를 제거하기 위해 "한글/숫자만" 남긴다.
				-- - 주의: 단순 `[가-힣]+구` 제거는 그리디 매칭으로 문자열 앞부분까지 통째로 제거될 수 있다.
				-- - 의도는 "시/군 다음에 새로 붙은 구"만 제거하는 것이므로, `...시|군 + ...구` 패턴만 제거한다.
				regexp_replace(
					regexp_replace(
						regexp_replace(btrim(coalesce(t.legal_dong_nm, '')), '[^0-9가-힣]', '', 'g'),
						'([가-힣]+(시|군))([가-힣]+구)', E'\\1', 'g'
					),
					E'(제)?\\d+동$', '동'
				) AS nm_norm
			FROM tb_fdis_legal_dong_cd_m t
			JOIN pg_temp.tmp_legal_dong_migrate_stage_codes s ON s.legal_dong_cd = t.legal_dong_cd
			WHERE
			  -- past는 NULL 뿐 아니라 공백/비숫자 혼입으로 "사실상 미설정"인 케이스가 있어 동일하게 취급한다.
			  nullif(regexp_replace(coalesce(t.past_legal_dong_cd, ''), '[^0-9]', '', 'g'), '') IS NULL
			  -- 신규/기존 분리 기준(테스트 쿼리와 동일 의도, 공백 유무 무관)
			  -- - 유니코드 공백(NBSP/ZWSP)까지 제거한 후 '...구' 포함 여부를 판정한다.
			  AND regexp_replace(btrim(coalesce(t.legal_dong_nm, '')), '[^0-9가-힣]', '', 'g') ~ '[가-힣]+구'
			  -- 리 포함: 동/리로 끝나는 주소만 대상으로 한다.
			  AND regexp_replace(btrim(coalesce(t.legal_dong_nm, '')), '[^0-9가-힣]', '', 'g') ~ '(동|리)$'
		),
		old_rows AS (
			SELECT
				o.legal_dong_cd AS old_cd,
				o.legal_dong_nm AS old_nm,
				o.rank AS old_rank,
				nullif(btrim(coalesce(o.dlt_dt, '')), '') AS old_dlt_dt,
				regexp_replace(
					regexp_replace(
						regexp_replace(btrim(coalesce(o.legal_dong_nm, '')), '[^0-9가-힣]', '', 'g'),
						'([가-힣]+(시|군))([가-힣]+구)', E'\\1', 'g'
					),
					E'(제)?\\d+동$', '동'
				) AS nm_norm
			-- 과거 후보(old)는 스테이징 범위로 제한하지 않는다.
			-- - 입력 CSV가 신규 코드만 포함할 수 있어, old까지 제한하면 매핑이 0건이 될 수 있다.
			FROM tb_fdis_legal_dong_cd_m o
			WHERE regexp_replace(btrim(coalesce(o.legal_dong_nm, '')), '[^0-9가-힣]', '', 'g') !~ '[가-힣]+구'
			  -- 리 포함: 동/리로 끝나는 주소만 대상으로 한다.
			  AND regexp_replace(btrim(coalesce(o.legal_dong_nm, '')), '[^0-9가-힣]', '', 'g') ~ '(동|리)$'
		),
		candidates AS (
			SELECT
				n.new_cd,
				n.new_nm,
				n.new_cr_dt,
				o.old_cd,
				o.old_nm,
				o.old_rank,
				o.old_dlt_dt,
				count(*) OVER (PARTITION BY n.new_cd) AS cand_cnt,
				row_number() OVER (
					PARTITION BY n.new_cd
					ORDER BY
						-- 가장 강한 연결: 신규 cr_dt = 과거 dlt_dt (시행일 연결)
						CASE WHEN n.new_cr_dt IS NOT NULL AND o.old_dlt_dt = n.new_cr_dt THEN 0 ELSE 1 END,
						-- 보강: 시행일이 여러 개면 가장 최근 말소를 우선
						o.old_dlt_dt DESC NULLS LAST,
						o.old_cd
				) AS rn
			FROM new_rows n
			JOIN old_rows o ON o.nm_norm = n.nm_norm
			WHERE o.old_cd <> n.new_cd
		)
		SELECT
			new_cd,
			new_nm,
			old_cd AS would_set_past_cd,
			old_nm,
			old_rank AS would_set_rank,
			cand_cnt
		FROM candidates
		WHERE rn = 1;

		SELECT count(*) INTO addr_map_candidates_cnt
		FROM pg_temp.tmp_legal_dong_past_map_stage;

		UPDATE tb_fdis_legal_dong_cd_m t
		SET
			past_legal_dong_cd = m.would_set_past_cd,
			rank = COALESCE(m.would_set_rank, t.rank),
			last_updt_dtm = now(),
			last_upusr_id = v_operator_id
		FROM pg_temp.tmp_legal_dong_past_map_stage m
		WHERE t.legal_dong_cd = m.new_cd
		  -- 과거코드가 공백/비숫자 혼입으로 "사실상 미설정"인 케이스도 자동 매핑 대상으로 포함한다.
		  AND nullif(regexp_replace(coalesce(t.past_legal_dong_cd, ''), '[^0-9]', '', 'g'), '') IS NULL
		  AND m.would_set_past_cd ~ '^[0-9]{10}$';
		GET DIAGNOSTICS addr_map_updated_cnt = ROW_COUNT;

		IF p_preview_only THEN
			-- =========================================================
			-- [미리보기 출력] 결과 확인용 TEMP 테이블 생성
			-- =========================================================
		-- - 실테이블이 아닌 shadow TEMP 테이블(`tb_fdis_legal_dong_cd_m`)에서, 입력 범위(stage_codes)만 추려서 저장한다.
		-- - 이 TEMP 테이블만 남기고 shadow TEMP 테이블은 DROP한다(동일 세션에서 apply 시 안전).
		CREATE TEMP TABLE tmp_legal_dong_migrate_preview AS
		SELECT
			t.legal_dong_cd,
			t.legal_dong_nm,
			t.rank,
			t.past_legal_dong_cd,
			t.cr_dt,
			t.dlt_dt,
			t.use_yn,
			t.last_updt_dtm,
			t.last_upusr_id
		FROM tb_fdis_legal_dong_cd_m t
		JOIN pg_temp.tmp_legal_dong_migrate_stage_codes s ON s.legal_dong_cd = t.legal_dong_cd;

			-- shadow TEMP 테이블 정리(안전장치)
			EXECUTE format('DROP TABLE IF EXISTS pg_temp.%I;', target_table);
			-- 디버깅을 위해 stage_codes는 세션에 유지한다.
		END IF;

	-- 결과 건수는 TEMP 결과 테이블로 조회 가능하게 제공한다.
	DROP TABLE IF EXISTS pg_temp.tmp_legal_dong_migrate_result;
		CREATE TEMP TABLE tmp_legal_dong_migrate_result (
		input_codes int NULL,
		upsert_rows_effected int NULL,
		past_emndn_updated int NULL,
		past_li_updated int NULL,
		emndn_manual_rank_updated_cnt int NULL,
		li_manual_rank_updated_cnt int NULL,
		emndn_tie_cnt int NULL,
		eff_dt_count int NULL,
		old_eff_mapped_count int NULL,
		old_eff_dt_min text NULL,
		old_eff_dt_max text NULL,
		old_emndn_cnt int NULL,
		new_emndn_cnt int NULL,
		emndn_candidates_cnt int NULL,
		emndn_unique_map_cnt int NULL,
			old_li_cnt int NULL,
			new_li_cnt int NULL,
			li_targets_cnt int NULL,
			addr_map_candidates_cnt int NULL,
			addr_map_updated_cnt int NULL,
			executed_at timestamptz NOT NULL DEFAULT now(),
			operator_id text NULL,
			preview_only boolean NOT NULL
		);
		INSERT INTO tmp_legal_dong_migrate_result (
		input_codes, upsert_rows_effected, past_emndn_updated, past_li_updated,
		emndn_manual_rank_updated_cnt, li_manual_rank_updated_cnt, emndn_tie_cnt,
		eff_dt_count, old_eff_mapped_count, old_eff_dt_min, old_eff_dt_max,
			old_emndn_cnt, new_emndn_cnt, emndn_candidates_cnt, emndn_unique_map_cnt,
			old_li_cnt, new_li_cnt, li_targets_cnt,
			addr_map_candidates_cnt, addr_map_updated_cnt,
			operator_id, preview_only
		) VALUES (
		input_codes, upsert_rows_effected, past_emndn_updated, past_li_updated,
		emndn_manual_rank_updated_cnt, li_manual_rank_updated_cnt, emndn_tie_cnt,
		eff_dt_count, old_eff_mapped_count, old_eff_dt_min, old_eff_dt_max,
			old_emndn_cnt, new_emndn_cnt, emndn_candidates_cnt, emndn_unique_map_cnt,
			old_li_cnt, new_li_cnt, li_targets_cnt,
			addr_map_candidates_cnt, addr_map_updated_cnt,
			v_operator_id, p_preview_only
		);

		RAISE NOTICE 'sp_legal_dong_migrate_from_temp result: input_codes=%, upsert_rows_effected=%, past_emndn_updated=%, past_li_updated=%',
			input_codes, upsert_rows_effected, past_emndn_updated, past_li_updated;
	RAISE NOTICE 'sp_legal_dong_migrate_from_temp diag: emndn mapping ties(top_ties>1)=%', emndn_tie_cnt;
END;
$$;
