package com.mysite.sbb.legaldong.mois;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * MOIS 법정동 모니터링 배치 실행 로그 Repository.
 *
 * - 운영 조회(최근 실행 상태 확인/장애 분석)에 사용한다.
 */
public interface MoisLegalDongJobRunRepository extends JpaRepository<MoisLegalDongJobRun, Long> {
}

