package com.mysite.sbb.legaldong;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 법정동 엔티티 조회/저장을 위한 Spring Data JPA 리포지토리.
 *
 * 담당 역할
 * - {@link LegalDongTables#LEGAL_DONG_TABLE} 테이블을 JPA 방식으로 조회/저장할 때 사용한다.
 *
 * 참고
 * - 대량 적재는 성능을 위해 JDBC 업서트(`LegalDongJdbcUpsertRepository`)를 사용한다.
 * - 다만 관리자 화면에서 특정 코드 단건 조회 등 기능이 확장될 수 있어 기본 리포지토리를 함께 둔다.
 */
public interface LegalDongRepository extends JpaRepository<LegalDong, String> {
}
