package com.mysite.sbb.legaldong;

import lombok.Value;

@Value
/**
 * 과거법정동코드(past_legal_dong_cd) 자동 매핑 "적용" 결과 DTO.
 *
 * 설계 의도
 * - 시행일(eff_dt) 단위로 신규 코드(cr_dt=eff_dt)에 과거 코드(old.dlt_dt=eff_dt)를 매핑한다.
 * - 애매 케이스(후보 > 1)나 누락(후보 0)은 자동 반영하지 않고, 미리보기/수동 보정 대상으로 남긴다.
 */
public class LegalDongPastMappingApplyResult {
	String effDt;

	int oldEmndnCnt;
	int newEmndnCnt;
	int emndnUpdatedCnt;
	int emndnAmbiguousCnt;
	int emndnMissingCnt;

	int oldLiCnt;
	int newLiCnt;
	int liUpdatedCnt;
	int liMissingOldCnt;
}
