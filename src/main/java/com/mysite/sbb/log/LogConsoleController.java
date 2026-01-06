package com.mysite.sbb.log;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 운영 로그를 화면에서 바로 검색·요약할 수 있는 콘솔.
 */
@Controller
@RequestMapping("/logs")
@PreAuthorize("hasRole('ADMIN')")
public class LogConsoleController {

	private final LogSearchService logSearchService;
	private final LogSearchProperties logSearchProperties;

	public LogConsoleController(LogSearchService logSearchService, LogSearchProperties logSearchProperties) {
		this.logSearchService = logSearchService;
		this.logSearchProperties = logSearchProperties;
	}

	@GetMapping("/console")
	public String viewConsole(
		@RequestParam(value = "q", required = false) String query,
		@RequestParam(value = "level", required = false) String level,
		@RequestParam(value = "service", required = false) String service,
		@RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(value = "size", required = false, defaultValue = "100") Integer size,
		Model model) {

		LocalDate todayUtc = LocalDate.now(java.time.ZoneOffset.UTC);
		LogSearchRequest request = new LogSearchRequest();
		request.setQuery(query);
		request.setLevel(level);
		request.setService(service);
		request.setFromDate(from != null ? from : todayUtc.minusDays(1));
		request.setToDate(to != null ? to : todayUtc);
		request.setSize(size != null ? size : 100);

		LogSearchResult result;
		try {
			result = logSearchService.search(request);
		} catch (RuntimeException e) {
			model.addAttribute("errorMessage", "Elasticsearch 검색 중 오류가 발생했습니다: " + e.getMessage());
			result = LogSearchResult.empty(logSearchProperties.getIndexPrefix() + "*");
		}

		model.addAttribute("result", result);
		model.addAttribute("filters", request);
		model.addAttribute("levelOptions", List.of("DEBUG", "INFO", "WARN", "ERROR"));
		model.addAttribute("indexPattern", logSearchProperties.getIndexPrefix() + "*");
		return "logs/console";
	}
}
