-- 법정동 마이그레이션(운영: INSERT/UPDATE만) - DBeaver 실행용 / 스테이징·TEMP 테이블 불필요
--
-- 요구사항/제약
-- - 운영 DB에서 테이블 생성(영구/임시 포함) 로그가 남아 허용되지 않음.
-- - 따라서 "CSV 내용을 SQL의 VALUES 인라인 테이블"로 넣고, 그 범위만 업서트+past 반영을 수행한다.
-- - INSERT/UPDATE만 수행(DDL 없음).  ※ 이 파일 자체는 단순 SQL이며 CREATE TABLE 등을 포함하지 않는다.
--
-- 입력 데이터(엑셀→CSV 변환, 동일 컬럼)
-- - 행정동코드, 시도명, 시군구명, 읍면동명, 법정동코드, 동리명, 생성일자, 말소일자
--
-- 사용 방법(DBeaver)
-- 1) 아래 input CTE의 VALUES (...) 부분에 CSV를 변환한 행들을 붙여 넣는다(수백건 수준 권장).
--    - 각 행은 8개 컬럼 순서를 반드시 지켜야 한다.
-- 2) 전체 스크립트를 실행한다.
--
-- CSV → VALUES 변환 팁(예시)
-- - DBeaver에서 CSV 파일을 열고(또는 결과 그리드에 로드 후),
--   "Export Data" → "SQL" → "INSERT" 또는 "VALUES" 형태로 내보낸 뒤,
--   VALUES (...) 목록만 이 스크립트에 붙여 넣는다.
--
-- 작업 범위
-- - input에 포함된 legal_dong_cd(=법정동코드 10자리) 범위만 업서트/과거코드 반영 대상으로 삼는다.
--
-- 주의
-- - cr_dt/dlt_dt는 yyyyMMdd(8자리) 문자열로 관리한다(yyyy-mm-dd도 숫자만 추출).
-- - past 반영은 안전 우선:
--   - old.dlt_dt = eff_dt(=시행일)인 말소 코드만 과거 후보
--   - new.cr_dt = eff_dt이고 past가 NULL인 신규 코드만 대상
--   - 최고 점수 유니크(top_ties=1)인 케이스만 자동 반영
--
SET client_encoding = 'UTF8';

WITH
params AS (
	SELECT
		'DBEAVER'::text AS operator_id
),
input_raw(
	admin_cd_raw,
	ctprv_nm_raw,
	sgng_nm_raw,
	emndn_nm_raw,
	legal_cd_raw,
	li_nm_raw,
	cr_dt_raw,
	dlt_dt_raw
) AS (
	-- TODO: 여기에 VALUES 목록을 붙여 넣으세요.
	-- VALUES
	-- ('4159042000','경기도','화성시','동탄면','4159042000','동탄면','20010321','20180122'),
	-- ('4159042000','경기도','화성시','동탄면','4159042021','오산리','20010321','20150102')
	SELECT NULL::text,NULL::text,NULL::text,NULL::text,NULL::text,NULL::text,NULL::text,NULL::text
	LIMIT 0
),
normalized AS (
	SELECT
		NULLIF(btrim(ctprv_nm_raw), '') AS ctprv_nm,
		NULLIF(btrim(sgng_nm_raw), '') AS sgng_nm,
		NULLIF(btrim(emndn_nm_raw), '') AS emndn_nm_raw,
		NULLIF(btrim(li_nm_raw), '') AS li_nm_raw,
		regexp_replace(coalesce(legal_cd_raw, ''), '[^0-9]', '', 'g') AS legal_dong_cd,
		NULLIF(regexp_replace(coalesce(cr_dt_raw, ''), '[^0-9]', '', 'g'), '') AS cr_dt_digits,
		NULLIF(regexp_replace(coalesce(dlt_dt_raw, ''), '[^0-9]', '', 'g'), '') AS dlt_dt_digits
	FROM input_raw
),
validated AS (
	SELECT
		ctprv_nm,
		sgng_nm,
		CASE
			WHEN length(legal_dong_cd) = 10 AND right(legal_dong_cd, 2) = '00'
			THEN coalesce(emndn_nm_raw, li_nm_raw)  -- 상위(리 없음) 행 보정
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
	JOIN tb_legal_dong_l o ON o.dlt_dt = e.eff_dt
	WHERE o.li_cd IS NOT NULL
	GROUP BY e.eff_dt, substring(o.legal_dong_cd, 1, 8)
),
new_li_tails AS (
	SELECT
		e.eff_dt,
		substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd8,
		array_agg(DISTINCT right(n.legal_dong_cd, 2) ORDER BY right(n.legal_dong_cd, 2)) AS new_tail2s
	FROM eff e
	JOIN tb_legal_dong_l n ON n.cr_dt = e.eff_dt
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
	JOIN tb_legal_dong_l o ON o.dlt_dt = e.eff_dt
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
	JOIN tb_legal_dong_l n ON n.cr_dt = e.eff_dt
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
	UPDATE tb_legal_dong_l t
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
	FROM tb_legal_dong_l n
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
	FROM tb_legal_dong_l o
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
	UPDATE tb_legal_dong_l t
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
	(SELECT count(*) FROM li_updated) AS past_li_updated;

