package com.mysite.sbb.legaldong;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
/**
 * 법정동 마이그레이션 "실행 단위(run)" 스냅샷/롤백 레포지토리.
 *
 * 목적
 * - 관리자가 엑셀 업로드 → DB 적용 후 결과가 기대와 다르면, 직전 적용을 되돌릴 수 있어야 한다.
 * - 상시 이력테이블을 운영하지 않는 전제에서, "마이그레이션 실행 단위"로만 스냅샷을 남긴다.
 *
 * 스냅샷 범위
 * - 마이그레이션 실행(run)에서 업서트 대상으로 들어온 legal_dong_cd 목록(derived rows)을 대상으로 한다.
 * - 이 범위 내에서 업서트/과거코드 매핑이 수정하는 컬럼(cr/dlt/use/past 포함)을 복원할 수 있다.
 */
public class LegalDongMigrationRunRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	@Transactional
	public UUID createRun(String fileName, String fileSha256, String operatorId) {
		UUID runId = UUID.randomUUID();
		String sql = """
				INSERT INTO tb_legal_dong_migration_run (run_id, file_name, file_sha256, operator_id)
				VALUES (:runId, :fileName, :fileSha256, :operatorId)
				""";

		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("runId", runId)
				.addValue("fileName", fileName)
				.addValue("fileSha256", fileSha256)
				.addValue("operatorId", operatorId);

		jdbcTemplate.update(sql, params);
		return runId;
	}

	@Transactional
	public int snapshotBefore(UUID runId, List<String> legalDongCds) {
		if (runId == null || legalDongCds == null || legalDongCds.isEmpty()) {
			return 0;
		}

		String sql = """
				INSERT INTO tb_legal_dong_migration_run_row (
					run_id, legal_dong_cd, existed,
					legal_dong_nm, ctprv_cd, ctprv_nm, sgng_cd, sgng_nm, emndn_cd, emndn_nm,
					li_cd, li_nm, rank, cr_dt, dlt_dt, past_legal_dong_cd, use_yn
				)
				SELECT
					:runId AS run_id,
					c.code AS legal_dong_cd,
					(t.legal_dong_cd IS NOT NULL) AS existed,
					t.legal_dong_nm, t.ctprv_cd, t.ctprv_nm, t.sgng_cd, t.sgng_nm, t.emndn_cd, t.emndn_nm,
					t.li_cd, t.li_nm, t.rank, t.cr_dt, t.dlt_dt, t.past_legal_dong_cd, t.use_yn
				FROM (
					SELECT unnest(:codes)::varchar(10) AS code
				) c
				LEFT JOIN tb_legal_dong_l t ON t.legal_dong_cd = c.code
				ON CONFLICT (run_id, legal_dong_cd) DO NOTHING
				""";

		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("runId", runId)
				.addValue("codes", legalDongCds);

		return jdbcTemplate.update(sql, params);
	}

	@Transactional
	public RollbackResult rollback(UUID runId, String operatorId) {
		if (runId == null) {
			return new RollbackResult(0, 0, 0, "run_id가 비어 있습니다.");
		}

		// 이미 롤백된 run은 중복 실행을 방지한다.
		String checkSql = """
				SELECT rolled_back_at IS NOT NULL AS rolled_back
				FROM tb_legal_dong_migration_run
				WHERE run_id = :runId
				""";
		Boolean rolledBack = jdbcTemplate.queryForObject(checkSql, Map.of("runId", runId), Boolean.class);
		if (rolledBack == null) {
			return new RollbackResult(0, 0, 0, "존재하지 않는 run_id 입니다: " + runId);
		}
		if (rolledBack) {
			return new RollbackResult(0, 0, 0, "이미 롤백된 실행입니다: " + runId);
		}

		// 1) existed=false: 해당 코드가 실행 전에는 없었으므로 삭제한다.
		String deleteSql = """
				DELETE FROM tb_legal_dong_l t
				USING tb_legal_dong_migration_run_row r
				WHERE r.run_id = :runId
				  AND r.existed = false
				  AND t.legal_dong_cd = r.legal_dong_cd
				""";
		int deleted = jdbcTemplate.update(deleteSql, Map.of("runId", runId));

		// 2) existed=true: 실행 전 스냅샷으로 복원한다.
		String restoreSql = """
				UPDATE tb_legal_dong_l t
				SET
					legal_dong_nm = r.legal_dong_nm,
					ctprv_cd = r.ctprv_cd,
					ctprv_nm = r.ctprv_nm,
					sgng_cd = r.sgng_cd,
					sgng_nm = r.sgng_nm,
					emndn_cd = r.emndn_cd,
					emndn_nm = r.emndn_nm,
					li_cd = r.li_cd,
					li_nm = r.li_nm,
					rank = r.rank,
					cr_dt = r.cr_dt,
					dlt_dt = r.dlt_dt,
					past_legal_dong_cd = r.past_legal_dong_cd,
					use_yn = r.use_yn,
					last_updt_dtm = now(),
					last_upusr_id = :operatorId
				FROM tb_legal_dong_migration_run_row r
				WHERE r.run_id = :runId
				  AND r.existed = true
				  AND t.legal_dong_cd = r.legal_dong_cd
				""";
		MapSqlParameterSource restoreParams = new MapSqlParameterSource()
				.addValue("runId", runId)
				.addValue("operatorId", operatorId == null ? "SYSTEM" : operatorId);
		int restored = jdbcTemplate.update(restoreSql, restoreParams);

		String markSql = """
				UPDATE tb_legal_dong_migration_run
				SET rolled_back_at = now()
				WHERE run_id = :runId
				""";
		jdbcTemplate.update(markSql, Map.of("runId", runId));

		int total = countRunRows(runId);
		return new RollbackResult(total, restored, deleted, null);
	}

	private int countRunRows(UUID runId) {
		String sql = "SELECT count(*) FROM tb_legal_dong_migration_run_row WHERE run_id = :runId";
		Integer cnt = jdbcTemplate.queryForObject(sql, Map.of("runId", runId), Integer.class);
		return cnt == null ? 0 : cnt;
	}

	public List<UUID> findRecentRuns(int limit) {
		int safeLimit = Math.max(1, Math.min(limit, 20));
		String sql = """
				SELECT run_id
				FROM tb_legal_dong_migration_run
				ORDER BY created_at DESC
				LIMIT :limit
				""";
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, Map.of("limit", safeLimit));
		List<UUID> result = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Object v = row.get("run_id");
			if (v instanceof UUID uuid) {
				result.add(uuid);
			} else if (v != null) {
				result.add(UUID.fromString(v.toString()));
			}
		}
		return result;
	}

	public record RollbackResult(int snapshotRows, int restoredRows, int deletedRows, String message) {
	}
}

