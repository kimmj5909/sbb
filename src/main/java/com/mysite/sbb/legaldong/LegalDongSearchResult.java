package com.mysite.sbb.legaldong;

import java.util.Collections;
import java.util.List;

import lombok.Value;

@Value
/**
 * 법정동 DB 검색 결과.
 *
 * 담당 역할
 * - 검색 결과 리스트와 total/페이징 정보를 템플릿으로 전달한다.
 * - 화면에서 페이지 이동 링크를 구성할 수 있도록 totalPages 계산 유틸리티를 포함한다.
 */
public class LegalDongSearchResult {
	List<LegalDongSearchRow> entries;
	long total;
	int page;
	int size;

	public int totalPages() {
		if (size <= 0) {
			return 0;
		}
		return (int) Math.ceil((double) total / (double) size);
	}

	public static LegalDongSearchResult empty(int page, int size) {
		return new LegalDongSearchResult(Collections.emptyList(), 0, page, size);
	}
}
