package com.mysite.sbb.legaldong.mois;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.boot.web.client.RestTemplateBuilder;

/**
 * MOIS 법정동 변경내역 게시판 "최신 게시물"의 첨부파일을 수동으로 다운로드하는 점검용 Main.
 *
 * 사용 목적
 * - 운영/개발 환경에서 스케줄러(16:00) 대기 없이, 네트워크/파싱/다운로드가 정상인지 즉시 확인한다.
 *
 * 동작
 * 1) 목록 1페이지에서 최신 게시물 1건을 선택한다(등록일 + nttId 기준).
 * 2) 상세 페이지에서 첨부 다운로드 링크를 추출한다.
 * 3) `{downloadBaseDir}/{게시물번호}_{YYYYMMDD}/` 폴더를 생성하고 첨부파일을 저장한다.
 *
 * 오버라이드 가능한 시스템 프로퍼티(없으면 기본값 사용)
 * - sbb.legal-dong.mois.board-list-url
 * - sbb.legal-dong.mois.bbs-id
 * - sbb.legal-dong.mois.zone-id
 * - sbb.legal-dong.mois.download-base-dir
 */
public class MoisLegalDongManualDownloadMain {

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	public static void main(String[] args) throws IOException {
		MoisLegalDongProperties properties = new MoisLegalDongProperties();
		applySystemOverrides(properties);

		MoisLegalDongBoardClient client = new MoisLegalDongBoardClient(properties, new RestTemplateBuilder());
		MoisLegalDongBoardClient.PostSummary latest = client.fetchLatestPostSummary();

		System.out.println("[MOIS] latest post selected");
		System.out.println(" - bbsId: " + properties.getBbsId());
		System.out.println(" - nttId: " + latest.getNttId());
		System.out.println(" - registeredDate: " + latest.getRegisteredDate());
		System.out.println(" - title: " + latest.getTitle());
		System.out.println(" - articleUrl: " + latest.getArticleUrl());

		MoisLegalDongBoardClient.PostDetail detail = client.fetchPostDetail(
				latest.getArticleUrl(),
				latest.getNttId(),
				latest.getTitle(),
				latest.getRegisteredDate());

		List<MoisLegalDongBoardClient.Attachment> attachments = detail.getAttachments();
		if (attachments.isEmpty()) {
			System.out.println("[MOIS] no attachments found.");
			return;
		}

		ZoneId zoneId = ZoneId.of(properties.getZoneId());
		LocalDate downloadDate = LocalDate.now(zoneId);
		String folderName = latest.getNttId() + "_" + downloadDate.format(YYYYMMDD);
		Path baseDir = MoisLegalDongPathResolver.resolveBaseDir(properties.getDownloadBaseDir());
		Path targetDir = baseDir.resolve(folderName);

		System.out.println("[MOIS] downloading attachments");
		System.out.println(" - targetDir: " + targetDir.toAbsolutePath());
		System.out.println(" - count: " + attachments.size());

		int downloaded = 0;
		for (MoisLegalDongBoardClient.Attachment attachment : attachments) {
			Path saved = client.downloadAttachment(
					attachment.getDownloadUrl(),
					detail.getArticleUrl(),
					targetDir,
					attachment.getSuggestedName());
			downloaded++;
			System.out.println(" - saved[" + downloaded + "]: " + saved.toAbsolutePath());
		}

		System.out.println("[MOIS] done. downloaded=" + downloaded);
	}

	private static void applySystemOverrides(MoisLegalDongProperties properties) {
		String boardListUrl = System.getProperty("sbb.legal-dong.mois.board-list-url");
		if (boardListUrl != null && !boardListUrl.isBlank()) {
			properties.setBoardListUrl(boardListUrl.trim());
		}

		String bbsId = System.getProperty("sbb.legal-dong.mois.bbs-id");
		if (bbsId != null && !bbsId.isBlank()) {
			properties.setBbsId(bbsId.trim());
		}

		String zoneId = System.getProperty("sbb.legal-dong.mois.zone-id");
		if (zoneId != null && !zoneId.isBlank()) {
			properties.setZoneId(zoneId.trim());
		}

		String downloadBaseDir = System.getProperty("sbb.legal-dong.mois.download-base-dir");
		if (downloadBaseDir != null && !downloadBaseDir.isBlank()) {
			properties.setDownloadBaseDir(downloadBaseDir.trim());
		}
	}
}
