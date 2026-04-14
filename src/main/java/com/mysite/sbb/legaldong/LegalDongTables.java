package com.mysite.sbb.legaldong;

/**
 * 법정동 기능에서 사용하는 테이블명 상수 모음.
 *
 * 주의
 * - SQL 문자열에 직접 합쳐 넣는 값이므로, "사용자 입력"으로부터 파생된 값이 들어가면 안 된다.
 * - 운영/테스트 DB의 실제 테이블명 변경이 필요할 때만 수정한다.
 */
public final class LegalDongTables {

	/**
	 * 법정동 코드 기준 테이블(관리자 검색/마이그레이션 적용 대상).
	 *
	 * 변경 배경
	 * - 기존 `tb_legal_dong_l`은 다른 배치/스크립트/테스트가 동시에 접근하는 경우 DDL 락 대기가 길어질 수 있다.
	 * - 관리 기능은 "검증/테스트 단계"에서 주로 사용하므로, 별도 스테이징 테이블로 분리해 락 충돌을 줄인다.
	 */
	public static final String LEGAL_DONG_TABLE = "tb_legal_dong_stage_l";

	private LegalDongTables() {
	}
}

