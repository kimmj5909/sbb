package com.mysite.sbb.legaldong;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
/**
 * 과거법정동코드(past_legal_dong_cd) 자동 매핑 서비스.
 *
 * 적용 방식
 * - 시행일(효력일)별로 2단계 적용한다.
 *   1) 읍면동(동 포함) 단위: 신규 emndn(cr_dt=eff_dt)에 과거 emndn(dlt_dt=eff_dt) 매핑
 *   2) 하위(li) 단위: 1)에서 유니크 매핑이 된 emndn만 대상으로 tail2 규칙으로 매핑
 *
 * 주의
 * - 후보가 2개 이상인 애매 케이스는 자동 반영하지 않는다.
 * - 신규 데이터의 past_legal_dong_cd가 이미 채워진 경우는 덮어쓰지 않는다.
 */
public class LegalDongPastMappingService {

	private final LegalDongPastMappingJdbcRepository repository;

	@Transactional
	public List<LegalDongPastMappingApplyResult> applyForEffectiveDates(Set<String> effDts, String operatorId) {
		if (effDts == null || effDts.isEmpty()) {
			return List.of();
		}

		Set<String> normalized = new LinkedHashSet<>();
		for (String effDt : effDts) {
			if (effDt == null || effDt.isBlank()) {
				continue;
			}
			normalized.add(effDt.trim());
		}
		if (normalized.isEmpty()) {
			return List.of();
		}

		List<LegalDongPastMappingApplyResult> results = new ArrayList<>();
		for (String effDt : normalized) {
			results.add(repository.applyForEffectiveDate(effDt, operatorId));
		}
		return results;
	}
}

