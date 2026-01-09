package com.mysite.sbb.legaldong;

import java.util.List;

import lombok.Value;

@Value
/**
 * 법정동 코드 마이그레이션 "DB 적용" 결과 DTO.
 *
 * 주의
 * - Thymeleaf에서 `${result.totalRows}`처럼 JavaBean 프로퍼티 접근이 가능해야 하므로,
 *   record 대신 getter를 제공하는 클래스로 둔다.
 * - DevTools 재시작(ClassLoader 교체) 과정에서 내부 클래스를 참조하면
 *   `NoClassDefFoundError: ...$InnerClass`가 발생할 수 있어, 결과 타입은 top-level로 분리한다.
 */
public class LegalDongMigrationApplyResult {
	int totalRows;
	int upsertTargetRows;
	int changedRows;
	int insertedRows;
	int updatedRows;
	int dltDtUpdatedRows;
	List<String> updateDetails;
	List<String> errors;

	// 롤백 식별자: DB 적용 결과가 기대와 다를 경우, 동일 run_id로 원복 가능
	String runId;
}
