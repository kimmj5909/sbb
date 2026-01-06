package com.mysite.sbb.legaldong;

import java.time.LocalDate;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
/**
 * 엑셀에서 읽은 "원본 값" 1행 DTO.
 *
 * 담당 역할
 * - 파일에 존재하는 입력 컬럼을 최대한 있는 그대로 보관한다(가공/파생은 하지 않는다).
 * - 코드 파생/랭크 계산/명칭 조합은 `LegalDongMigrationService`에서 수행한다.
 *
 * 주의사항
 * - 행정동코드는 입력에 포함되지만 현재 적재 스키마에 사용하지 않으므로 단순 보관만 한다.
 * - 법정동코드는 반드시 문자열 10자리로 정규화된 값만 저장한다(앞 0 유실 방지).
 *
 * - 원본 코드/명칭/일자만 보관하고, 단위별 코드 파생/랭크 계산은 서비스에서 수행한다.
 * - 행정동코드는 입력에 포함되지만 현재 적재 스키마에 사용하지 않으므로 별도 컬럼으로 저장하지 않는다.
 */
public class LegalDongSourceRow {
	int rowNumber;
	String adminDongCd;
	String ctprvNm;
	String sgngNm;
	String emndnNm;
	String legalDongCd;
	String liNm;
	LocalDate crDt;
	LocalDate dltDt;
}
