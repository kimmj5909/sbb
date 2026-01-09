# 변경 이력

## 2026-01-09
- 마이그레이션 미리보기에서 DB 스냅샷 대비 `cr_dt(LEAST)`/`dlt_dt(GREATEST)` 갱신 필요 여부를 셀 단위로 표시해, 일자 정합성(누락/오입력) 확인을 강화.
- 미리보기 CSV 다운로드의 한글 깨짐/파일명 깨짐을 줄이기 위해 UTF-8 BOM, `filename*` 헤더, `text/plain;charset=UTF-8` 응답을 적용.
- `/admin/legal-dong/migration/preview` 업로드 요청에서 파일 파트 누락 시 예외 로그가 발생하지 않도록 방어 로직을 보강.
- DB 적용 결과에 시행일별 과거코드 매핑 애매/누락/말소 리 누락 카운트를 추가로 출력해, 신규 코드 `past_legal_dong_cd` 미반영 원인 추적을 지원.
- 특정 신규 코드 1건 기준으로 과거코드 후보/점수/유니크 매핑 가능 여부를 확인할 수 있는 디버그 SQL(`scripts/legal_dong_past_mapping_debug_one.sql`)을 추가.
- 과거코드 매핑 결과에 시행일별 말소(구) 읍면동/리 건수를 함께 출력하고, `sgng_nm` 공백 유무와 무관하게 `...시/군` 기준으로 base_city를 정규화해(예: `화성시만세구`) 후보 매칭 누락을 완화.
- 과거코드 매핑의 base_city 정규화를 `...시/군` 루트명(예: `화성`) 기준으로 보강해 군↔시 전환(`화성군`→`화성시`) 및 시군구명 접두어 차이로 인한 후보 누락을 추가로 완화.
- 과거코드 매핑 진단(애매/누락) 집계를 후보 개수 기준이 아니라 `max_score/top_ties` 기준으로 정정해, “후보는 여러 개지만 최고 점수가 유니크한 케이스”가 애매로 잘못 표시되지 않도록 수정.
- 법정동 마이그레이션(DB 적용) 실행 단위 스냅샷(runId)을 저장하고, 관리자 화면에서 `이번 적용 롤백` 버튼으로 직전 적용을 원복(신규코드 삭제/기존코드 복원)할 수 있도록 롤백 기능을 추가.
- 법정동 검색/조회 화면에서 인라인 편집 영역(탭/확장 UI)을 제거하고, "사용여부" 옆 `편집` 컬럼의 버튼 클릭 시 오버레이(모달)로 과거코드/말소일자를 수정할 수 있도록 UX를 개선.
- DBeaver에서 CSV(엑셀 동일 컬럼) Import 후 스크립트만으로 업서트+과거코드 자동반영을 수행할 수 있도록 `scripts/legal_dong_migrate_from_csv_dbeaver.sql`을 추가.

## 2026-01-08
- 법정동 마이그레이션 DB 적용 시, 시행일(cr_dt) 기준으로 신규 코드의 `past_legal_dong_cd`를 자동 업데이트하도록 반영(유니크 매핑만 자동 적용, 애매/누락은 미반영).
- DevTools RestartClassLoader 환경에서 간헐적으로 `ClassNotFoundException`이 발생하는 이슈가 있어, `spring-boot-devtools` 의존성을 제거해 기동 안정성을 확보(템플릿 캐시는 `spring.thymeleaf.cache=false`로 유지).
- 법정동 마이그레이션 "적용 결과"를 업서트 대상/변경 건수 중심으로 요약하고(신규 추가/말소일자 업데이트 등), 상세 내역은 리스트 형태로 출력하도록 UI/서비스를 정리.
- 운영 환경에서 "말소/생성되는 코드만" 적재하는 배치를 위해 델타 CSV 업서트 + 시행일 기준 past 자동 반영 스크립트(`scripts/legal_dong_upsert_delta_from_csv.sql`)를 추가.
- 마이그레이션 미리보기 목록에 DB의 현재 생성/말소일자와, 업서트 규칙 기준으로 일자 변경 필요 여부(CR/DLT OK/변경)를 함께 표시하도록 컬럼을 추가.
- 마이그레이션 미리보기 컬럼을 `행/법정동코드/법정동명/rank/생성일자/말소일자/과거법정동코드`로 정리하고, 표시 개수 선택(50/200/400) 및 중복코드 목록 경고 출력을 추가.

## 2026-01-07
- 법정동 마이그레이션 미리보기 결과를 CSV로 다운로드할 수 있도록 `/admin/legal-dong/migration/preview/csv` 엔드포인트와 UI 버튼을 추가.
- 미리보기 CSV를 psql `\\copy`로 적재해 `tb_legal_dong_l`에 `ON CONFLICT` 업서트하는 스크립트 `scripts/legal_dong_upsert_from_csv.sql`을 추가.
- 법정동 검색/조회 화면(`/admin/legal-dong/search`)에 페이지 표시 개수 선택(40/100/200)을 추가하고, 목록에서 과거코드/말소일자를 인라인으로 수정할 수 있도록 관리자 수정 API를 추가.
- 템플릿 전반의 본문 컨테이너를 `container-fluid`로 확장해 테이블 줄바꿈/폭 부족 이슈를 완화.
- Elasticsearch 비밀번호를 소스(`src/main/resources/application.properties`)에 하드코딩하지 않고 로컬 외부 설정(`config/application.properties`)으로 관리할 수 있도록 예시 파일과 `.gitignore`를 추가.
- Codex 작업 컨텍스트를 보존하기 위한 `CODEX_SESSION.md`를 추가.
- 엑셀/CSV 입력에서 동일 `legal_dong_cd`가 중복 등장할 수 있어, DB 적재 전 중복 행을 병합(cr_dt 최소값, dlt_dt 최대값)하고 업서트에서도 동일 규칙을 적용해 말소일 누락/불일치로 인한 매핑 실패를 방지.
- 과거법정동코드 자동 매핑 검증을 위해 시행일 기준 후보/애매/누락 케이스를 확인할 수 있는 SQL(`scripts/legal_dong_past_mapping_preview.sql`)을 추가.

## 2026-01-06
- 법정동 검색/조회 화면(`/admin/legal-dong/search`)을 DB 실시간 조회(`tb_legal_dong_l`) 방식으로 제공하고, 별도 색인/동기화 작업 없이 최신 데이터를 즉시 반영하도록 정리.
- 마이그레이션 적용은 DB 반영 결과만 표시하도록 UI/서비스를 정리.
- 법정동 검색 조건에 사용여부(전체/사용/미사용) 필터를 추가하고, `use_yn` 컬럼은 화면에서 `사용(Y)`/`미사용(null)`으로 표시되도록 개선.
- 법정동 마이그레이션 시 읍면동 단위 행의 명칭이 `동리명` 컬럼에 들어오는 케이스를 반영해 `emndn_nm` 매핑을 보강하고, 읍면동 코드가 있는데 명칭이 비어 있는 입력은 경고로 수집.
- `cr_dt`/`dlt_dt`를 yyyyMMdd 문자열(varchar)로 적재하도록 업서트/검색 쿼리를 정리하고, 날짜 범위 검색은 `to_date(..., 'YYYYMMDD')`로 비교하도록 개선.
- PostgreSQL에서 null 문자열 파라미터가 `unknown`으로 처리되며 `? IS NULL` 형태에서 타입 추론 실패가 발생할 수 있어, 업서트 바인딩 타입(Types.VARCHAR)과 SQL CAST로 "매개 변수 자료형" 오류를 방지.
- DevTools 재기동(RestartClassLoader) 환경에서 특정 클래스 로딩이 불안정한 케이스가 있어, `spring.devtools.restart.enabled=false`로 재시작 기능을 비활성화(템플릿 캐시 비활성으로 개발 편의는 유지).
- 프로젝트 루트의 `jdk-23.0.2`를 Gradle 실행 JDK로 사용하도록 `gradlew`/`gradlew.bat`에서 자동 탐지해 JAVA_HOME을 설정하도록 보강.
- `JAVA_HOME`/PATH가 비어 있는 환경에서도 `./gradlew`/`gradlew.bat`가 루트의 `jdk-23.0.2`를 자동 탐지해 실행하도록 래퍼 스크립트를 보강.
- 홈 디렉터리 쓰기 제약이 있는 환경에서도 Gradle Wrapper가 동작하도록 `GRADLE_USER_HOME` 기본값을 프로젝트 루트 `.gradle/`로 지정.
- `tb_legal_dong_l` 테이블이 없으면 기동 시 `CREATE TABLE IF NOT EXISTS`로 자동 생성하고, 과거 스키마로 생성돼 컬럼이 누락된 경우 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`로 필수 컬럼을 방어적으로 추가하도록 스키마 초기화 로직을 개선.
- DevTools 재기동 환경에서 마이그레이션 결과 타입을 내부 클래스로 참조할 때 `NoClassDefFoundError`가 발생할 수 있어, 미리보기/적용 결과 DTO를 top-level 클래스로 분리.

## 2026-01-05
- 관리자 화면에 법정동 코드 마이그레이션(`/admin/legal-dong/migration`)을 추가하고, 엑셀(xlsx) 업로드로 데이터를 파싱·미리보기·DB 반영까지 수행하도록 구현.
- 법정동코드를 10자리 문자열로 정규화한 뒤 단위별 꼬리값 000/00 규칙에 따라 하위 단위 코드·명칭을 null 처리하고, 상위/하위 rank를 각각 1,2,3...으로 별도 카운트해 단일 컬럼에 저장하도록 반영.
- 전국 단위 파일도 처리할 수 있도록 PostgreSQL `ON CONFLICT` 기반 JDBC batch 업서트를 적용하고, 말소 여부는 `dlt_dt`로만 판단하며 `use_yn`은 기본 null 유지하도록 확정.
- 대용량 엑셀 업로드를 위해 `spring.servlet.multipart.max-file-size/max-request-size` 기본값을 50MB/60MB로 상향.
- 테스트/운영 환경에서 `tb_legal_dong_l` 테이블이 없으면 애플리케이션 시작 시점에 자동 생성하도록 초기화 로직을 추가.
- 마이그레이션 미리보기 화면에서 변환된 전체 데이터를 20개 단위로 페이징 조회할 수 있도록 개선하고, 페이지 이동 시 업로드 파일을 세션에 임시 보관해 재업로드 없이 탐색하도록 지원.
- Elasticsearch 로그 인덱스 prefix 설정값에 공백이 포함돼 `invalid_index_name_exception`이 발생할 수 있어, `sbb.elasticsearch.index-prefix`를 정규화(trim/sanitize)하고 기본값을 안전하게 보정하도록 개선.
- 법정동 검색/조회 화면에서 조회 필드를 확장(법정동코드/법정동명/시도명/시군구명/읍면동명/리명/생성일자/삭제일자/과거법정동코드)하고, 각 조건별 부분검색 및 페이징 조회를 지원.
- 법정동 검색/조회 조건 입력을 다중 필드 입력 방식에서 "조건 선택박스 + 키워드" 방식으로 변경하고, 날짜 조건도 선택박스로 생성/삭제일자를 선택해 범위 검색하도록 개선.
- `legal_dong_mig.md` 추가 요구사항에 맞춰 rank 계산 규칙을 조정: 시군구(sgng) 내 읍면동(emndn) 상위 rank는 1,2,3...으로 증가하고, 리(li) 하위 rank는 읍면동 그룹마다 1부터 재카운트하도록 변경.
- 기존 데이터의 `dlt_dt`가 null인 상태에서 마이그레이션 파일에 말소일자가 들어오면 동일 `legal_dong_cd` 기준으로 `dlt_dt`만 갱신하고(`use_yn`은 null 유지), 기존 `dlt_dt`가 이미 존재하면 덮어쓰지 않도록 업데이트 조건을 단순화.
- `use_yn` 규칙을 "말소일자 없음 = 'Y', 말소일자 존재 = null"로 정리하고, 기존 데이터에 말소일자를 업데이트하는 경우 기존 'Y' 값을 null로 전환하도록 업서트 로직을 보강.

## 2025-12-05
- Elasticsearch REST 클라이언트에 Basic 인증을 적용해 `sbb.elasticsearch.username/password` 설정 시 보안 클러스터에서도 로그 적재·검색이 가능하도록 수정(미지정 시 기존 무인증 방식 유지).
- ES 호스트 설정 키를 `sbb.elasticsearch.hosts`로 통일하고 과거 `urls` 키는 호환 세터로 흡수, 연결/소켓 타임아웃 값을 설정에서 전달하도록 정리.

## 2025-12-03
- ES 호스트 기본값을 127.0.0.1, localhost 순으로 단순화해 IPv6/내부 IP 우선 시도에 따른 Connection refused 가능성을 줄임.
- 로컬 환경에서 127.0.0.1 접속이 차단되는 경우를 위해 ES 기본 호스트를 localhost 단일값으로 변경.
- Elasticsearch 호스트를 복수로 설정할 수 있게(`sbb.elasticsearch.hosts`) 하고 기본값에 localhost/127.0.0.1을 모두 포함해 IPv4/IPv6/포워딩 이슈로 인한 Connection refused 가능성을 낮춤.
- ES REST 클라이언트 설정에서 타임아웃 커스텀을 제거해 httpclient4/5 혼용 시그니처 충돌을 방지하고 기본 설정으로 연결하도록 정리.
- 로그 콘솔에서 ES 연결 실패 시 500 오류 대신 친절한 경고 메시지를 노출하고 빈 결과를 표시하도록 방어 로직을 추가.
- 로그 콘솔 화면을 추가하고 Elasticsearch Java 클라이언트 설정을 도입해 `logs-web*` 데이터 스트림을 검색·집계할 수 있도록 백엔드/프런트엔드를 연결.
- HTTP 요청을 인터셉터로 수집해 ES에 적재하는 기반 클래스(모델, 인터셉터, WebMvc 설정, 적재 서비스)를 추가하고 콘솔에 HTTP 메타데이터 컬럼을 노출하는 준비를 완료.
- SecurityContext와 user 테이블을 이용해 로그인 사용자 ID를 추출하도록 인터셉터에 사용자 연동을 적용.
- 인터셉터/적재 서비스/모델에 기능별 주석을 추가하고 정적 리소스 제외 정책을 명시해 유지보수를 용이하게 함.
- `seed_es_dummy_logs.sh`가 데이터 스트림 템플릿이 적용된 ES에서 bulk 주입 시 `op_type` 오류로 실패하던 문제를 create 액션과 데이터 스트림 엔드포인트를 사용하도록 수정.
- Elasticsearch 개발 클러스터에 더미 웹/WAS 로그를 손쉽게 주입할 수 있도록 `scripts/seed_es_dummy_logs.sh` 스크립트를 추가하고 실행 권한을 부여.
- Bulk API 호출 전 헬스 체크를 포함하고 최근 3일치 샘플 로그를 `logs-web-YYYY.MM.DD` 인덱스에 적재한 뒤 검색 결과를 바로 확인하도록 구성.

## 2025-10-29
- `signup_form.html` 연락처 입력 필드에 숫자 전용 패턴·자리수 제한·안내 문구를 추가해 사용자가 10~11자리 숫자만 입력하도록 유도.
- 회원가입 화면에 연락처 입력 시 즉시 숫자만 남기도록 하는 sanitize 스크립트를 추가해 잘못된 문자를 자동으로 제거.
- `UserCreateForm`에 연락처 숫자 패턴 검증 애노테이션과 주석을 보강해 프런트 제약과 백엔드 검증이 일관되도록 조정.

## 2025-10-28
- `Question` 엔티티 컬렉션을 미리 초기화하고 댓글/답변 집계 시 null 방어 로직을 추가해 `/question/list` 진입 시 발생하던 500 오류를 차단.
- `SiteUser` Boolean 권한 플래그에 기본값을 부여하고 `isAdmin()`/`ensureRoleInitialized()` 헬퍼로 관리자 여부를 안정적으로 판별.
- `UserService`, `UserSecurityService`를 확장해 사용자 목록 조회·권한 갱신·Spring Security 권한 매핑을 Boolean 기반으로 처리.
- `Question` 엔티티에 `notice` 컬럼을 추가하고 `QuestionService`에 공지 우선 정렬, 일괄 공지/삭제 기능을 구현.
- `/admin` 대시보드에서 게시글 공지 토글, 선택 삭제, 사용자 권한 변경을 제공하며 관리자 배지와 일괄 선택 UI를 구성.
- 질문 목록(`question_list.html`)에 공지 배지와 연한 배경·굵은 글씨를 적용해 공지를 최상단에 노출.
- 네비게이션 우측에 로그인 사용자를 표시하고, 관리자 계정은 `[관리자] 아이디`를 굵게 노출.
- `navbar.html`에서 `${#authentication.name}` 형태로 인증 표현식을 감싸 템플릿 파싱 예외를 제거하고, 인증 정보 표시 주석을 보강.
- 관리자 대시보드에 50·100·200개 옵션을 가진 페이지당 게시물 선택을 추가하고 페이징·체크박스 동기화를 개선해 대량 게시물 관리 시 UX를 강화.
- 관리자 대시보드 페이징을 `이전/앞단 점프/중앙 5개/뒤단 점프/다음` 구조로 꾸미고 10페이지 단위 점프·생략 표시 로직을 컨트롤러와 템플릿에 추가, 점프 버튼에는 실제 이동 페이지 번호를 노출하며 페이지네이션을 가운데 정렬.
- `CommentService`에 삭제 권한 검사 유틸리티를 추가하고 CommentController·ReplyRestController에서 재사용해 관리자 계정이 타인의 댓글을 삭제할 수 있도록 확장.
- 관리자 대시보드 사용자 관리 섹션에서 이메일·연락처를 즉시 편집할 수 있도록 입력 필드를 노출하고, 단일 저장 버튼으로 연락 정보·권한을 동시에 갱신하도록 컨트롤러/서비스를 확장.
- 로그인 폼(`login_form.html`)을 화면 중앙에 배치해 사용자 입력 필드가 가독성 있게 노출되도록 조정.

## 2025-02-08
- `question_list.html` 페이징 번호 계산식을 교정해 페이지 크기 10 선택 시 번호가 1로 고정되는 현상 방지.
- 게시글 수 셀렉트 박스 옵션 텍스트를 `n개` 포맷으로 고정하고 최소 너비를 지정해 선택 값이 `1`로 잘리는 문제 수정.
- `question_list.html` 원본을 `question_list.html.pre-fix-20250208`으로 백업해 변경 추적 자료 확보.
- `/question/replies/{id}` API를 DTO 기반으로 재정비하고 팝업 전용 뷰 `question_replies_popup.html`을 추가해 답글 보기를 별도 창으로 전환.
- `ReplyRestController` 추가 및 팝업 화면 비동기 로직으로 답변·댓글 작성/수정/삭제를 지원, CSRF·권한 검사를 반영해 새 창에서도 CRUD가 가능하도록 개선.
- Hibernate `MultipleBagFetchException`을 유발하던 다중 컬렉션 fetch join을 제거해 `/question/replies/popup/{id}` 500 오류를 해결.


## 2025-10-25
- `AGENTS.md` 신규 작성: 저장소 기여 가이드 한글 버전 정리.
- `QuestionController`, `QuestionService`, `question_list.html` 페이징 오류 방지 로직 개선: 0 미만 페이지 요청 방어, 활성 페이지 클릭 시 재요청 차단.
- 게시글 수 선택 기능 추가: `size` 파라미터 도입, 10/20/50개 옵션 제공, UI 선택 변경 시 자동 재조회 처리.

## 2025-10-27
- 엑셀 다운로드용 DTO(`QuestionExcelRow`) 도입과 `QuestionService#getExcelRows` 추가로 검색 조건을 유지한 데이터 추출 지원.
- `QuestionController`에서 Apache POI 스타일을 적용해 헤더 강조, 날짜 포맷, 자동 열 너비 조정을 수행하도록 개선.
- 엑셀 시트는 B열 2행부터 데이터를 배치하고 문자열 길이 기반 너비 계산으로 주요 열(A, B, E, F) 가독성을 확보.
- 클래스/메서드 매핑 중복을 제거해 `/question/excel/download` 호출 시 404가 발생하지 않도록 매핑 경로를 조정.
- `ReplyRestController` 및 팝업 템플릿, 서비스 메서드에 상세 주석을 추가해 유지보수성을 향상.
- 엑셀 헤더에 노란색 배경과 볼드체, 모든 데이터 셀에 기본 실선 테두리를 적용해 가독성을 높이고 자동 너비 조정으로 문자 길이에 맞게 열을 정돈.
- CKEditor5 본문 이미지 업로드를 위해 `/question/ckeditor/upload` 엔드포인트와 전용 저장/제공 로직을 추가하고, 에디터 초기화 스크립트에 simpleUpload 설정과 CSRF 헤더를 연동.
- CKEditor5 CDN을 41.4.2 클래식 빌드로 교체하고 초기화 스크립트에 버전 로깅을 추가해 호환성을 점검.
- 질문 목록 엑셀 다운로드 시 전용 DTO 매핑과 헤더/날짜 스타일을 적용하고, 답변·댓글 수 등 확장 필드를 선택적으로 포함할 수 있도록 개선.
- CKEditor5 npm 빌드 기반 이미지 업로드 시 simpleUpload와 CKFinder 설정을 동일 엔드포인트와 CSRF 헤더로 통합해 에디터 버튼 유형과 무관하게 업로드가 이루어지도록 정비.
- CSRF 토큰 탐색 로직을 숨은 필드(`data-csrf-token`)·메타 태그·`ckCsrfToken` 쿠키 순으로 보강하고, 메타의 `_csrf_header` 값을 활용해 `X-CSRF-TOKEN`·`X-XSRF-TOKEN`을 포함한 모든 헤더를 세팅하며 업로드 URL에도 `_csrf` 쿼리 파라미터를 추가해 어느 채널로든 토큰이 전달되도록 강화했다. 레이아웃에는 CSRF 메타 태그를 노출해 템플릿 어디서든 토큰을 참조할 수 있도록 정비했고, 토큰 부재 시 경고 로그로 진단을 돕는다.
- Spring Security에서 `CookieCsrfTokenRepository.withHttpOnlyFalse()`를 적용해 `ckCsrfToken`을 JavaScript에서 읽을 수 있게 하고, 프런트엔드가 CSRF 토큰을 헤더/쿼리로 전달할 수 있도록 구성해 CKEditor 업로드 403 문제를 근본적으로 해결.
- CKEditor 기본 업로드 어댑터를 fetch 기반 커스텀 구현으로 교체해 CSRF 헤더·쿼리를 동시에 전송하고, 업로드 실패 시 사용자 친화적 오류 메시지를 반환하도록 개선.
- 에디터 이미지 URL이 이중 인코딩돼 400이 발생하던 문제를 해결하기 위해 업로드 시 `UriComponentsBuilder`에 파일명을 위임하고, 다운로드 시 추가 디코딩을 제거해 저장된 파일명을 그대로 로드하도록 수정.
- 게시글 목록에서 첨부파일/이미지 여부를 사전 조회(`FileService#questionHasAttachment`, `questionHasImage`)와 본문 내 `<img>` 태그 검출을 조합해 판단하고, 제목 옆에 💾·🖼️ 이모지를 순서대로 표기해 댓글 수와 함께 시각적으로 구분되도록 개선.
