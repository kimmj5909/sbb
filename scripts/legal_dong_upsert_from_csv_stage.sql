-- 법정동 마이그레이션 CSV를 PostgreSQL(tb_legal_dong_stage_l)에 업서트한다.
--
-- 목적
-- - 운영 타겟 테이블에 반영하기 전에, SBB에서 사용하는 stage 테이블에 먼저 적재해 검증한다.
-- - 업서트 키는 legal_dong_cd(10자리)이며, 규칙은 애플리케이션과 동일하게 맞춘다.
--
-- 전제
-- - tb_legal_dong_stage_l 스키마는 애플리케이션 기동 시 자동 생성되도록 구성됨.
-- - cr_dt / dlt_dt는 yyyyMMdd(8자리) 문자열(varchar(8))로 저장한다.
-- - CSV는 관리자 화면 "법정동 코드 마이그레이션" 미리보기에서 다운로드한 `*.derived.csv`를 사용한다.
--
-- 실행 예시(psql)
--   psql "postgresql://postgres:root@localhost:5432/testdb" ^
--     -v csv_path="C:/path/to/file.derived.csv" ^
--     -v operator_id="ADMIN" ^
--     -f scripts/legal_dong_upsert_from_csv_stage.sql
--
-- 주의
-- - \copy는 psql 전용 메타 명령이므로, 반드시 psql에서 실행해야 한다.
-- - CSV 헤더/컬럼 순서는 관리자 다운로드와 동일해야 한다.
--
-- CSV 컬럼
-- legal_dong_cd,legal_dong_nm,ctprv_cd,ctprv_nm,sgng_cd,sgng_nm,emndn_cd,emndn_nm,li_cd,li_nm,rank,cr_dt,dlt_dt
--
-- 업서트 규칙
-- - 충돌 키: legal_dong_cd(PK)
-- - past_legal_dong_cd 및 frst_* 는 최초값 유지(UPDATE에서 제외)
-- - dlt_dt: 최대값(가장 늦은 값) 유지(과거 데이터 누락 보강)
-- - use_yn:
--     * dlt_dt가 NULL이면 'Y'
--     * 최종 dlt_dt가 존재하면 NULL
--
\set ON_ERROR_STOP on

-- 필수 파라미터 확인(누락 시 즉시 실패)
\if :{?csv_path}
\else
  \echo 'ERROR: -v csv_path=... 가 필요합니다.'
  \quit 1
\endif

-- operator_id는 선택(미지정 시 SYSTEM)
\if :{?operator_id}
\else
  \set operator_id 'SYSTEM'
\endif

BEGIN;

CREATE TEMP TABLE tmp_legal_dong_upsert (
	legal_dong_cd  varchar(10),
	legal_dong_nm  varchar(200),
	ctprv_cd       varchar(2),
	ctprv_nm       varchar(50),
	sgng_cd        varchar(5),
	sgng_nm        varchar(50),
	emndn_cd       varchar(8),
	emndn_nm       varchar(50),
	li_cd          varchar(10),
	li_nm          varchar(50),
	rank           integer,
	cr_dt          varchar(8),
	dlt_dt         varchar(8)
);

-- CSV 로드(클라이언트 경로)
\copy tmp_legal_dong_upsert (
	legal_dong_cd, legal_dong_nm,
	ctprv_cd, ctprv_nm,
	sgng_cd, sgng_nm,
	emndn_cd, emndn_nm,
	li_cd, li_nm,
	rank, cr_dt, dlt_dt
) FROM :'csv_path' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

-- 데이터 정규화: 빈 문자열을 NULL로 변환(특히 코드/일자 컬럼)
UPDATE tmp_legal_dong_upsert
SET
	sgng_cd  = NULLIF(btrim(sgng_cd), ''),
	sgng_nm  = NULLIF(btrim(sgng_nm), ''),
	emndn_cd = NULLIF(btrim(emndn_cd), ''),
	emndn_nm = NULLIF(btrim(emndn_nm), ''),
	li_cd    = NULLIF(btrim(li_cd), ''),
	li_nm    = NULLIF(btrim(li_nm), ''),
	cr_dt    = NULLIF(btrim(cr_dt), ''),
	dlt_dt   = NULLIF(btrim(dlt_dt), '');

-- 업서트
INSERT INTO tb_legal_dong_stage_l (
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
	t.legal_dong_cd, t.legal_dong_nm,
	t.ctprv_cd, t.ctprv_nm,
	t.sgng_cd, t.sgng_nm,
	t.emndn_cd, t.emndn_nm,
	t.li_cd, t.li_nm,
	t.rank, t.cr_dt, t.dlt_dt,
	NULL,
	now(), :'operator_id',
	now(), :'operator_id',
	CASE WHEN CAST(t.dlt_dt AS varchar) IS NULL THEN 'Y' ELSE NULL END
FROM tmp_legal_dong_upsert t
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
	-- 생성일자(cr_dt)는 최소값(가장 이른 값)을 유지한다.
	cr_dt = CASE
		WHEN tb_legal_dong_stage_l.cr_dt IS NULL THEN EXCLUDED.cr_dt
		WHEN EXCLUDED.cr_dt IS NULL THEN tb_legal_dong_stage_l.cr_dt
		ELSE LEAST(tb_legal_dong_stage_l.cr_dt, EXCLUDED.cr_dt)
	END,
	-- 말소일자(dlt_dt)는 최대값(가장 늦은 값)을 유지한다.
	dlt_dt = CASE
		WHEN tb_legal_dong_stage_l.dlt_dt IS NULL THEN EXCLUDED.dlt_dt
		WHEN EXCLUDED.dlt_dt IS NULL THEN tb_legal_dong_stage_l.dlt_dt
		ELSE GREATEST(tb_legal_dong_stage_l.dlt_dt, EXCLUDED.dlt_dt)
	END,
	-- use_yn은 최종 dlt_dt 존재 여부로만 결정한다(Y or NULL).
	use_yn = CASE
		WHEN (CASE
			WHEN tb_legal_dong_stage_l.dlt_dt IS NULL THEN EXCLUDED.dlt_dt
			WHEN EXCLUDED.dlt_dt IS NULL THEN tb_legal_dong_stage_l.dlt_dt
			ELSE GREATEST(tb_legal_dong_stage_l.dlt_dt, EXCLUDED.dlt_dt)
		END) IS NULL THEN 'Y'
		ELSE NULL
	END,
	last_updt_dtm = now(),
	last_upusr_id = :'operator_id';

COMMIT;

