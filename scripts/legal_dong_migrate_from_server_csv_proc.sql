-- 법정동 마이그레이션 프로시저 (PostgreSQL / 서버 경로 CSV)
--
-- 목적
-- - 운영 DB 서버에 업로드된 CSV 파일 경로만 넘겨서:
--   1) 서버-side COPY로 CSV 적재
--   2) tb_legal_dong_l 업서트 + past 자동 반영
-- 을 한 번에 수행한다.
--
-- 전제/주의
-- - CSV는 "DB 서버"의 파일 시스템 경로여야 한다(클라이언트 로컬 경로 불가).
-- - COPY FROM 서버 파일 읽기는 권한이 필요하다.
--   - PostgreSQL 14+에서는 보통 `pg_read_server_files` 또는 superuser 권한이 필요하다.
-- - 이 프로시저는 내부적으로 TEMP TABLE을 생성한다(세션 종료 시 자동 삭제).
--   - 테이블 생성 로그 정책이 엄격한 환경에서는 사용 전 정책 확인 필요.
-- - `sp_legal_dong_migrate_from_temp` 프로시저가 먼저 생성되어 있어야 한다.
--   - 스크립트: scripts/legal_dong_migrate_from_temp_proc.sql
--
-- CSV 컬럼(엑셀 동일 8컬럼)
--   행정동코드, 시도명, 시군구명, 읍면동명, 법정동코드, 동리명, 생성일자, 말소일자
--
-- 사용 예시
--   CALL sp_legal_dong_migrate_from_server_csv('/var/lib/postgresql/import/legal_dong.csv', 'ADMIN');

CREATE OR REPLACE PROCEDURE sp_legal_dong_migrate_from_server_csv(
	IN p_csv_path text,
	IN p_operator_id text DEFAULT 'DBSERVER'
)
LANGUAGE plpgsql
AS $$
DECLARE
	csv_path text := nullif(btrim(p_csv_path), '');
	operator_id text := coalesce(nullif(btrim(p_operator_id), ''), 'DBSERVER');
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

	-- 업서트 + past 반영(기존 프로시저 재사용)
	CALL sp_legal_dong_migrate_from_temp(operator_id);
END;
$$;

