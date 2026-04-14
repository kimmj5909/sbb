-- 법정동 마이그레이션 프로시저 (PostgreSQL / 서버 경로 CSV)
--
-- 목적
-- - 운영 DB 서버에 업로드된 CSV 파일 경로만 넘겨서:
--   1) 서버-side COPY로 CSV 적재
-- 을 수행한다.
-- - 실제 테이블 업서트/과거코드(past_legal_dong_cd) 반영은 2번 프로시저(`sp_legal_dong_migrate_from_temp`)를 별도로 호출한다.
--
-- 전제/주의
-- - CSV는 "DB 서버"의 파일 시스템 경로여야 한다(클라이언트 로컬 경로 불가).
-- - COPY FROM 서버 파일 읽기는 권한이 필요하다.
--   - PostgreSQL 14+에서는 보통 `pg_read_server_files` 또는 superuser 권한이 필요하다.
-- - 이 프로시저는 내부적으로 TEMP TABLE을 생성한다(세션 종료 시 자동 삭제).
--   - 테이블 생성 로그 정책이 엄격한 환경에서는 사용 전 정책 확인 필요.
-- - (참고) 실테이블 반영은 `sp_legal_dong_migrate_from_temp`가 담당한다.
--
-- CSV 컬럼(엑셀 동일 8컬럼)
--   행정동코드, 시도명, 시군구명, 읍면동명, 법정동코드, 동리명, 생성일자, 말소일자
--
-- 사용 예시
--   CALL sp_legal_dong_migrate_from_server_csv('/var/lib/postgresql/import/legal_dong.csv', 'ADMIN');

-- =========================================================
-- v1 (기존 유지): UTF-8 + 콤마 구분자만 지원
-- =========================================================
CREATE OR REPLACE PROCEDURE sp_legal_dong_migrate_from_server_csv(
	p_csv_path text,
	-- 작성자 기본값은 정책상 ADMIN으로 고정한다.
	p_operator_id text DEFAULT 'ADMIN'
)
LANGUAGE plpgsql
AS $$
DECLARE
	csv_path text := nullif(btrim(p_csv_path), '');
	operator_id text := coalesce(nullif(btrim(p_operator_id), ''), 'ADMIN');
	input_rows int := 0;
	normalized_rows int := 0;
BEGIN
	IF csv_path IS NULL THEN
		RAISE EXCEPTION 'csv_path is required (server file path)';
	END IF;

	-- TEMP 테이블 준비(세션 종료 시 자동 삭제)
	CREATE TEMP TABLE IF NOT EXISTS tmp_legal_dong_excel_csv (
		admin_cd_raw  text NULL,
		ctprv_nm_raw  text NULL,
		sgng_nm_raw   text NULL,
		emndn_nm_raw  text NULL,
		legal_cd_raw  text NULL,
		li_nm_raw     text NULL,
		cr_dt_raw     text NULL,
		dlt_dt_raw    text NULL
	);

	TRUNCATE TABLE tmp_legal_dong_excel_csv;

	-- 서버-side COPY: CSV 파일은 DB 서버 경로여야 함
	EXECUTE format($fmt$
		COPY tmp_legal_dong_excel_csv(
			admin_cd_raw, ctprv_nm_raw, sgng_nm_raw, emndn_nm_raw,
			legal_cd_raw, li_nm_raw, cr_dt_raw, dlt_dt_raw
		)
		FROM %L
		WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
	$fmt$, csv_path);

	-- 입력된 원본 row 수 출력(COPY 직후 기준)
	SELECT count(*) INTO input_rows
	FROM tmp_legal_dong_excel_csv;
	RAISE NOTICE 'tmp_legal_dong_excel_csv input rows: %', input_rows;

	-- (정규화) 반송2동/반송1동 등 행정동명 혼입 케이스 정규화
	-- - `...1동`/`...2동`/`... 제1동`처럼 "숫자+동" 패턴은 법정동 관점의 `...동`으로 통일한다.
	-- - 2번 프로시저는 정규화된 입력을 전제로 미리보기/실테이블 적재를 수행한다.
	UPDATE tmp_legal_dong_excel_csv t
	SET
		emndn_nm_raw = CASE
			WHEN btrim(t.emndn_nm_raw) ~ '^[가-힣]+\\s*(제\\s*)?\\d+\\s*동$'
				THEN regexp_replace(btrim(t.emndn_nm_raw), '\\s*(제\\s*)?\\d+\\s*동$', '동')
			ELSE t.emndn_nm_raw
		END,
		li_nm_raw = CASE
			WHEN btrim(t.li_nm_raw) ~ '^[가-힣]+\\s*(제\\s*)?\\d+\\s*동$'
				THEN regexp_replace(btrim(t.li_nm_raw), '\\s*(제\\s*)?\\d+\\s*동$', '동')
			ELSE t.li_nm_raw
		END
	WHERE
		(btrim(t.emndn_nm_raw) ~ '^[가-힣]+\\s*(제\\s*)?\\d+\\s*동$')
		OR (btrim(t.li_nm_raw) ~ '^[가-힣]+\\s*(제\\s*)?\\d+\\s*동$');

	GET DIAGNOSTICS normalized_rows = ROW_COUNT;
	RAISE NOTICE 'tmp_legal_dong_excel_csv normalized rows: %', normalized_rows;
END;
$$;

-- =========================================================
-- v2: 인코딩/구분자 자동 재시도 지원 (실사용 권장)
-- =========================================================
CREATE OR REPLACE PROCEDURE sp_legal_dong_migrate_from_server_csv2(
	p_csv_path text,
	-- 작성자 기본값은 정책상 ADMIN으로 고정한다.
	p_operator_id text DEFAULT 'ADMIN',
	-- (선택) CSV 인코딩
	-- - 기본은 UTF-8로 가정한다.
	-- - Windows/Excel에서 "CSV(쉼표로 분리)(*.csv)"로 저장한 파일은 실제로 CP949(WIN949)인 경우가 많아,
	--   UTF-8로 COPY가 실패하면(예: invalid byte sequence for encoding "UTF8") WIN949로 한 번 더 시도한다.
	p_csv_encoding text DEFAULT 'UTF8',
	-- (선택) CSV 구분자
	-- - 기본은 콤마(,)로 가정한다.
	-- - 엑셀에서 "텍스트(탭으로 분리)"로 내보낸 파일을 .csv로 저장하는 경우 실제 구분자가 TAB인 케이스가 있어,
	--   포맷 오류(예: missing data for column ...)가 발생하면 TAB으로 재시도한다.
	p_csv_delimiter text DEFAULT ','
)
LANGUAGE plpgsql
AS $$
DECLARE
	csv_path text := nullif(btrim(p_csv_path), '');
	operator_id text := coalesce(nullif(btrim(p_operator_id), ''), 'ADMIN');
	v_csv_encoding text := upper(coalesce(nullif(btrim(p_csv_encoding), ''), 'UTF8'));
	v_csv_delimiter text := coalesce(nullif(btrim(p_csv_delimiter), ''), ',');
	v_delimiter_char text := NULL;
	used_csv_encoding text := NULL;
	used_csv_delimiter text := NULL;
	used_quote_disabled boolean := false;
	try_encoding text := NULL;
	try_delimiter text := NULL;
	try_quote_disabled boolean := false;
	last_sqlstate text := NULL;
	last_sqlerrm text := NULL;
	input_rows int := 0;
	normalized_rows int := 0;
BEGIN
	IF csv_path IS NULL THEN
		RAISE EXCEPTION 'csv_path is required (server file path)';
	END IF;

	-- delimiter는 COPY의 DELIMITER에 실제 문자 1개가 와야 한다.
	-- - 사용자 입력이 'TAB' 또는 '\t'인 경우 실제 탭 문자로 변환한다.
	v_delimiter_char := CASE
		WHEN upper(v_csv_delimiter) = 'TAB' THEN chr(9)
		WHEN v_csv_delimiter IN ('\\t', '\t') THEN chr(9)
		ELSE v_csv_delimiter
	END;
	IF char_length(v_delimiter_char) <> 1 THEN
		RAISE EXCEPTION 'csv_delimiter must be a single character (use TAB or \\t for tab). got=%', v_csv_delimiter;
	END IF;

	-- TEMP 테이블 준비(세션 종료 시 자동 삭제)
	CREATE TEMP TABLE IF NOT EXISTS tmp_legal_dong_excel_csv (
		admin_cd_raw  text NULL,
		ctprv_nm_raw  text NULL,
		sgng_nm_raw   text NULL,
		emndn_nm_raw  text NULL,
		legal_cd_raw  text NULL,
		li_nm_raw     text NULL,
		cr_dt_raw     text NULL,
		dlt_dt_raw    text NULL
	);

	TRUNCATE TABLE tmp_legal_dong_excel_csv;

	-- 서버-side COPY: CSV 파일은 DB 서버 경로여야 함
	-- - 인코딩(UTF8/WIN949) + 구분자(기본/탭) + QUOTE 비활성(옵션)을 순차로 시도한다.
	-- - 현재 장애 케이스는 엑셀이 탭 파일을 .csv로 저장하면서 행 전체가 따옴표로 감싸진 형태(예: ""4159...\t..."")로,
	--   기본 CSV 해석(QUOTE=")에서는 delimiter가 무시되어 `missing data for column ... (22P04)`가 발생할 수 있다.
	--   이 경우 QUOTE 문자를 '"'가 아닌 다른 문자로 바꿔(사실상 quoting 비활성) 파싱을 진행한다.
	FOR try_encoding IN
		SELECT unnest(ARRAY[
			v_csv_encoding,
			CASE WHEN v_csv_encoding <> 'WIN949' THEN 'WIN949' ELSE NULL END
		])
	LOOP
		EXIT WHEN try_encoding IS NULL OR used_csv_encoding IS NOT NULL;

		FOR try_delimiter IN
			SELECT unnest(ARRAY[
				v_delimiter_char,
				CASE WHEN v_delimiter_char <> chr(9) THEN chr(9) ELSE NULL END
			])
		LOOP
			EXIT WHEN try_delimiter IS NULL OR used_csv_encoding IS NOT NULL;

			-- 1) 일반 CSV 옵션(QUOTE 기본값)
			BEGIN
				EXECUTE format($fmt$
					COPY tmp_legal_dong_excel_csv(
						admin_cd_raw, ctprv_nm_raw, sgng_nm_raw, emndn_nm_raw,
						legal_cd_raw, li_nm_raw, cr_dt_raw, dlt_dt_raw
					)
					FROM %L
					WITH (FORMAT csv, HEADER true, ENCODING %L, DELIMITER %L)
				$fmt$, csv_path, try_encoding, try_delimiter);
				used_csv_encoding := try_encoding;
				used_csv_delimiter := try_delimiter;
				used_quote_disabled := false;
			EXCEPTION
				WHEN character_not_in_repertoire OR bad_copy_file_format OR feature_not_supported THEN
					last_sqlstate := SQLSTATE;
					last_sqlerrm := SQLERRM;

					-- 2) QUOTE 변경(사실상 quoting 비활성):
					-- - QUOTE/ESCAPE는 반드시 "단일 1바이트 문자"여야 한다.
					-- - 여기서는 단일 따옴표(')를 사용해, 파일의 큰따옴표(")를 quote로 취급하지 않도록 한다.
					BEGIN
						EXECUTE format($fmt$
							COPY tmp_legal_dong_excel_csv(
								admin_cd_raw, ctprv_nm_raw, sgng_nm_raw, emndn_nm_raw,
								legal_cd_raw, li_nm_raw, cr_dt_raw, dlt_dt_raw
							)
							FROM %L
							WITH (FORMAT csv, HEADER true, ENCODING %L, DELIMITER %L, QUOTE '''', ESCAPE '''')
						$fmt$, csv_path, try_encoding, try_delimiter);
						used_csv_encoding := try_encoding;
						used_csv_delimiter := try_delimiter;
						used_quote_disabled := true;
					EXCEPTION
						WHEN character_not_in_repertoire OR bad_copy_file_format OR feature_not_supported THEN
							last_sqlstate := SQLSTATE;
							last_sqlerrm := SQLERRM;
					END;
			END;
		END LOOP;
	END LOOP;

	IF used_csv_encoding IS NULL THEN
		RAISE EXCEPTION 'COPY failed for all (encoding, delimiter, quote) combinations. last_sqlstate=%, last_error=%',
			coalesce(last_sqlstate, '?'),
			coalesce(last_sqlerrm, '?');
	END IF;

	RAISE NOTICE 'tmp_legal_dong_excel_csv COPY encoding used: %, delimiter used: %',
		used_csv_encoding,
		CASE WHEN used_csv_delimiter = chr(9) THEN 'TAB' ELSE used_csv_delimiter END;
	RAISE NOTICE 'tmp_legal_dong_excel_csv COPY quote disabled: %', used_quote_disabled;

	-- 엑셀/내보내기 파일에서 행 전체가 ""로 감싸진 형태로 들어오면,
	-- quote 처리 옵션을 우회하더라도 컬럼 값에 큰따옴표가 남을 수 있어, 전 컬럼에서 제거한다.
	UPDATE tmp_legal_dong_excel_csv t
	SET
		admin_cd_raw = nullif(btrim(replace(replace(coalesce(t.admin_cd_raw, ''), E'\ufeff', ''), '"', '')), ''),
		ctprv_nm_raw = nullif(btrim(replace(replace(coalesce(t.ctprv_nm_raw, ''), E'\ufeff', ''), '"', '')), ''),
		sgng_nm_raw = nullif(btrim(replace(replace(coalesce(t.sgng_nm_raw, ''), E'\ufeff', ''), '"', '')), ''),
		emndn_nm_raw = nullif(btrim(replace(replace(coalesce(t.emndn_nm_raw, ''), E'\ufeff', ''), '"', '')), ''),
		legal_cd_raw = nullif(btrim(replace(replace(coalesce(t.legal_cd_raw, ''), E'\ufeff', ''), '"', '')), ''),
		li_nm_raw = nullif(btrim(replace(replace(coalesce(t.li_nm_raw, ''), E'\ufeff', ''), '"', '')), ''),
		cr_dt_raw = nullif(btrim(replace(replace(coalesce(t.cr_dt_raw, ''), E'\ufeff', ''), '"', '')), ''),
		dlt_dt_raw = nullif(btrim(replace(replace(coalesce(t.dlt_dt_raw, ''), E'\ufeff', ''), '"', '')), '');

	-- 입력된 원본 row 수 출력(COPY 직후 기준)
	SELECT count(*) INTO input_rows
	FROM tmp_legal_dong_excel_csv;
	RAISE NOTICE 'tmp_legal_dong_excel_csv input rows: %', input_rows;

	-- (정규화) 반송2동/반송1동 등 행정동명 혼입 케이스 정규화
	UPDATE tmp_legal_dong_excel_csv t
	SET
		emndn_nm_raw = CASE
			WHEN btrim(t.emndn_nm_raw) ~ '^[가-힣]+\\s*(제\\s*)?\\d+\\s*동$'
				THEN regexp_replace(btrim(t.emndn_nm_raw), '\\s*(제\\s*)?\\d+\\s*동$', '동')
			ELSE t.emndn_nm_raw
		END,
		li_nm_raw = CASE
			WHEN btrim(t.li_nm_raw) ~ '^[가-힣]+\\s*(제\\s*)?\\d+\\s*동$'
				THEN regexp_replace(btrim(t.li_nm_raw), '\\s*(제\\s*)?\\d+\\s*동$', '동')
			ELSE t.li_nm_raw
		END
	WHERE
		(btrim(t.emndn_nm_raw) ~ '^[가-힣]+\\s*(제\\s*)?\\d+\\s*동$')
		OR (btrim(t.li_nm_raw) ~ '^[가-힣]+\\s*(제\\s*)?\\d+\\s*동$');

	GET DIAGNOSTICS normalized_rows = ROW_COUNT;
	RAISE NOTICE 'tmp_legal_dong_excel_csv normalized rows: %', normalized_rows;
END;
$$;
