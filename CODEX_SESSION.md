# Codex 세션 메모 (SBB)

이 파일은 Codex 작업을 “이어서” 진행할 때 컨텍스트를 빠르게 복원하기 위한 요약 메모입니다.  
민감정보(비밀번호/토큰)는 기록하지 않습니다.

## 저장소/브랜치
- Repo: `SBB`
- Branch: `sbb`

## 핵심 결정(운영 규칙)
- **Elasticsearch는 로그 콘솔/로그 적재 용도만 사용**(법정동 기능은 DB만 사용).
- **ES 계정/패스워드 관련 설정은 자동 수정 금지**(사용자 요청).
- `cr_dt`, `dlt_dt`는 DB에는 **`YYYYMMDD`(8자리) 문자열(varchar)** 로 저장.
  - 관리자 검색 화면 출력만 `YYYY-MM-DD`로 포맷.
- DevTools restart는 환경에 따라 클래스 로딩 문제가 있어 기본 비활성화:
  - `spring.devtools.restart.enabled=false`
- 템플릿은 폭 부족 줄바꿈 완화를 위해 전반적으로 `container` → `container-fluid ...` 적용.

## 법정동(legal dong) 기능 요약
- 마이그레이션: `/admin/legal-dong/migration`
  - xlsx 업로드 → 미리보기(세션 저장) → DB 적용
  - 미리보기 결과를 CSV로 다운로드: `/admin/legal-dong/migration/preview/csv`
- DB: `tb_legal_dong_l`
  - 기동 시 테이블 없으면 자동 생성(`CREATE TABLE IF NOT EXISTS`) + 컬럼 보강
  - 업서트는 JDBC batch + `ON CONFLICT`
  - `dlt_dt`가 null일 때 PostgreSQL 파라미터 타입추론 문제 방지:
    - SQL `CAST(:dltDt AS varchar)` + 바인딩 `Types.VARCHAR`
- 검색: `/admin/legal-dong/search`
  - size 선택: 40/100/200
  - 목록 인라인 수정: 과거코드/말소일자 편집 후 저장
    - `POST /admin/legal-dong/update`
    - 입력 필드는 숫자만/길이 제한(pattern+maxlength+JS sanitize)

## CSV 기반 업서트(.sql)
- `scripts/legal_dong_upsert_from_csv.sql`
  - psql `\\copy`로 CSV 로드 후 `tb_legal_dong_l`에 업서트
  - CSV는 관리자 화면에서 내려받은 `*.derived.csv` 사용

## Elasticsearch(로그 콘솔) 설정
- 외부 설정 파일로 관리(소스에서 비밀번호 하드코딩하지 않음):
  - `config/application.properties` (gitignore 처리)
  - 예시: `config/application.properties.example`
- 실행 환경변수 방식도 가능:
  - `SBB_ELASTICSEARCH_USERNAME`, `SBB_ELASTICSEARCH_PASSWORD`

## 빌드/테스트 메모
- 전체 `./gradlew test`는 PostgreSQL 미기동이면 `SbbApplicationTests`가 실패할 수 있음.
- 법정동 관련 테스트만 실행:
  - `./gradlew test --tests com.mysite.sbb.legaldong.*`

## 최근 커밋(요약)
아래 커밋들이 이 세션에서 누적된 주요 변경입니다(필요 시 `git show <hash>`로 확인).
- `c5695cd` legaldong/log/admin 기능 큰 묶음 반영
- `a682128` 미리보기 CSV 다운로드 + CSV 업서트 SQL 추가
- `ca40586` 검색 size 선택(40/100/200) + 인라인 수정
- `dd19582` 인라인 수정 후 redirect 템플릿 오류 수정
- `e572636` 관리자/로그 화면 container-fluid 확장
- `3085c0f` 템플릿 전반 container-fluid 확장
- `279a08b` CHANGELOG에서 ES는 로그용으로만 정리(문서)
- `8329423` 인라인 입력(과거코드/말소일자) 숫자 제한 강화
- `650fb9a` ES 비밀번호 외부 설정(config)로 분리(.gitignore + example)

