package com.mysite.sbb.log;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;

/**
 * HTTP 요청/응답 정보를 가로채 Elasticsearch에 전달하는 인터셉터.
 * - preHandle: 요청 시작 시각/traceId를 심는다.
 * - afterCompletion: 상태코드 기반 레벨 산정, 사용자/클라이언트/지연 시간 메타데이터를 수집해 ES로 전달한다.
 */
public class RequestLoggingInterceptor implements HandlerInterceptor {

	private static final String ATTR_START = RequestLoggingInterceptor.class.getName() + ".startTime";
	private static final String ATTR_TRACE = RequestLoggingInterceptor.class.getName() + ".traceId";

	private final LogIngestService ingestService;
	private final UserRepository userRepository;
	private final String serviceName;

	public RequestLoggingInterceptor(LogIngestService ingestService, UserRepository userRepository, String serviceName) {
		this.ingestService = ingestService;
		this.userRepository = userRepository;
		this.serviceName = serviceName;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		request.setAttribute(ATTR_START, Instant.now());
		String traceId = request.getHeader("X-B3-TraceId");
		if (!StringUtils.hasText(traceId)) {
			traceId = UUID.randomUUID().toString();
		}
		request.setAttribute(ATTR_TRACE, traceId);
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		Instant start = (Instant) request.getAttribute(ATTR_START);
		if (start == null) {
			return;
		}
		long durationMs = Duration.between(start, Instant.now()).toMillis();
		String traceId = (String) request.getAttribute(ATTR_TRACE);
		int status = response.getStatus();
		String level = decideLevel(status, ex);

		HttpLogEvent event = HttpLogEvent.builder()
			.timestamp(Instant.now())
			.level(level)
			.service(serviceName)
			.logType("http")
			.message(buildMessage(request, status))
			.userId(resolveUserId(request))
			.userIp(resolveClientIp(request))
			.requestUrl(request.getRequestURI())
			.httpMethod(request.getMethod())
			.status(status)
			.durationMs(durationMs)
			.userAgent(request.getHeader("User-Agent"))
			.traceId(traceId)
			.build();
		ingestService.ingest(event);
	}

	private String decideLevel(int status, Exception ex) {
		if (ex != null) {
			return "ERROR";
		}
		if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
			return "ERROR";
		}
		if (status >= HttpStatus.BAD_REQUEST.value()) {
			return "WARN";
		}
		return "INFO";
	}

	private String buildMessage(HttpServletRequest request, int status) {
		return request.getMethod() + " " + request.getRequestURI() + " -> " + status;
	}

	private String resolveUserId(HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		String name = authentication.getName();
		if (!StringUtils.hasText(name) || "anonymousUser".equalsIgnoreCase(name)) {
			return null;
		}
		return userRepository.findByUsername(name)
			.map(SiteUser::getUsername)
			.orElse(name);
	}

	private String resolveClientIp(HttpServletRequest request) {
		String xff = request.getHeader("X-Forwarded-For");
		if (StringUtils.hasText(xff)) {
			return xff.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}
}
