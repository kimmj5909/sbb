package com.mysite.sbb.legaldong;

import java.util.List;

import lombok.Value;

@Value
/**
 * 법정동 코드 마이그레이션 "미리보기" 결과 DTO.
 *
 * 주의
 * - Thymeleaf에서 `${preview.totalPages}` 형태로 접근하므로, getter가 필요하다.
 * - DevTools 재시작 환경에서 내부 클래스 참조가 깨지는 사례를 방지하기 위해 top-level 클래스로 분리한다.
 */
public class LegalDongMigrationPreviewResult {
	int totalRows;
	int derivedRows;
	int upperRows;
	int lowerRows;
	int page;
	int size;
	int totalPages;
	List<String> errors;
	List<LegalDongDerivedRow> entries;
}

