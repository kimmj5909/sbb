package com.mysite.sbb.legaldong.mois;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MOIS 법정동 변경내역 게시판 모니터링/다운로드 배치의 핵심 서비스.
 *
 * 처리 흐름(요구사항 대응)
 * 1) 등록일 기준 now()-1 ~ now() 범위로 목록 수집
 * 2) 게시글 번호(nttId) 기반 처리 이력 존재 여부로 중복 작업 방지
 * 3) 신규 게시물의 첨부파일을 다운로드
 *    - 저장 폴더: {downloadBaseDir}/{게시물번호}_{YYYYMMDD}/ 하위에 첨부파일 저장
 * 4) 매 실행마다 성공/실패/변동없음 상태를 실행 로그 테이블에 적재
 */
@Service
public class MoisLegalDongMonitorService {

	private static final Logger log = LoggerFactory.getLogger(MoisLegalDongMonitorService.class);
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final MoisLegalDongProperties properties;
	private final MoisLegalDongBoardClient boardClient;
	private final MoisLegalDongJobRunRepository jobRunRepository;
	private final MoisLegalDongPostHistoryRepository postHistoryRepository;

	public MoisLegalDongMonitorService(
			MoisLegalDongProperties properties,
			MoisLegalDongBoardClient boardClient,
			MoisLegalDongJobRunRepository jobRunRepository,
			MoisLegalDongPostHistoryRepository postHistoryRepository) {
		this.properties = properties;
		this.boardClient = boardClient;
		this.jobRunRepository = jobRunRepository;
		this.postHistoryRepository = postHistoryRepository;
	}

	@Transactional
	public MoisLegalDongJobRun runOnce() {
		ZoneId zoneId = ZoneId.of(properties.getZoneId());
		LocalDate today = LocalDate.now(zoneId);
		LocalDate fromDate = today.minusDays(properties.getScanDaysBack());
		LocalDate toDate = today;

		MoisLegalDongJobRun run = new MoisLegalDongJobRun(
				MoisLegalDongJobRun.Status.NO_CHANGE,
				Instant.now(),
				fromDate,
				toDate);
		run = jobRunRepository.save(run);

		try {
			List<MoisLegalDongBoardClient.PostSummary> candidates = boardClient.fetchPostSummaries(fromDate, toDate);
			run.setCandidatePosts(candidates.size());

			int skippedAlreadyProcessed = 0;
			int processedSuccess = 0;
			int processedFailure = 0;
			int newPosts = 0;

			for (MoisLegalDongBoardClient.PostSummary summary : candidates) {
				boolean alreadyProcessed = postHistoryRepository.existsByBbsIdAndNttId(properties.getBbsId(), summary.getNttId());
				if (alreadyProcessed) {
					skippedAlreadyProcessed++;
					continue;
				}

				newPosts++;

				LocalDate downloadDate = today;
				String folderName = summary.getNttId() + "_" + downloadDate.format(YYYYMMDD);
				Path baseDir = MoisLegalDongPathResolver.resolveBaseDir(properties.getDownloadBaseDir());
				Path targetDir = baseDir.resolve(folderName);

				MoisLegalDongPostHistory postHistory = new MoisLegalDongPostHistory(
						properties.getBbsId(),
						summary.getNttId(),
						summary.getTitle(),
						summary.getRegisteredDate(),
						downloadDate,
						targetDir.toAbsolutePath().toString());

				try {
					MoisLegalDongBoardClient.PostDetail detail = boardClient.fetchPostDetail(
							summary.getArticleUrl(),
							summary.getNttId(),
							summary.getTitle(),
							summary.getRegisteredDate());

					List<MoisLegalDongBoardClient.Attachment> attachments = detail.getAttachments();
					postHistory.setAttachmentCount(attachments.size());

					int downloadedCount = 0;
					for (MoisLegalDongBoardClient.Attachment attachment : attachments) {
						boardClient.downloadAttachment(
								attachment.getDownloadUrl(),
								detail.getArticleUrl(),
								targetDir,
								attachment.getSuggestedName());
						downloadedCount++;
					}

					postHistory.setDownloadedCount(downloadedCount);
					postHistory.setStatus(MoisLegalDongPostHistory.Status.SUCCESS);
					postHistory.setProcessedAt(Instant.now());
					postHistory.setMessage("첨부 " + downloadedCount + "건 다운로드 완료");
					postHistoryRepository.save(postHistory);
					processedSuccess++;
				} catch (IOException ex) {
					postHistory.setStatus(MoisLegalDongPostHistory.Status.FAILURE);
					postHistory.setProcessedAt(Instant.now());
					postHistory.setMessage("첨부 다운로드 실패: " + ex.getMessage());
					postHistoryRepository.save(postHistory);
					processedFailure++;
					log.warn("MOIS legal-dong post download failed. nttId={}, title={}", summary.getNttId(), summary.getTitle(), ex);
				} catch (RuntimeException ex) {
					postHistory.setStatus(MoisLegalDongPostHistory.Status.FAILURE);
					postHistory.setProcessedAt(Instant.now());
					postHistory.setMessage("처리 중 런타임 오류: " + ex.getMessage());
					postHistoryRepository.save(postHistory);
					processedFailure++;
					log.warn("MOIS legal-dong post processing failed. nttId={}, title={}", summary.getNttId(), summary.getTitle(), ex);
				}
			}

			run.setNewPosts(newPosts);
			run.setSkippedAlreadyProcessed(skippedAlreadyProcessed);
			run.setProcessedSuccess(processedSuccess);
			run.setProcessedFailure(processedFailure);
			run.setFinishedAt(Instant.now());

			if (newPosts == 0) {
				run.setStatus(MoisLegalDongJobRun.Status.NO_CHANGE);
				run.setMessage("등록일 " + fromDate + " ~ " + toDate + " 범위 신규 게시물 없음(또는 모두 처리 완료)");
			} else if (processedFailure > 0) {
				run.setStatus(MoisLegalDongJobRun.Status.FAILURE);
				run.setMessage("신규 " + newPosts + "건 중 성공 " + processedSuccess + "건, 실패 " + processedFailure + "건");
			} else {
				run.setStatus(MoisLegalDongJobRun.Status.SUCCESS);
				run.setMessage("신규 " + newPosts + "건 처리 완료");
			}

			return jobRunRepository.save(run);
		} catch (RuntimeException ex) {
			run.setStatus(MoisLegalDongJobRun.Status.FAILURE);
			run.setFinishedAt(Instant.now());
			run.setMessage("배치 실행 실패: " + ex.getMessage());
			jobRunRepository.save(run);
			throw ex;
		}
	}
}
