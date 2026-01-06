package com.mysite.sbb.log;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 검색 결과와 집계 정보 묶음.
 */
public class LogSearchResult {
	private final List<LogEntry> entries;
	private final long totalHits;
	private final Map<String, Long> levelCounts;
	private final Map<String, Long> serviceCounts;
	private final String indexPattern;

	public LogSearchResult(List<LogEntry> entries, long totalHits, Map<String, Long> levelCounts,
		Map<String, Long> serviceCounts, String indexPattern) {
		this.entries = entries;
		this.totalHits = totalHits;
		this.levelCounts = levelCounts;
		this.serviceCounts = serviceCounts;
		this.indexPattern = indexPattern;
	}

	public List<LogEntry> getEntries() {
		return Collections.unmodifiableList(entries);
	}

	public long getTotalHits() {
		return totalHits;
	}

	public Map<String, Long> getLevelCounts() {
		return Collections.unmodifiableMap(levelCounts);
	}

	public Map<String, Long> getServiceCounts() {
		return Collections.unmodifiableMap(serviceCounts);
	}

	public String getIndexPattern() {
		return indexPattern;
	}

	public static LogSearchResult empty(String indexPattern) {
		return new LogSearchResult(Collections.emptyList(), 0, Collections.emptyMap(), Collections.emptyMap(),
			indexPattern);
	}
}
