package com.mysite.sbb.legaldong;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
/**
 * 테스트/운영 환경에서 `tb_legal_dong_l` 테이블이 없을 경우 자동으로 생성한다.
 *
 * 담당 역할
 * - 테스트 단계에서 insert 대상 테이블이 없을 때, 운영 배포 전에 기능 검증이 가능하도록 선행 DDL을 제공한다.
 * - JPA ddl-auto=update가 켜져 있어도 DB/권한 구성에 따라 테이블 생성이 누락될 수 있어 방어적으로 보강한다.
 *
 * 요구사항
 * - 테이블명: tb_legal_dong_l
 * - legal_dong_cd 유니크(실사용상 PK가 자연스럽고, 업서트 충돌 타겟으로 사용한다)
 * - frst~/last~ 컬럼은 timestamptz
 * - 나머지 컬럼 기본값은 null
 *
 * 날짜 컬럼 타입
 * - 기존 요구사항/데이터 특성상 생성/말소일자는 "yyyyMMdd(8자리)" 문자열로 관리한다.
 * - 따라서 cr_dt/dlt_dt 컬럼은 `varchar(8)`로 생성한다.
 * - 검색(범위 조건)에서는 `to_date(cr_dt, 'YYYYMMDD')` 형태로 변환해 비교한다.
 *
 * 이력 관리
 * - `legal_dong_cd` 자체는 고유 코드이므로, 코드 마스터(tb_legal_dong_l)는 1행=1코드로 유지한다.
 * - 다만 실무 데이터(예: 행정구역 개편)에서는 같은 법정동코드가 서로 다른 생성/말소 기간 조합으로 반복 등장할 수 있다.
 * - 이를 보존하기 위해 (legal_dong_cd, cr_dt, dlt_dt) 기준의 이력 테이블(tb_legal_dong_l_hist)을 별도로 둔다.
 * - 이력 테이블은 "누적"이 원칙이며, 동일 키(legal_dong_cd, cr_dt, dlt_dt)는 중복 삽입되지 않게 유니크로 막는다.
 */
public class LegalDongSchemaInitializer {

	private static final Logger log = LoggerFactory.getLogger(LegalDongSchemaInitializer.class);

	private final JdbcTemplate jdbcTemplate;

	@PostConstruct
	public void initializeSchema() {
		// 애플리케이션 기동 시점에 테이블이 없으면 먼저 생성해준다.
		// - 운영/테스트에서 ddl-auto=update가 있어도 권한/스키마에 따라 자동 생성이 누락될 수 있어 방어적으로 보강한다.
		// - CREATE TABLE IF NOT EXISTS는 멱등(idempotent)하므로, 이미 존재해도 안전하다.
		String ddl = """
				CREATE TABLE IF NOT EXISTS tb_legal_dong_l (
					legal_dong_cd        varchar(10)  NOT NULL,
					legal_dong_nm        varchar(200) NOT NULL,
					ctprv_cd             varchar(2)   NOT NULL,
					ctprv_nm             varchar(50)  NOT NULL,
					sgng_cd              varchar(5)   NULL,
					sgng_nm              varchar(50)  NULL,
					emndn_cd             varchar(8)   NULL,
					emndn_nm             varchar(50)  NULL,
					li_cd                varchar(10)  NULL,
					li_nm                varchar(50)  NULL,
					rank                 integer      NULL,
					cr_dt                varchar(8)   NULL,
					dlt_dt               varchar(8)   NULL,
					past_legal_dong_cd   varchar(10)  NULL,
					frst_wrtng_dtm       timestamptz  NULL,
					frst_writr_id        varchar(100) NULL,
					last_updt_dtm        timestamptz  NULL,
					last_upusr_id        varchar(100) NULL,
					use_yn               varchar(1)   NULL,
					CONSTRAINT tb_legal_dong_l_pk PRIMARY KEY (legal_dong_cd)
				);
				""";

		jdbcTemplate.execute(ddl);

		String historyDdl = """
				CREATE TABLE IF NOT EXISTS tb_legal_dong_l_hist (
					legal_dong_cd  varchar(10) NOT NULL,
					cr_dt          varchar(8)  NOT NULL,
					dlt_dt         varchar(8)  NULL,
					frst_wrtng_dtm timestamptz NULL,
					frst_writr_id  varchar(100) NULL,
					CONSTRAINT tb_legal_dong_l_hist_uk UNIQUE (legal_dong_cd, cr_dt, dlt_dt)
				);
				""";
		jdbcTemplate.execute(historyDdl);

		// 과거에 생성된 테이블(또는 수동 DDL)에는 일부 컬럼이 빠져 있을 수 있어, 필수 컬럼은 방어적으로 보강한다.
		// - 관리자 검색/마이그레이션에서 공통으로 참조하는 컬럼을 대상으로 한다.
		ensureColumn("rank", "integer");
		ensureColumn("cr_dt", "varchar(8)");
		ensureColumn("dlt_dt", "varchar(8)");
		ensureColumn("past_legal_dong_cd", "varchar(10)");
		ensureColumn("use_yn", "varchar(1)");
	}

	private void ensureColumn(String columnName, String columnType) {
		try {
			jdbcTemplate.execute("ALTER TABLE tb_legal_dong_l ADD COLUMN IF NOT EXISTS " + columnName + " " + columnType);
		} catch (Exception ex) {
			// 환경(권한/스키마)별로 ALTER 권한이 없을 수 있어, 시작 자체를 막지 않고 경고만 남긴다.
			log.warn("tb_legal_dong_l 컬럼 보강 실패 - column={}, type={}, message={}", columnName, columnType, ex.getMessage());
		}
	}
}
