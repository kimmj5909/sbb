-- 과거법정동코드(past_legal_dong_cd) 자동 매핑 미리보기/적용 SQL (PostgreSQL)
--
-- 목적
-- - 시행일(=효력일) 기준으로 "미사용 전환(말소)"된 구 코드 → "신규 생성"된 신 코드에 대해
--   tb_legal_dong_l.past_legal_dong_cd 컬럼을 채운다.
-- - 법정동명/리명은 변경될 수 있으므로, 이름 완전일치에 의존하지 않고 다음 조건을 중심으로 방어적으로 매핑한다.
--   * 동일 시도(ctprv_cd)
--   * 동일 base_city(시군구명에서 첫 토큰: 예 '화성시', '용인시' 등)
--   * 동일 읍면동 루트명(emndn_nm에서 마지막 접미사 '읍/면/동/리/가' 제거 후 비교)
--   * 시행일: old.dlt_dt = eff_dt AND new.cr_dt = eff_dt
--
-- 전제
-- - tb_legal_dong_l: 1행=1법정동코드(legal_dong_cd PK)
-- - cr_dt/dlt_dt: yyyyMMdd(8자리) 문자열(varchar(8))
-- - "반드시 말소된 코드만" past로 넣기 위해 old.dlt_dt=eff_dt 조건을 강제한다.
--
-- 컬럼 설명(주요)
-- - new_*: 시행일에 생성된 신규 코드(=new.cr_dt = eff_dt)
-- - old_*: 시행일에 말소된 기존 코드(=old.dlt_dt = eff_dt)
-- - *_candidate_cnt / *_candidates10: 후보 개수/후보 10자리 코드 목록(검증용)
-- - chosen_*: 점수 기반 정렬에서 1순위로 선택된 코드(단, candidate_cnt>1이면 '애매 케이스'로 취급)
--
-- 사용법
-- 1) 아래 params.eff_dt 값을 원하는 시행일(yyyyMMdd)로 바꾼 뒤 실행한다.
-- 2) 결과에서 애매 케이스(candidate_cnt>1) / 누락 케이스(0)를 먼저 확인한다.
-- 3) 매핑이 확정된 케이스만 UPDATE를 실행한다(아래 "적용" 섹션 참조).

WITH
params AS (
	-- TODO: 시행일(효력일) 변경
	SELECT
		'20260201'::varchar(8) AS eff_dt
),
old_emndn AS (
	SELECT
		o.legal_dong_cd AS old_emndn_cd,
		o.ctprv_cd,
		o.ctprv_nm,
		o.sgng_cd,
		o.sgng_nm,
		regexp_replace(coalesce(o.sgng_nm, ''), '\\s.*$', '') AS old_base_city,
		o.emndn_cd,
		o.emndn_nm,
		regexp_replace(coalesce(o.emndn_nm, ''), '(읍|면|동|리|가)$', '') AS old_emndn_root,
		o.cr_dt,
		o.dlt_dt
	FROM tb_legal_dong_l o, params p
	WHERE o.li_cd IS NULL
	  AND o.emndn_cd IS NOT NULL
	  AND o.dlt_dt = p.eff_dt
),
new_emndn AS (
	SELECT
		n.legal_dong_cd AS new_emndn_cd,
		n.ctprv_cd,
		n.ctprv_nm,
		n.sgng_cd,
		n.sgng_nm,
		regexp_replace(coalesce(n.sgng_nm, ''), '\\s.*$', '') AS new_base_city,
		n.emndn_cd,
		n.emndn_nm,
		regexp_replace(coalesce(n.emndn_nm, ''), '(읍|면|동|리|가)$', '') AS new_emndn_root,
		n.cr_dt,
		n.dlt_dt,
		n.past_legal_dong_cd
	FROM tb_legal_dong_l n, params p
	WHERE n.li_cd IS NULL
	  AND n.emndn_cd IS NOT NULL
	  AND n.cr_dt = p.eff_dt
	  AND n.past_legal_dong_cd IS NULL
),
emndn_candidates AS (
	SELECT
		n.new_emndn_cd,
		o.old_emndn_cd,
		-- 점수(정렬용): sgng_nm 접두 관계가 더 강하면 우선한다.
		CASE
			WHEN n.sgng_nm IS NOT NULL AND o.sgng_nm IS NOT NULL AND n.sgng_nm = o.sgng_nm THEN 30
			WHEN n.sgng_nm IS NOT NULL AND o.sgng_nm IS NOT NULL AND n.sgng_nm LIKE o.sgng_nm || '%' THEN 20
			WHEN n.sgng_nm IS NOT NULL AND o.sgng_nm IS NOT NULL AND o.sgng_nm LIKE n.sgng_nm || '%' THEN 10
			ELSE 0
		END AS score
	FROM new_emndn n
	JOIN old_emndn o
	  ON o.ctprv_cd = n.ctprv_cd
	 AND o.old_base_city = n.new_base_city
	 AND o.old_emndn_root = n.new_emndn_root
),
emndn_preview AS (
	SELECT
		p.eff_dt,
		n.new_emndn_cd,
		n.ctprv_nm,
		n.sgng_nm AS new_sgng_nm,
		n.emndn_nm AS new_emndn_nm,
		count(c.old_emndn_cd) AS old_emndn_candidate_cnt,
		array_agg(c.old_emndn_cd ORDER BY c.score DESC, c.old_emndn_cd) AS old_emndn_candidates10,
		(array_agg(c.old_emndn_cd ORDER BY c.score DESC, c.old_emndn_cd))[1] AS chosen_old_emndn_cd
	FROM params p
	JOIN new_emndn n ON 1=1
	LEFT JOIN emndn_candidates c ON c.new_emndn_cd = n.new_emndn_cd
	GROUP BY p.eff_dt, n.new_emndn_cd, n.ctprv_nm, n.sgng_nm, n.emndn_nm
),
emndn_map_unique AS (
	SELECT
		e.eff_dt,
		e.new_emndn_cd,
		e.chosen_old_emndn_cd
	FROM emndn_preview e
	WHERE e.old_emndn_candidate_cnt = 1
),
new_li AS (
	SELECT
		n.legal_dong_cd AS new_li_cd,
		substring(n.legal_dong_cd, 1, 8) AS new_emndn_cd,
		right(n.legal_dong_cd, 2) AS li_tail2,
		n.ctprv_cd,
		n.ctprv_nm,
		n.sgng_nm,
		n.emndn_nm,
		n.li_nm,
		n.cr_dt,
		n.past_legal_dong_cd
	FROM tb_legal_dong_l n, params p
	WHERE n.li_cd IS NOT NULL
	  AND n.cr_dt = p.eff_dt
	  AND n.past_legal_dong_cd IS NULL
),
old_li AS (
	SELECT
		o.legal_dong_cd AS old_li_cd,
		substring(o.legal_dong_cd, 1, 8) AS old_emndn_cd,
		right(o.legal_dong_cd, 2) AS li_tail2,
		o.ctprv_cd,
		o.ctprv_nm,
		o.sgng_nm,
		o.emndn_nm,
		o.li_nm,
		o.dlt_dt
	FROM tb_legal_dong_l o, params p
	WHERE o.li_cd IS NOT NULL
	  AND o.dlt_dt = p.eff_dt
),
li_preview AS (
	SELECT
		p.eff_dt,
		n.new_emndn_cd,
		max(n.ctprv_nm) AS ctprv_nm,
		max(n.sgng_nm) AS new_sgng_nm,
		max(n.emndn_nm) AS new_emndn_nm,
		count(*) AS new_li_cnt,
		array_agg(n.new_li_cd ORDER BY n.new_li_cd) AS new_li_codes10,
		count(ol.old_li_cd) AS matched_old_li_cnt,
		array_agg(ol.old_li_cd ORDER BY ol.old_li_cd) FILTER (WHERE ol.old_li_cd IS NOT NULL) AS matched_old_li_codes10
	FROM params p
	JOIN new_li n ON 1=1
	LEFT JOIN emndn_map_unique m
	  ON m.new_emndn_cd = n.new_emndn_cd
	LEFT JOIN old_li ol
	  ON ol.old_emndn_cd = m.chosen_old_emndn_cd
	 AND ol.li_tail2 = n.li_tail2
	GROUP BY p.eff_dt, n.new_emndn_cd
),
missing_old_emndn AS (
	SELECT
		p.eff_dt,
		o.ctprv_nm,
		o.sgng_nm,
		o.emndn_nm,
		o.old_emndn_cd
	FROM params p
	JOIN old_emndn o ON 1=1
	LEFT JOIN emndn_candidates c
	  ON c.old_emndn_cd = o.old_emndn_cd
	WHERE c.old_emndn_cd IS NULL
)
-- 1) 읍면동(동 포함) 단위 매핑 후보 미리보기
SELECT
	eff_dt,
	new_emndn_cd,
	ctprv_nm,
	new_sgng_nm,
	new_emndn_nm,
	old_emndn_candidate_cnt,
	old_emndn_candidates10,
	chosen_old_emndn_cd
FROM emndn_preview
ORDER BY old_emndn_candidate_cnt DESC, new_emndn_cd;

-- 2) 리(하위) 단위 매핑(읍면동 유니크 매핑 기준) 미리보기 - emndn 단위로 array 집계
--    - matched_old_li_cnt < new_li_cnt 인 경우, (old_emndn_cd||tail2) 형태의 말소 코드가 누락된 것이므로 데이터 정합성 점검 필요.
SELECT
	eff_dt,
	new_emndn_cd,
	ctprv_nm,
	new_sgng_nm,
	new_emndn_nm,
	new_li_cnt,
	new_li_codes10,
	matched_old_li_cnt,
	coalesce(matched_old_li_codes10, ARRAY[]::varchar[]) AS matched_old_li_codes10
FROM li_preview
ORDER BY new_li_cnt DESC, new_emndn_cd;

-- 3) (참고) 시행일에 말소된 old_emndn 중, 신규 emndn 후보가 0인 코드 목록
SELECT
	eff_dt,
	ctprv_nm,
	sgng_nm,
	emndn_nm,
	old_emndn_cd
FROM missing_old_emndn
ORDER BY old_emndn_cd;

-- ======================
-- 적용(UPDATE) - 실행 전 반드시 백업/검증
-- ======================
-- 아래 UPDATE는 "유니크 매핑(후보 1개)"만 반영한다.
-- 애매 케이스(candidate_cnt>1)는 수동 확인 후 별도 조건/화이트리스트로 처리 권장.
--
-- WITH params AS (SELECT '20260201'::varchar(8) AS eff_dt),
-- ... (위 CTE 동일) ...
-- ,apply_targets AS (
--   -- emndn(동 포함) 단위
--   SELECT
--     n.new_emndn_cd AS new_legal_dong_cd,
--     m.chosen_old_emndn_cd AS past_legal_dong_cd
--   FROM new_emndn n
--   JOIN emndn_preview m ON m.new_emndn_cd = n.new_emndn_cd
--   WHERE m.old_emndn_candidate_cnt = 1
--   UNION ALL
--   -- li 단위 (읍면동 유니크 매핑 + tail2)
--   SELECT
--     nl.new_li_cd AS new_legal_dong_cd,
--     (m2.chosen_old_emndn_cd || nl.li_tail2) AS past_legal_dong_cd
--   FROM new_li nl
--   JOIN emndn_preview m2 ON m2.new_emndn_cd = nl.new_emndn_cd
--   WHERE m2.old_emndn_candidate_cnt = 1
-- )
-- UPDATE tb_legal_dong_l t
-- SET past_legal_dong_cd = a.past_legal_dong_cd,
--     last_updt_dtm = now(),
--     last_upusr_id = 'ADMIN'
-- FROM apply_targets a, params p
-- WHERE t.legal_dong_cd = a.new_legal_dong_cd
--   AND t.cr_dt = p.eff_dt
--   AND t.past_legal_dong_cd IS NULL;

