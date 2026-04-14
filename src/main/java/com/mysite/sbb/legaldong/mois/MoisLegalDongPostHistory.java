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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * MOIS 게시판 게시물 단위의 처리 이력.
 *
 * 핵심 목적
 * - "게시글 번호(nttId)"를 키로 작업 이력을 남겨, 다음 실행에서 중복 다운로드/중복 처리를 방지한다.
 *
 * 저장 규칙(요구사항)
 * - 다운로드 폴더명: 게시물번호_다운로드일자(YYYYMMDD)
 * - 등록일(등록일자 기준): 목록에서 수집한 LocalDate를 저장(작성 시간은 제공되지 않는다고 가정)
 */
@Entity
@Table(
		name = "mois_legal_dong_post_history",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_mois_legal_dong_post", columnNames = {"bbs_id", "ntt_id"})
		},
		indexes = {
				@Index(name = "idx_mois_legal_dong_reg_date", columnList = "registered_date")
		})
public class MoisLegalDongPostHistory {

	public enum Status {
		SUCCESS,
		FAILURE
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "bbs_id", nullable = false, length = 40)
	private String bbsId;

	@Column(name = "ntt_id", nullable = false)
	private long nttId;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(name = "registered_date", nullable = false)
	private LocalDate registeredDate;

	@Column(name = "download_date", nullable = false)
	private LocalDate downloadDate;

	@Column(name = "download_folder", nullable = false, length = 1000)
	private String downloadFolder;

	@Column(nullable = false)
	private int attachmentCount;

	@Column(nullable = false)
	private int downloadedCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false)
	private Instant processedAt;

	@Column(nullable = true, length = 2000)
	private String message;

	protected MoisLegalDongPostHistory() {
	}

	public MoisLegalDongPostHistory(
			String bbsId,
			long nttId,
			String title,
			LocalDate registeredDate,
			LocalDate downloadDate,
			String downloadFolder) {
		this.bbsId = bbsId;
		this.nttId = nttId;
		this.title = title;
		this.registeredDate = registeredDate;
		this.downloadDate = downloadDate;
		this.downloadFolder = downloadFolder;
	}

	public Long getId() {
		return id;
	}

	public String getBbsId() {
		return bbsId;
	}

	public long getNttId() {
		return nttId;
	}

	public String getTitle() {
		return title;
	}

	public LocalDate getRegisteredDate() {
		return registeredDate;
	}

	public LocalDate getDownloadDate() {
		return downloadDate;
	}

	public String getDownloadFolder() {
		return downloadFolder;
	}

	public int getAttachmentCount() {
		return attachmentCount;
	}

	public void setAttachmentCount(int attachmentCount) {
		this.attachmentCount = attachmentCount;
	}

	public int getDownloadedCount() {
		return downloadedCount;
	}

	public void setDownloadedCount(int downloadedCount) {
		this.downloadedCount = downloadedCount;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}

	public void setProcessedAt(Instant processedAt) {
		this.processedAt = processedAt;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}
}

