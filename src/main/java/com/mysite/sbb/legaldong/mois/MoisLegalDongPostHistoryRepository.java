package com.mysite.sbb.legaldong.mois;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * MOIS 게시물 처리 이력 Repository.
 *
 * - 게시글 번호(bbsId + nttId)를 기준으로 중복 작업을 차단한다.
 */
public interface MoisLegalDongPostHistoryRepository extends JpaRepository<MoisLegalDongPostHistory, Long> {

	boolean existsByBbsIdAndNttId(String bbsId, long nttId);
}

