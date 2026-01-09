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

		// 과거에 생성된 테이블(또는 수동 DDL)에는 일부 컬럼이 빠져 있을 수 있어, 필수 컬럼은 방어적으로 보강한다.
		// - 관리자 검색/마이그레이션에서 공통으로 참조하는 컬럼을 대상으로 한다.
		ensureColumn("rank", "integer");
		ensureColumn("cr_dt", "varchar(8)");
		ensureColumn("dlt_dt", "varchar(8)");
		ensureColumn("past_legal_dong_cd", "varchar(10)");
		ensureColumn("use_yn", "varchar(1)");

		// ==========================
		// 마이그레이션 롤백(1회 실행 단위)용 스냅샷 테이블
		// ==========================
		// 요구사항
		// - 관리자가 엑셀 업로드 → DB 적용 후, 결과가 기대와 다르면 직전 적용을 되돌릴 수 있어야 한다.
		// - 별도 이력테이블을 상시 운영하지 않는 전제에서, "마이그레이션 실행 단위"로만 스냅샷을 남긴다.
		//
		// 설계
		// - run: 실행 메타(누가/언제/무슨 파일)
		// - run_row: 실행 대상 legal_dong_cd별 "적용 전" 스냅샷
		//   - existed=false: 적용 전에는 없었던 코드(롤백 시 DELETE)
		//   - existed=true: 적용 전 스냅샷으로 복원(UPDATE)
		String runDdl = """
				CREATE TABLE IF NOT EXISTS tb_legal_dong_migration_run (
					run_id              uuid         NOT NULL,
					file_name           varchar(255) NULL,
					file_sha256         varchar(64)  NULL,
					operator_id         varchar(100) NULL,
					created_at          timestamptz  NOT NULL DEFAULT now(),
					rolled_back_at      timestamptz  NULL,
					CONSTRAINT tb_legal_dong_migration_run_pk PRIMARY KEY (run_id)
				);
				""";

		String runRowDdl = """
				CREATE TABLE IF NOT EXISTS tb_legal_dong_migration_run_row (
					run_id              uuid         NOT NULL,
					legal_dong_cd       varchar(10)  NOT NULL,
					existed             boolean      NOT NULL,

					legal_dong_nm       varchar(200) NULL,
					ctprv_cd            varchar(2)   NULL,
					ctprv_nm            varchar(50)  NULL,
					sgng_cd             varchar(5)   NULL,
					sgng_nm             varchar(50)  NULL,
					emndn_cd            varchar(8)   NULL,
					emndn_nm            varchar(50)  NULL,
					li_cd               varchar(10)  NULL,
					li_nm               varchar(50)  NULL,
					rank                integer      NULL,
					cr_dt               varchar(8)   NULL,
					dlt_dt              varchar(8)   NULL,
					past_legal_dong_cd  varchar(10)  NULL,
					use_yn              varchar(1)   NULL,

					CONSTRAINT tb_legal_dong_migration_run_row_pk PRIMARY KEY (run_id, legal_dong_cd),
					CONSTRAINT tb_legal_dong_migration_run_row_fk FOREIGN KEY (run_id)
						REFERENCES tb_legal_dong_migration_run(run_id)
				);
				""";

		jdbcTemplate.execute(runDdl);
		jdbcTemplate.execute(runRowDdl);
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
