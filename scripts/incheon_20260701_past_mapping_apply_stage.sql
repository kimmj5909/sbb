-- 인천(2026-07-01) 법정동 past_legal_dong_cd 반영 (SBB stage 테이블용)
--
-- 목적
-- - 인천 개편(중구/동구/서구 → 제물포구/영종구/서구/검단구)으로 "신 법정동코드"가 생성되는 경우,
--   신코드의 past_legal_dong_cd를 구코드로 채워 이력 조회/추적이 가능하도록 한다.
--
-- 입력(매핑 CSV)
-- - scripts/incheon_20260701_extract_mapping.py 산출물
--   scripts/out/incheon_20260701_admin_bjd_mapping.csv
--
-- 주의
-- - 이 스크립트는 "신코드가 이미 업서트되어 존재"한다는 전제에서 동작한다.
-- - SBB 앱 기본 테이블은 LegalDongTables.LEGAL_DONG_TABLE = tb_legal_dong_stage_l 이다.
--   운영 타겟 테이블이 다르면 테이블명을 교체해서 사용한다.
--
-- 실행(psql)
--   psql "postgresql://USER:PASSWORD@HOST:5432/DB" ^
--     -v map_csv="C:/.../scripts/out/incheon_20260701_admin_bjd_mapping.csv" ^
--     -v operator_id="ADMIN" ^
--     -f scripts/incheon_20260701_past_mapping_apply_stage.sql
--
\set ON_ERROR_STOP on

\if :{?map_csv}
\else
  \echo 'ERROR: -v map_csv=<mapping_csv_path> 가 필요합니다.'
  \quit 1
\endif

\if :{?operator_id}
\else
  \set operator_id 'PSQL'
\endif

BEGIN;

CREATE TEMP TABLE tmp_incheon_20260701_map (
	old_legal_dong_cd10 text NOT NULL,
	new_legal_dong_cd10 text NOT NULL
);

TRUNCATE TABLE tmp_incheon_20260701_map;

-- 산출 CSV 전체 컬럼 중, past 매핑에 필요한 2개 컬럼만 사용한다.
\copy tmp_incheon_20260701_map(old_legal_dong_cd10,new_legal_dong_cd10) \
  FROM :'map_csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

-- 신코드에 past가 비어 있을 때만 채운다(수동 보정값 보호).
UPDATE tb_legal_dong_stage_l t
SET past_legal_dong_cd = m.old_legal_dong_cd10,
    last_updt_dtm = now(),
    last_upusr_id = :'operator_id'
FROM tmp_incheon_20260701_map m
WHERE t.legal_dong_cd = m.new_legal_dong_cd10
  AND coalesce(nullif(btrim(m.old_legal_dong_cd10), ''), '') <> ''
  AND t.past_legal_dong_cd IS NULL;

-- 적용 결과 요약(빠른 점검용)
SELECT
  count(*) AS mapped_cnt
FROM tb_legal_dong_stage_l t
JOIN tmp_incheon_20260701_map m ON m.new_legal_dong_cd10 = t.legal_dong_cd
WHERE t.past_legal_dong_cd = m.old_legal_dong_cd10;

COMMIT;

