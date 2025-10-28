# Repository Guidelines

## 프로젝트 구조 및 모듈 구성
- 핵심 비즈니스 코드는 `src/main/java/com/mysite/sbb` 하위에 기능별 패키지(`question`, `answer`, `comment`, `user`)로 분리되어 있으며, 각 패키지는 컨트롤러·서비스·리포지토리·폼 DTO를 짝지어 둡니다.
- 서버 사이드 뷰는 `src/main/resources/templates`의 Thymeleaf 템플릿으로 관리하고, 정적 리소스는 `src/main/resources/static`에 두어 캐시 설정과 구분합니다.
- 애플리케이션 설정은 `src/main/resources/application.properties`에 기본값을 두고, 환경 차이는 `SPRING_PROFILES_ACTIVE`를 이용한 별도 프로파일 파일로 분리하는 것을 권장합니다.
- 테스트 코드는 `src/test/java`에서 메인 패키지와 동일한 구조를 유지하여 기능 단위로 대응하도록 합니다.

## 빌드·테스트·개발 명령
- `./gradlew bootRun` : PostgreSQL 연결을 전제로 Spring Boot 애플리케이션을 실행하며 DevTools 덕분에 템플릿 변경이 즉시 반영됩니다.
- `./gradlew clean build` : 전체 컴파일과 JUnit 테스트를 수행하고 실행 가능한 JAR을 `build/libs/`에 생성합니다.
- `./gradlew test` : JUnit 5 기반 테스트만 빠르게 실행합니다. 불안정한 케이스는 `--info` 옵션으로 로그를 확장하세요.
- 모든 명령은 저장소 루트에서 실행해야 `settings.gradle`과 Gradle Wrapper를 정확히 찾습니다.

## 코딩 스타일 및 네이밍 규칙
- Java 파일은 탭 들여쓰기와 120자 소프트 한계를 유지하며, 기능 단위 패키징을 우선합니다.
- 클래스 이름은 Spring 관례를 따르며 서비스·리포지토리·컨트롤러 역할을 접미사로 명확히 표현하고, 요청 폼은 `*Form`으로 통일합니다.
- 의존성 주입은 Lombok `@RequiredArgsConstructor`를 통한 생성자 주입을 기본으로 하고, 쓰기 작업은 서비스 계층에서 `@Transactional`로 감쌉니다.
- Thymeleaf 공통 레이아웃은 `templates/layout/` 같은 하위 폴더에 모아 재사용도를 높입니다.

## 테스트 가이드라인
- JUnit 5와 Spring Test를 사용하되, 통합 검증은 `@SpringBootTest`, 빠른 단위 검증은 `@WebMvcTest`, `@DataJpaTest` 등 슬라이스 테스트로 분리합니다.
- 테스트 클래스는 `*Tests`, 메서드는 `shouldWork_whenCondition()` 형태의 영어 서술형 네이밍을 권장합니다.
- 테스트 데이터는 Builder나 헬퍼를 이용해 최소 단위로 구성하고, 대량 데이터를 주입할 때는 `@Transactional`과 롤백으로 상태를 초기화합니다.
- 주요 컨트롤러·서비스·보안 흐름에 대해 커버리지를 확보한 뒤 PR을 열어야 합니다.

## 커밋 및 PR 가이드라인
- 커밋 메시지는 현재형 명령조로 작성하고 기능 접두사(`answer: validate author on edit`)를 사용해 범위를 명확히 합니다. 제목은 72자 이하로 유지하세요.
- PR 본문에는 변경 요약, 관련 이슈 링크, 수행한 검증(`./gradlew test`)을 리스트로 정리합니다.
- UI·템플릿·보안 규칙이 달라진 경우 스크린샷이나 curl 결과를 첨부해 리뷰어의 확인 시간을 줄입니다.
- 새로운 설정 키를 추가했다면 `application.properties` 주석 또는 별도 문서에 의미와 적용 범위를 함께 기록합니다.

## 코드 수정 전 확인 사항
- 답변 기본 언어는 한국어로 표시.
- 코드 수정 대상 파일 목록화.
- 코드 수정 전 원본 파일 백업명 표시하고 백업 완료 표시해줘(+백업명).
- 코드 수정 완료한 건에 대한 CHANGELOG.md 에 날짜 별 이력 현행화.
- 코드 수정 후 상세한 주석 작성.
- 클래스 레벨 `@RequestMapping`과 메서드 매핑이 중복되어 경로가 이중 생성되지 않는지 확인.
