# 변경 이력

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
