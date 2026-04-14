# 변경 이력

## 2026-03-03
- ELK(Filebeat+Logstash+Elasticsearch+Kibana) 설치 및 SBB 로그 연동 매뉴얼 문서(`docs/elk-manual.md`)를 추가.

## 2026-02-26
- 인천광역시 법정동 개편(시행일 2026-07-01) 사전 검증을 위해, 변경 상세내역 엑셀(`행정기관(행정동) 및 관할구역(법정동) 변경 상세내역(인천광역시).xlsx`)에서 구/신 법정동코드 재배치 매핑을 추출하는 스크립트(`scripts/incheon_20260701_extract_mapping.py`, KIKmix 8컬럼 델타 CSV 산출 포함)와, SBB stage 테이블(`tb_legal_dong_stage_l`)에 past_legal_dong_cd를 일괄 반영하는 psql 스크립트(`scripts/incheon_20260701_past_mapping_apply_stage.sql`), stage 업서트 스크립트(`scripts/legal_dong_upsert_from_csv_stage.sql`), KIKmix xlsx→8컬럼 CSV 변환 스크립트(`scripts/kikmix_xlsx_to_8cols_csv.py`), 관리자 마이그레이션 화면의 csv 업로드 지원 및 테스트 가이드(`legal_dong_incheon_20260701.md`)를 추가.
- Windows Excel에서 UTF-8 CSV를 ANSI로 오인해 한글이 깨지는 현상을 완화하기 위해, 인천 매핑/델타 CSV 및 KIKmix xlsx→csv 변환 스크립트 출력에 UTF-8 BOM을 기본 포함하도록 보강.

## 2026-02-09
- HTTP 요청 로깅 인터셉터(RequestLoggingInterceptor)가 생성하는 구조화 로그(HttpLogEvent)를 JSONL 파일로 저장할 수 있도록 `sbb.http-log.enabled`, `sbb.http-log.file` 설정과 파일 저장 로직을 추가해, Filebeat 기반 파이프라인(→Redis→Logstash→Elasticsearch)으로 적재하는 구성을 지원.

## 2026-02-03
- /etc 하위 Elasticsearch/Kibana/Filebeat/Logstash 설정을 기준으로, 애플리케이션 ELK 기본 접속 대상 값을 `localhost:9200`(Elasticsearch) / `localhost:5601`(Kibana)로 정리하고 `application.properties`에 문서화.
- Elasticsearch 보안(xpack security) 활성화 환경을 전제로, ES 인증 정보는 `ELASTIC_USERNAME`/`ELASTIC_PASSWORD` 환경변수로 주입하도록 변경해 소스 내 비밀번호 하드코딩을 제거.
- Elasticsearch 인증 방식으로 ApiKey(`ELASTIC_API_KEY`)를 추가 지원하고, 설정 시 BasicAuth보다 우선 적용되도록 RestClient 구성을 보강.
- Filebeat 수집을 고려해 애플리케이션 로그 파일 경로를 `logging.file.name`으로 노출하고, 필요 시 `SBB_LOG_FILE`로 `/var/log/*.log` 패턴에 맞춰 오버라이드할 수 있도록 안내 설정을 추가.
- `localhost` 해석/포워딩(IPv6 ::1, WSL localhost forwarding 등) 환경 차이가 있어, Elasticsearch 기본 접속 값은 `localhost:9200`으로 유지하고 필요 시 `ELASTIC_HOSTS`로 오버라이드하도록 안내를 추가.
- `LogSearchService`의 기간(range) 필터 빌더가 elasticsearch-java 클라이언트 버전에 따라 컴파일 오류가 날 수 있어, 쿼리 JSON 기반으로 고정해 빌드/IDE 환경 차이에 덜 민감하도록 수정.

## 2026-01-29
- 행정안전부(MOIS) "법정동 변경내역 알림" 게시판을 등록일 기준으로 모니터링하고(오늘-1일~오늘), 게시글 번호(nttId) 작업 이력으로 중복 처리를 방지하며, 신규 게시물의 첨부파일을 `{downloadBaseDir}/{게시물번호}_{YYYYMMDD}/` 하위에 자동 다운로드하는 스케줄러(기본 매일 16:00)와 실행 로그(성공/실패/변동없음) 적재를 추가.
- MOIS 법정동 변경내역 첨부파일 다운로드 기본 경로를 `C:\\Users\\kfca\\Desktop\\legal`로 변경.
- MOIS 게시판 최신 게시물 1건의 첨부파일을 수동 다운로드할 수 있도록 `./gradlew moisDownloadLatest` 실행 태스크와 점검용 Main을 추가.
- WSL/Linux 환경에서도 Windows 경로(`X:\\...`)가 의도한 위치(`/mnt/x/...`)로 저장되도록 다운로드 경로 해석을 보강하고, 깨진 Content-Disposition 파일명 대신 링크 텍스트 기반 파일명을 우선 사용하도록 개선.
- MOIS 첨부 링크 텍스트에 포함된 용량 표기(예: `파일명.ext [ 59.6 KB ]`)를 제거해, 확장자 뒤에 불필요한 문자열이 붙지 않도록 파일명 정규화를 보강.

## 2026-01-16
- 법정동 마이그레이션 미리보기에서 스냅샷 저장 시 `unnest()`에 코드 리스트가 개별 파라미터로 확장되어 PostgreSQL 함수 인자 100개 제한에 걸리던 문제를, SQL ARRAY(단일 파라미터)로 바인딩하도록 수정해 500 오류를 방지.
- `LegalDongMigrationService` 생성자 의존성 확장에 맞춰 `src/test` 단위 테스트가 컴파일되도록 생성자 인자를 보강.
- HTTP 요청 로깅 인터셉터에서 사용자 조회/Elasticsearch 적재를 동기 처리해 화면 응답이 지연될 수 있던 문제를, user DB 조회 제거 및 ES 적재 비동기 실행기로 분리해(업무 트래픽 비차단) 개선.
- `/admin/legal-dong/search` DB 검색 SQL 생성 시 문자열 결합과 `formatted()` 적용 순서 문제로 `FROM %s`가 그대로 남아 실행되던 문법 오류를 수정.
- `scripts/1_legal_dong_migrate_from_server_csv_proc.sql`에서 기존 `sp_legal_dong_migrate_from_server_csv`는 유지하고, `sp_legal_dong_migrate_from_server_csv2`에 서버-side `COPY` 적재 시 인코딩(UTF8/WIN949)·구분자(콤마/TAB) 조합을 순차 재시도하며, 일부 엑셀/내보내기 파일에서 행 전체가 큰따옴표로 감싸져 포맷 오류가 나는 케이스는 QUOTE 문자를 단일 따옴표로 변경(사실상 quoting 비활성)해 재시도하도록 보강하고, 적재 후 전체 컬럼의 BOM/큰따옴표를 제거하도록 정리했으며, 실제 사용한 인코딩/구분자/QUOTE 설정을 NOTICE로 출력해 원인 분리를 지원.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`에 주소 정규화 기반 스테이징 매핑(`pg_temp.tmp_legal_dong_past_map_stage`)을 추가해, 운영 테이블에 `dlt_dt` 스냅샷이 없어도 스테이징에서 계산한 과거코드(`would_set_past_cd`)와 rank(`would_set_rank`)를 신규 코드에 반영(`past_legal_dong_cd/rank` 업데이트)하고 카운트를 `pg_temp.tmp_legal_dong_migrate_result`로 확인 가능하도록 개선.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`의 읍면동 past 매핑 후보군에서 시군구 레벨(읍면동 000) 코드(예: `4159000000`)를 제외해, `새솔동`처럼 기존 과거 읍면동이 없는 케이스에서 past가 시군구 코드로 잘못 설정되는 오매핑을 방지.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`에서 입력 CSV에 시군구 레벨 코드(sgng_cd||`00000`)가 누락된 경우를 대비해, `...구`로 끝나는 시군구(`sgng_nm`)에 한해 시군구 레벨 행을 추가 생성해 업서트 대상에 포함시켜(예: `화성시 만세구/효행구/병점구/동탄구`) 누락을 방지.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`에서 `...구` 시군구의 sgng_cd||`00000` 레벨 코드를 스테이징 코드 목록(`tmp_legal_dong_migrate_stage_codes`)에도 자동 포함시켜, 미리보기/스테이징 조회에서 시군구 레벨 행이 누락된 것처럼 보이는 혼선을 줄임.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`의 주소 정규화 매핑 대상에 `리`까지 포함되도록, 신규/기존 매핑 후보를 `(동|리)$`로 끝나는 주소만 대상으로 필터링.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`에서 `UPDATE ... FROM` 구문 내 JOIN 조건에서 타깃 테이블 별칭을 참조해 발생하던 오류(42P01)를 방지하도록, JOIN 조건을 WHERE 절로 이동해 PostgreSQL 11.5에서 정상 실행되도록 수정.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`의 주소 정규화 매핑에서 신규/기존 분리 기준을 테스트 쿼리와 동일하게(`new: legal_dong_nm LIKE '%구 %'`, `old: NOT LIKE '%구 %'`) 적용하고, 매핑 대상 범위를 입력 스테이징 코드(`tmp_legal_dong_migrate_stage_codes`)로 한정해 미리보기 결과와 일치하도록 정리.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`의 주소 정규화 매핑에서 신규/기존 분리 조건을 공백 유무에 영향받지 않도록 `...구` 정규식 기반(`~ '[가-힣]+구'`)으로 보강하고, 과거 후보(old)는 스테이징 범위로 제한하지 않도록 조정해 매핑 누락을 줄임.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`의 주소 정규화 매핑을 스테이징 범위 내부에서만(new/old 모두 `tmp_legal_dong_migrate_stage_codes`) 수행하도록 재정리해, 테스트 쿼리에서 확인된 매핑 건수(예: 194건)와 프로시저 반영 건수 차이를 줄임.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql` 미리보기 모드에서 디버깅을 위해 `pg_temp.tmp_legal_dong_migrate_stage_codes` TEMP 테이블을 세션에 유지하도록 정리.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`의 주소 정규화 기반 past 매핑에서 신규(new)는 스테이징 코드로 제한하되, 과거 후보(old)는 운영 테이블 전체에서 찾도록 조정해(입력 CSV가 신규 코드만 포함하는 케이스 대응) 매핑 누락(0건)을 줄임.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`의 past 매핑(시행일 기반/주소 정규화 기반)에서 `past_legal_dong_cd`가 NULL이 아닌 공백/특수문자(비숫자) 혼입으로 "사실상 미설정"인 케이스를 숫자만 남겨 판정해 동일하게 처리하고, 주소 정규화 후보 선택 시 `신규 cr_dt = 과거 dlt_dt` 시행일 연결을 우선하며, 주소 정규화 문자열은 유니코드 공백(NBSP/ZWSP)까지 흡수하도록 "한글/숫자만 남김" 방식으로 보강하고 `...구` 제거 정규식의 그리디 매칭으로 주소 앞부분이 통째로 제거되는 문제를 `시/군 + 구` 패턴만 제거하도록 수정해 체인 연결 누락을 방지.
- 법정동 관리자 기능(검색/마이그레이션)의 기준 테이블을 `tb_legal_dong_stage_l`로 분리하고, 기동 시 스키마 초기화 DDL에 `lock_timeout/statement_timeout` 및 실행 시간 로그를 추가해 DDL 락 대기 시 무한 멈춤처럼 보이는 문제를 빠르게 식별 가능하도록 개선.

## 2026-01-15
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`에서 관리자 화면에서 입력된 과거법정동코드(`past_legal_dong_cd`)를 매핑 체인에 반영하고, 과거코드의 `rank`를 신규 코드에 그대로 적용하도록 보강(행정동 혼입 정규화 로직은 유지).

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
- 운영에서 테이블 생성 없이(DLL 불가) DBeaver Import→프로시저 호출로 처리할 수 있도록 스테이징 테이블 기반 프로시저 스크립트 `scripts/legal_dong_migrate_from_stage_proc.sql`을 추가.
- 운영에서 스테이징/임시테이블 생성 로그를 남길 수 없는 환경을 위해, CSV 데이터를 VALUES 인라인 테이블로 넣고 INSERT/UPDATE만 수행하는 DBeaver 실행용 스크립트 `scripts/legal_dong_migrate_from_values_dbeaver.sql`을 추가.
- (테스트용) DBeaver에서 TEMP 테이블 생성→CSV Import→업서트+past 반영을 같은 세션에서 수행할 수 있는 템플릿 스크립트 `scripts/legal_dong_migrate_from_temp_dbeaver_template.sql`을 추가.
- (테스트용) TEMP 테이블(`pg_temp.tmp_legal_dong_excel_csv`)에 Import 후 `CALL` 한 번으로 처리할 수 있는 프로시저 스크립트 `scripts/legal_dong_migrate_from_temp_proc.sql`을 추가.
- (psql 전용) 로컬 CSV를 `\copy`로 TEMP 테이블에 적재한 뒤 `CALL`로 처리하는 드라이버 스크립트 `scripts/legal_dong_migrate_from_psql_local_csv.sql`을 추가.
- (서버 CSV) 운영 DB 서버 경로의 CSV를 server-side `COPY FROM`으로 TEMP에 적재한 뒤 `CALL sp_legal_dong_migrate_from_temp`를 실행하는 프로시저 `scripts/legal_dong_migrate_from_server_csv_proc.sql`을 추가.
- `scripts/2_legal_dong_migrate_from_temp_proc.sql`에서 업서트 전 `sc_fdis_backup.tb_fdis_legal_dong_cd_m_YYYYMMDD_backup` 백업 테이블을 생성하도록 보강(동일 날짜 백업이 이미 있으면 스킵).

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
