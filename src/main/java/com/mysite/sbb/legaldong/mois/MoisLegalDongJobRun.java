package com.mysite.sbb.legaldong.mois;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * MOIS 법정동 변경내역 모니터링 배치의 "실행 단위" 로그.
 *
 * 요구사항 매핑
 * - 실행 시각: 매일 16:00에 실행(스케줄러에서 보장)하되, 실제 실행 시각/윈도우(now()-1~now())는 이 테이블에 기록한다.
 * - 상태: 성공/실패/변동없음(신규 게시물 없음 또는 모두 처리 이력 존재)
 * - 집계: 신규 후보/처리/스킵/실패 건수를 저장해, 운영 시 "왜 변동없음이었는지" 확인할 수 있게 한다.
 */
@Entity
@Table(name = "mois_legal_dong_job_run")
public class MoisLegalDongJobRun {

	public enum Status {
		SUCCESS,
		FAILURE,
		NO_CHANGE
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false)
	private Instant startedAt;

	@Column(nullable = true)
	private Instant finishedAt;

	@Column(nullable = false)
	private LocalDate windowFromDate;

	@Column(nullable = false)
	private LocalDate windowToDate;

	@Column(nullable = false)
	private int candidatePosts;

	@Column(nullable = false)
	private int newPosts;

	@Column(nullable = false)
	private int skippedAlreadyProcessed;

	@Column(nullable = false)
	private int processedSuccess;

	@Column(nullable = false)
	private int processedFailure;

	@Column(nullable = true, length = 2000)
	private String message;

	protected MoisLegalDongJobRun() {
	}

	public MoisLegalDongJobRun(
			Status status,
			Instant startedAt,
			LocalDate windowFromDate,
			LocalDate windowToDate) {
		this.status = status;
		this.startedAt = startedAt;
		this.windowFromDate = windowFromDate;
		this.windowToDate = windowToDate;
	}

	public Long getId() {
		return id;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public void setFinishedAt(Instant finishedAt) {
		this.finishedAt = finishedAt;
	}

	public LocalDate getWindowFromDate() {
		return windowFromDate;
	}

	public LocalDate getWindowToDate() {
		return windowToDate;
	}

	public int getCandidatePosts() {
		return candidatePosts;
	}

	public void setCandidatePosts(int candidatePosts) {
		this.candidatePosts = candidatePosts;
	}

	public int getNewPosts() {
		return newPosts;
	}

	public void setNewPosts(int newPosts) {
		this.newPosts = newPosts;
	}

	public int getSkippedAlreadyProcessed() {
		return skippedAlreadyProcessed;
	}

	public void setSkippedAlreadyProcessed(int skippedAlreadyProcessed) {
		this.skippedAlreadyProcessed = skippedAlreadyProcessed;
	}

	public int getProcessedSuccess() {
		return processedSuccess;
	}

	public void setProcessedSuccess(int processedSuccess) {
		this.processedSuccess = processedSuccess;
	}

	public int getProcessedFailure() {
		return processedFailure;
	}

	public void setProcessedFailure(int processedFailure) {
		this.processedFailure = processedFailure;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}
}

