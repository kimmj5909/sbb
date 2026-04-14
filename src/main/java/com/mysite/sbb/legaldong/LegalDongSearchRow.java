package com.mysite.sbb.legaldong;

import lombok.Value;

@Value
/**
 * 법정동 DB 조회 결과 1건(화면 표출용).
 *
 * 담당 역할
 * - 관리자 화면(`/admin/legal-dong/search`)에서 {@link LegalDongTables#LEGAL_DONG_TABLE} 데이터를 실시간 조회해 표시한다.
 * - Elasticsearch 문서 스키마가 아닌, "DB 조회 결과"를 그대로 담는 DTO다.
 *
 * 설계 포인트
 * - DB에는 yyyyMMdd(8자리) 문자열로 저장하고, 화면 표시는 yyyy-MM-dd로 포맷팅한 값을 전달한다.
 * - 날짜 범위 검색은 컨트롤러에서 LocalDate로 파싱한 뒤, 서비스에서 `to_date(cr_dt, 'YYYYMMDD')`로 비교한다.
 */
public class LegalDongSearchRow {
	String legalDongCd;
	String legalDongNm;
	String ctprvNm;
	String sgngNm;
	String emndnNm;
	String liNm;
	Integer rank;
	String crDt;
	String dltDt;
	String pastLegalDongCd;
	String useYn;
}
