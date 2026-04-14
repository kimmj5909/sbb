package com.mysite.sbb.legaldong.mois;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MOIS 법정동 변경내역 모니터링 배치 설정.
 *
 * 운영자가 자주 바꾸는 값을 코드에서 분리하기 위해 사용한다.
 *
 * - 대상 게시판: bbsId 및 목록 URL
 * - 신규 감지 범위: "등록일 기준 now()-1 ~ now()" 요구사항을 LocalDate 범위로 환산해 적용
 * - 실행 시각: cron(기본 매일 16:00)
 * - 다운로드 저장 경로: baseDir/게시물번호_YYYYMMDD/ 하위에 첨부파일 저장
 */
@ConfigurationProperties(prefix = "sbb.legal-dong.mois")
public class MoisLegalDongProperties {

	/**
	 * MOIS 도메인/공통 경로 변경에 대비해 URL을 설정값으로 둔다.
	 */
	private String boardListUrl = "https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardList.do";

	/**
	 * 대상 게시판 ID(법정동 변경내역 알림 게시판).
	 */
	private String bbsId = "BBSMSTR_000000000052";

	/**
	 * 신규글 감지 범위: 오늘 기준으로 며칠 전까지를 포함할지(기본 1이면 today-1 ~ today).
	 */
	private int scanDaysBack = 1;

	/**
	 * 매일 16:00 실행이 기본이며, 운영 환경에 따라 조정 가능.
	 */
	private String cron = "0 0 16 * * *";

	/**
	 * MOIS 게시판은 한국 기준 날짜로 표시되는 경우가 많으므로, 기본 타임존을 Asia/Seoul로 둔다.
	 * (운영 서버의 시스템 타임존이 다를 수 있어도, 배치 윈도우/폴더명이 일관되도록 한다.)
	 */
	private String zoneId = "Asia/Seoul";

	/**
	 * 다운로드 저장 기본 경로(상대/절대 모두 허용).
	 * 실제 저장은 {baseDir}/{게시물번호}_{YYYYMMDD}/ 하위에 첨부파일을 생성한다.
	 */
	private String downloadBaseDir = "./downloads/mois/legal-dong";

	/**
	 * HTTP 요청 타임아웃(목록/상세/다운로드 공통).
	 */
	private Duration httpTimeout = Duration.ofSeconds(30);

	/**
	 * 기본 User-Agent. 간단한 차단/봇 필터를 피하기 위해 브라우저 UA로 둔다.
	 */
	private String userAgent = "Mozilla/5.0 (compatible; SBB-MOIS-LegalDongBot/1.0)";

	public String getBoardListUrl() {
		return boardListUrl;
	}

	public void setBoardListUrl(String boardListUrl) {
		this.boardListUrl = boardListUrl;
	}

	public String getBbsId() {
		return bbsId;
	}

	public void setBbsId(String bbsId) {
		this.bbsId = bbsId;
	}

	public int getScanDaysBack() {
		return scanDaysBack;
	}

	public void setScanDaysBack(int scanDaysBack) {
		this.scanDaysBack = scanDaysBack;
	}

	public String getCron() {
		return cron;
	}

	public void setCron(String cron) {
		this.cron = cron;
	}

	public String getZoneId() {
		return zoneId;
	}

	public void setZoneId(String zoneId) {
		this.zoneId = zoneId;
	}

	public String getDownloadBaseDir() {
		return downloadBaseDir;
	}

	public void setDownloadBaseDir(String downloadBaseDir) {
		this.downloadBaseDir = downloadBaseDir;
	}

	public Duration getHttpTimeout() {
		return httpTimeout;
	}

	public void setHttpTimeout(Duration httpTimeout) {
		this.httpTimeout = httpTimeout;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}
}

