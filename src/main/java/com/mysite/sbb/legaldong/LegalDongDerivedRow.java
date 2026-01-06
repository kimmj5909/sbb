package com.mysite.sbb.legaldong;

import java.time.LocalDate;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
/**
 * 원본 엑셀 1행을 기준으로 "적재 가능한 형태"로 변환한 DTO.
 *
 * 담당 역할
 * - 10자리 법정동코드에서 단위별 코드(ctprv/sgng/emndn/li)를 파생한 결과를 보관한다.
 * - 꼬리값 000/00 규칙에 따라 "하위 단위 없음"은 null로 정리된 상태를 보장한다.
 * - rank 계산(읍면동 그룹 카운트 + 리 하위 카운트)까지 완료된 값을 포함한다.
 *
 * 사용 위치
 * - `LegalDongMigrationService`에서 파생 결과로 생성한다.
 * - DB 업서트(`LegalDongJdbcUpsertRepository`)의 입력으로 사용한다.
 */
public class LegalDongDerivedRow {
	int rowNumber;
	String legalDongCd;
	String legalDongNm;
	String ctprvCd;
	String ctprvNm;
	String sgngCd;
	String sgngNm;
	String emndnCd;
	String emndnNm;
	String liCd;
	String liNm;
	Integer rank;
	LocalDate crDt;
	LocalDate dltDt;
}
