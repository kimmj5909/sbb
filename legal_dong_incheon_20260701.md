# 인천 법정동 마이그레이션 테스트 가이드 (시행일: 2026-07-01)

목표
- 운영 타겟 테이블에 업서트하기 전에, SBB 환경에서 **`legal_dong_cd`(10자리) 기반**으로
  인천 개편(중구/동구/서구 → 제물포구/영종구/서구/검단구) 마이그레이션을 먼저 검증한다.

전제
- 시행일(효력일): `20260701`
- 인천 변경 이력 파일(매핑 근거):
  - `행정기관(행정동) 및 관할구역(법정동) 변경 상세내역(인천광역시).xlsx`
- 업서트 입력 원본(정답 데이터)은 MOIS `KIKmix.20260701(말소코드포함).xlsx` 또는 동등한 CSV(8컬럼)로 준비한다.
  - 8컬럼: `행정동코드, 시도명, 시군구명, 읍면동명, 법정동코드, 동리명, 생성일자, 말소일자`

---

## 1) 인천 “구/법정동코드 재배치” 매핑 CSV 생성

이 단계는 **past_legal_dong_cd 보정** 및 **주소/연계 데이터 코드 치환**의 근거 매핑을 만든다.

- 실행
  - `python3 scripts/incheon_20260701_extract_mapping.py`
- 산출물
  - `scripts/out/incheon_20260701_admin_bjd_mapping.csv`
  - `scripts/out/incheon_20260701_affected_sgng_codes.csv`
  - `scripts/out/incheon_20260701_delta_8cols.csv` (KIKmix 8컬럼 “델타”. 전체 스냅샷 아님)

매핑 규칙
- 엑셀의 법정동코드 값은 `10100` 같은 **5자리 꼬리값** 형태이므로,
  `legal_dong_cd(10) = (기관명에 포함된 시군구코드 5자리) + (꼬리값 5자리)`로 결합한다.

---

## 2) SBB에 마이그레이션 “테스트 업서트” 수행(legal_dong_cd 기반)

권장 흐름(SBB 테스트 단계)
- (권장) SBB 관리자 화면 `/admin/legal-dong/migration`에서
  - KIKmix(또는 동일 구조) xlsx/csv 업로드 → 미리보기 → **stage 테이블 반영** 순서로 검증한다.
- (대안) 이미 `*.derived.csv`를 확보한 경우(=SBB 미리보기에서 다운로드한 CSV),
  `tb_legal_dong_stage_l`에 직접 업서트해서 DB 레벨로 빠르게 비교할 수 있다.
  - 업서트 스크립트: `scripts/legal_dong_upsert_from_csv_stage.sql`

예시(psql, derived.csv → stage 업서트)
- `psql "postgresql://USER:PASSWORD@HOST:5432/DB" -v csv_path="C:/path/file.derived.csv" -v operator_id="ADMIN" -f scripts/legal_dong_upsert_from_csv_stage.sql`

원본 KIKmix xlsx → 8컬럼 CSV 변환(필요 시)
- `python3 scripts/kikmix_xlsx_to_8cols_csv.py --xlsx "KIKmix.20260201(말소코드포함)-인천.xlsx" --ctprv "인천광역시" --out "scripts/out/kikmix_20260201_incheon_8cols.csv"`

점검 포인트
- 신설 구 코드(예: `28125` 제물포구, `28155` 영종구, `28290` 검단구) 관련 법정동이 `cr_dt=20260701`로 생성되는지
- 분할/이관되는 기존 법정동이 `dlt_dt=20260701`로 말소 처리되는지
- `rank` 규칙이 화성시 기준과 동일하게 유지되는지(상위 dense_rank / 하위 row_number)

---

## 3) (선택) past_legal_dong_cd 반영(스테이지 테이블)

신코드가 업서트된 뒤, 매핑 CSV를 이용해 past를 채운다.

- `psql ... -v map_csv="C:/.../scripts/out/incheon_20260701_admin_bjd_mapping.csv" -v operator_id="ADMIN" -f scripts/incheon_20260701_past_mapping_apply_stage.sql`
