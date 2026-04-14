package com.mysite.sbb.legaldong.mois;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MOIS 법정동 변경내역 모니터링 스케줄러.
 *
 * 요구사항
 * - 게시물 작성 시간은 알 수 없으므로 "매일 16:00"에 실행한다.
 * - 성공/실패/변동없음 상태는 서비스에서 실행 로그로 적재한다.
 */
@Component
public class MoisLegalDongMonitorScheduler {

	private static final Logger log = LoggerFactory.getLogger(MoisLegalDongMonitorScheduler.class);

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final MoisLegalDongMonitorService monitorService;

	public MoisLegalDongMonitorScheduler(MoisLegalDongMonitorService monitorService) {
		this.monitorService = monitorService;
	}

	@Scheduled(cron = "${sbb.legal-dong.mois.cron:0 0 16 * * *}", zone = "${sbb.legal-dong.mois.zone-id:Asia/Seoul}")
	public void run() {
		if (!running.compareAndSet(false, true)) {
			log.warn("MOIS legal-dong scheduler skipped: previous run still in progress.");
			return;
		}

		try {
			MoisLegalDongJobRun run = monitorService.runOnce();
			log.info("MOIS legal-dong run finished. status={}, newPosts={}, success={}, failure={}",
					run.getStatus(),
					run.getNewPosts(),
					run.getProcessedSuccess(),
					run.getProcessedFailure());
		} finally {
			running.set(false);
		}
	}
}

