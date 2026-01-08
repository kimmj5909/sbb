-- 법정동 "변경분(델타)" CSV를 PostgreSQL(tb_legal_dong_l)에 업서트하고,
-- 동일 시행일(eff_dt) 기준으로 past_legal_dong_cd까지 채운다.
--
-- 사용 의도(운영)
-- - 운영 DB에는 기존 데이터가 존재하며, 이번 배치에서 "말소/생성되는 코드"만 업서트한다.
-- - 업서트 후에는 신규 코드의 과거법정동코드(past_legal_dong_cd)를 시행일 기준으로 자동 반영한다.
--
-- 실행 예시(psql)
--   psql "postgresql://postgres:root@localhost:5432/testdb" ^
--     -v csv_path="C:/path/to/delta.derived.csv" ^
--     -v eff_dt="20260201" ^
--     -v operator_id="ADMIN" ^
--     -f scripts/legal_dong_upsert_delta_from_csv.sql
--
-- 전제
-- - cr_dt / dlt_dt는 yyyyMMdd(8자리) 문자열(varchar(8))로 저장한다.
-- - CSV 컬럼(derived.csv): legal_dong_cd,legal_dong_nm,ctprv_cd,ctprv_nm,sgng_cd,sgng_nm,emndn_cd,emndn_nm,li_cd,li_nm,rank,cr_dt,dlt_dt
--
-- 안전장치(중요)
-- - eff_dt는 필수이며, CSV의 각 행은 (cr_dt=eff_dt) 또는 (dlt_dt=eff_dt) 중 최소 하나를 만족해야 한다.
--   -> 운영에서 "해당 시행일에 생성/말소되는 코드만" 업서트한다는 전제를 강제해, 오염/대량갱신을 방지한다.
--
\set ON_ERROR_STOP on

\if :{?csv_path}
\else
  \echo 'ERROR: -v csv_path=... 가 필요합니다.'
  \quit 1
\endif

\if :{?eff_dt}
\else
  \echo 'ERROR: -v eff_dt=YYYYMMDD 가 필요합니다.'
  \quit 1
\endif

\if :{?operator_id}
\else
  \set operator_id 'SYSTEM'
\endif

BEGIN;

CREATE TEMP TABLE tmp_legal_dong_delta (
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
\copy tmp_legal_dong_delta (
	legal_dong_cd, legal_dong_nm,
	ctprv_cd, ctprv_nm,
	sgng_cd, sgng_nm,
	emndn_cd, emndn_nm,
	li_cd, li_nm,
	rank, cr_dt, dlt_dt
) FROM :'csv_path' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

-- 데이터 정규화: 빈 문자열을 NULL로 변환
UPDATE tmp_legal_dong_delta
SET
	legal_dong_cd = NULLIF(btrim(legal_dong_cd), ''),
	legal_dong_nm = NULLIF(btrim(legal_dong_nm), ''),
	ctprv_cd  = NULLIF(btrim(ctprv_cd), ''),
	ctprv_nm  = NULLIF(btrim(ctprv_nm), ''),
	sgng_cd   = NULLIF(btrim(sgng_cd), ''),
	sgng_nm   = NULLIF(btrim(sgng_nm), ''),
	emndn_cd  = NULLIF(btrim(emndn_cd), ''),
	emndn_nm  = NULLIF(btrim(emndn_nm), ''),
	li_cd     = NULLIF(btrim(li_cd), ''),
	li_nm     = NULLIF(btrim(li_nm), ''),
	cr_dt     = NULLIF(btrim(cr_dt), ''),
	dlt_dt    = NULLIF(btrim(dlt_dt), '');

-- 안전장치: eff_dt 형식 검증(8자리 숫자)
DO $$
BEGIN
	IF length(:'eff_dt') <> 8 OR translate(:'eff_dt', '0123456789', '') <> '' THEN
		RAISE EXCEPTION 'eff_dt는 yyyyMMdd(8자리 숫자)여야 합니다: %', :'eff_dt';
	END IF;
END $$;

-- 안전장치: 각 행은 cr_dt=eff_dt 또는 dlt_dt=eff_dt를 만족해야 한다.
DO $$
DECLARE v_bad_cnt integer;
BEGIN
	SELECT count(*) INTO v_bad_cnt
	FROM tmp_legal_dong_delta
	WHERE legal_dong_cd IS NOT NULL
	  AND (cr_dt IS NULL OR cr_dt <> :'eff_dt')
	  AND (dlt_dt IS NULL OR dlt_dt <> :'eff_dt');

	IF v_bad_cnt > 0 THEN
		RAISE EXCEPTION 'CSV에 eff_dt(%)와 무관한 행이 포함되어 있습니다(건수=%).', :'eff_dt', v_bad_cnt;
	END IF;
END $$;

-- 업서트
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
FROM tmp_legal_dong_delta t
WHERE t.legal_dong_cd IS NOT NULL
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
	-- 생성일자(cr_dt)는 최소값 유지
	cr_dt = CASE
		WHEN tb_legal_dong_l.cr_dt IS NULL THEN EXCLUDED.cr_dt
		WHEN EXCLUDED.cr_dt IS NULL THEN tb_legal_dong_l.cr_dt
		ELSE LEAST(tb_legal_dong_l.cr_dt, EXCLUDED.cr_dt)
	END,
	-- 말소일자(dlt_dt)는 최대값 유지
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
	last_upusr_id = :'operator_id';

COMMIT;

-- past_legal_dong_cd 적용(시행일 단일)
-- - 별도 트랜잭션으로 수행(스크립트 내부에 BEGIN/COMMIT 포함)
\set eff_dt :eff_dt
\set operator_id :operator_id
\i scripts/legal_dong_past_mapping_apply.sql

