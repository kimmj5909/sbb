-- 법정동 마이그레이션 실행 스크립트 (psql 전용 / 로컬 CSV → TEMP → CALL)
--
-- 배경
-- - DBeaver는 TEMP TABLE로 "Import Data"가 제한될 수 있어, psql로 처리하는 워크플로우를 제공한다.
-- - PostgreSQL PROCEDURE 내부에서는 psql 메타커맨드(\copy 등)를 실행할 수 없다.
--   따라서:
--   1) 이 스크립트에서 TEMP 테이블을 준비하고,
--   2) \copy 로 로컬 CSV를 TEMP 테이블에 적재한 뒤,
--   3) `CALL sp_legal_dong_migrate_from_temp(...)` 로 업서트 + past 반영을 수행한다.
--
-- 사용 예시(psql)
--   psql "postgresql://USER:PASSWORD@HOST:5432/DB" ^
--     -v csv_path="C:/data/legal_dong.csv" ^
--     -v operator_id="ADMIN" ^
--     -f scripts/legal_dong_migrate_from_psql_local_csv.sql
--
-- CSV 컬럼(엑셀 동일 8컬럼)
--   행정동코드, 시도명, 시군구명, 읍면동명, 법정동코드, 동리명, 생성일자, 말소일자
--
\set ON_ERROR_STOP on

\if :{?csv_path}
\else
  \echo 'ERROR: -v csv_path=<local_csv_path> 가 필요합니다.'
  \quit 1
\endif

\if :{?operator_id}
\else
  \set operator_id 'PSQL'
\endif

BEGIN;

-- 1) TEMP 테이블 준비 (세션 종료 시 자동 삭제)
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

-- 2) 로컬 CSV 적재 (클라이언트-side)
-- - psql의 \copy 는 클라이언트 머신 파일을 읽어 서버로 전송한다.
-- - 서버 디스크 접근 권한이 없어도 동작한다.
\copy tmp_legal_dong_excel_csv(admin_cd_raw,ctprv_nm_raw,sgng_nm_raw,emndn_nm_raw,legal_cd_raw,li_nm_raw,cr_dt_raw,dlt_dt_raw) \
  FROM :'csv_path' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

-- 3) 업서트 + past 반영
-- - 프로시저는 pg_temp.tmp_legal_dong_excel_csv 를 참조하므로, 현재 세션 TEMP 테이블을 그대로 사용한다.
CALL sp_legal_dong_migrate_from_temp(:'operator_id');

COMMIT;

