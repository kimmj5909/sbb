package com.mysite.sbb.legaldong;

import java.time.LocalDate;

import lombok.Data;

@Data
/**
 * 법정동 DB 검색 요청 DTO.
 *
 * 담당 역할
 * - 화면 입력 파라미터(코드/명칭/날짜조건/전체검색어)를 검색 서비스로 전달한다.
 *
 * 요구사항 반영
 * - 문자열 필드: 부분검색(contains)
 * - 생성/삭제일자: 날짜 range 검색(yyyyMMdd)
 * - 페이징: 기본 40개/페이지(선택: 40/100/200)
 */
public class LegalDongSearchRequest {
	/**
	 * 검색 조건 선택박스 값.
	 * - all: 전체(코드/명칭/주소/과거코드 대상 포함 검색)
	 * - legalDongCd/legalDongNm/ctprvNm/sgngNm/emndnNm/liNm/pastLegalDongCd: 개별 필드 포함 검색
	 */
	private String searchField;

	/**
	 * 선택된 검색 조건에 적용할 키워드(부분검색).
	 */
	private String keyword;

	/**
	 * 날짜 조건 선택박스 값.
	 * - none: 미사용
	 * - crDt: 생성일자
	 * - dltDt: 삭제일자
	 */
	private String dateField;

	/**
	 * 구버전 링크 호환용: 전체 검색어(현재는 all 검색과 동일하게 처리).
	 */
	private String query;

	/**
	 * 사용여부 필터(선택박스).
	 * - all: 전체
	 * - used: 사용(use_yn='Y')
	 * - unused: 미사용(use_yn IS NULL)
	 */
	private String useYnFilter;

	private String legalDongCd;
	private String legalDongNm;
	private String ctprvNm;
	private String sgngNm;
	private String emndnNm;
	private String liNm;
	private LocalDate crDtFrom;
	private LocalDate crDtTo;
	private LocalDate dltDtFrom;
	private LocalDate dltDtTo;
	private String pastLegalDongCd;
	private int page = 0;
	private int size = 40;
}
