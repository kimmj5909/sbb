package com.mysite.sbb.legaldong;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
/**
 * 법정동 검색/조회(DB 실시간 조회).
 *
 * 변경 배경
 * - Elasticsearch 기반 검색은 "재색인" 작업이 필요해 운영/관리 측면에서 번거롭다.
 * - 요구사항: {@link LegalDongTables#LEGAL_DONG_TABLE} 테이블을 직접 조회해 최신 데이터를 즉시 화면에 반영한다.
 *
 * 구현 포인트
 * - 화면 UX는 기존과 동일하게 "조건 선택 + 키워드 + 날짜범위"를 유지한다.
 * - DB 검색은 `ILIKE '%keyword%'` 기반 부분검색을 사용한다.
 * - 날짜 범위는 `to_date(cr_dt, 'YYYYMMDD')`로 비교한다(yyyyMMdd 문자열).
 * - 정렬은 `legal_dong_cd` 오름차순 고정(관리 화면 일관성/페이지 이동 안정성).
 */
public class LegalDongDbSearchService {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public LegalDongSearchResult search(LegalDongSearchRequest request) {
		int size = clampSize(request.getSize());
		int page = Math.max(request.getPage(), 0);
		int offset = page * size;

		Where where = Where.from(request);
		MapSqlParameterSource params = where.params
			.addValue("limit", size)
			.addValue("offset", offset);

		long total = jdbcTemplate.queryForObject(where.countSql(), params, Long.class);
		if (total <= 0) {
			return LegalDongSearchResult.empty(page, size);
		}

		List<LegalDongSearchRow> entries = jdbcTemplate.query(where.selectSql(), params, (rs, rowNum) -> new LegalDongSearchRow(
			rs.getString("legal_dong_cd"),
			rs.getString("legal_dong_nm"),
			rs.getString("ctprv_nm"),
			rs.getString("sgng_nm"),
			rs.getString("emndn_nm"),
			rs.getString("li_nm"),
			rs.getObject("rank", Integer.class),
			rs.getString("cr_dt"),
			rs.getString("dlt_dt"),
			rs.getString("past_legal_dong_cd"),
			rs.getString("use_yn")));

		return new LegalDongSearchResult(entries, total, page, size);
	}

	private int clampSize(int size) {
		// 관리자 화면에서 과도한 조회로 DB 부하가 급증하지 않도록 상한을 둔다.
		if (size <= 0) {
			return 40;
		}
		return Math.min(size, 200);
	}

	private record Where(String whereSql, MapSqlParameterSource params) {

		static Where from(LegalDongSearchRequest request) {
			StringBuilder where = new StringBuilder(" WHERE 1=1 ");
			MapSqlParameterSource params = new MapSqlParameterSource();

			applyKeyword(request, where, params);
			applyUseYnFilter(request, where);
			applyDateRange(request, where, params);

			return new Where(where.toString(), params);
		}

		String countSql() {
			return ("SELECT COUNT(*) FROM " + LegalDongTables.LEGAL_DONG_TABLE + " " + whereSql);
		}

		String selectSql() {
			/*
			 * 중요: String#formatted()는 "호출된 문자열 1개"에만 적용된다.
			 * - 아래처럼 텍스트 블록을 + 로 붙인 뒤 마지막 블록에만 formatted()를 호출하면,
			 *   앞 블록의 `%s`가 그대로 남아 "FROM %s" 형태의 잘못된 SQL이 실행될 수 있다.
			 * - 따라서 전체 SQL을 하나의 문자열로 합친 뒤, 그 결과에 formatted()를 적용한다.
			 */
			return ("""
					SELECT
						legal_dong_cd,
						legal_dong_nm,
						ctprv_nm,
						sgng_nm,
						emndn_nm,
						li_nm,
						"rank" AS rank,
						CASE
							WHEN cr_dt IS NULL THEN NULL
							WHEN cr_dt ~ '^[0-9]{8}$' THEN to_char(to_date(cr_dt, 'YYYYMMDD'), 'YYYY-MM-DD')
							ELSE NULL
						END AS cr_dt,
						CASE
							WHEN dlt_dt IS NULL THEN NULL
							WHEN dlt_dt ~ '^[0-9]{8}$' THEN to_char(to_date(dlt_dt, 'YYYYMMDD'), 'YYYY-MM-DD')
							ELSE NULL
						END AS dlt_dt,
						past_legal_dong_cd,
						use_yn
					FROM %s
					""" + whereSql + """
					ORDER BY legal_dong_cd
					LIMIT :limit OFFSET :offset
					""").formatted(LegalDongTables.LEGAL_DONG_TABLE);
		}

		private static void applyKeyword(LegalDongSearchRequest request, StringBuilder where, MapSqlParameterSource params) {
			// NOTE: 컬럼은 모두 varchar 계열(또는 null)로 가정하며, 부분검색은 ILIKE로 통일한다(대소문자 무시).
			//       legal_dong_cd는 숫자처럼 보이지만 "문자열 10자리" 규칙이라 문자로 검색한다.

			String keyword = request.getKeyword();
			if (StringUtils.hasText(keyword)) {
				applySelectBoxKeyword(where, params, request.getSearchField(), keyword);
				return;
			}

			// 구버전 링크/다중 필드 입력 방식 호환.
			// - selection box가 비어도 기존 파라미터(query/field별 값)가 들어오면 동일하게 동작해야 한다.
			if (StringUtils.hasText(request.getQuery())) {
				applySelectBoxKeyword(where, params, "all", request.getQuery());
			}

			applyFieldLike(where, params, "legalDongCd", "legal_dong_cd", request.getLegalDongCd());
			applyFieldLike(where, params, "legalDongNm", "legal_dong_nm", request.getLegalDongNm());
			applyFieldLike(where, params, "ctprvNm", "ctprv_nm", request.getCtprvNm());
			applyFieldLike(where, params, "sgngNm", "sgng_nm", request.getSgngNm());
			applyFieldLike(where, params, "emndnNm", "emndn_nm", request.getEmndnNm());
			applyFieldLike(where, params, "liNm", "li_nm", request.getLiNm());
			applyFieldLike(where, params, "pastLegalDongCd", "past_legal_dong_cd", request.getPastLegalDongCd());
		}

		private static void applySelectBoxKeyword(StringBuilder where, MapSqlParameterSource params, String field, String keyword) {
			String normalizedField = StringUtils.hasText(field) ? field.trim() : "all";
			String like = "%" + keyword.trim() + "%";
			params.addValue("kw", like);

			switch (normalizedField) {
				case "legalDongCd" -> where.append(" AND legal_dong_cd ILIKE :kw ");
				case "legalDongNm" -> where.append(" AND legal_dong_nm ILIKE :kw ");
				case "ctprvNm" -> where.append(" AND ctprv_nm ILIKE :kw ");
				case "sgngNm" -> where.append(" AND sgng_nm ILIKE :kw ");
				case "emndnNm" -> where.append(" AND emndn_nm ILIKE :kw ");
				case "liNm" -> where.append(" AND li_nm ILIKE :kw ");
				case "pastLegalDongCd" -> where.append(" AND past_legal_dong_cd ILIKE :kw ");
				case "all" -> where.append("""
						 AND (
							legal_dong_cd ILIKE :kw
							OR legal_dong_nm ILIKE :kw
							OR ctprv_nm ILIKE :kw
							OR sgng_nm ILIKE :kw
							OR emndn_nm ILIKE :kw
							OR li_nm ILIKE :kw
							OR past_legal_dong_cd ILIKE :kw
						 )
						""");
				default -> where.append("""
						 AND (
							legal_dong_cd ILIKE :kw
							OR legal_dong_nm ILIKE :kw
							OR ctprv_nm ILIKE :kw
							OR sgng_nm ILIKE :kw
							OR emndn_nm ILIKE :kw
							OR li_nm ILIKE :kw
							OR past_legal_dong_cd ILIKE :kw
						 )
						""");
			}
		}

		private static void applyFieldLike(StringBuilder where, MapSqlParameterSource params, String paramKey, String column, String raw) {
			if (StringUtils.hasText(raw) == false) {
				return;
			}
			String like = "%" + raw.trim() + "%";
			params.addValue(paramKey, like);
			where.append(" AND ").append(column).append(" ILIKE :").append(paramKey).append(" ");
		}

		private static void applyDateRange(LegalDongSearchRequest request, StringBuilder where, MapSqlParameterSource params) {
			String dateField = StringUtils.hasText(request.getDateField()) ? request.getDateField().trim() : "none";

			// dateFrom/dateTo는 컨트롤러에서 yyyyMMdd를 LocalDate로 파싱해 전달한다.
			// - null이면 조건 미적용
			// - DB 컬럼은 yyyyMMdd 문자열(varchar)로 저장하므로, 비교 시 to_date로 변환한다.
			// - 데이터 오염(8자리 숫자 외)이 있으면 to_date가 예외를 낼 수 있어, 정규식으로 먼저 방어한다.
			if ("crDt".equals(dateField) || ("none".equals(dateField) && (request.getCrDtFrom() != null || request.getCrDtTo() != null))) {
				if (request.getCrDtFrom() != null) {
					where.append(" AND cr_dt IS NOT NULL AND cr_dt ~ '^[0-9]{8}$' AND to_date(cr_dt, 'YYYYMMDD') >= :crFrom ");
					params.addValue("crFrom", request.getCrDtFrom());
				}
				if (request.getCrDtTo() != null) {
					where.append(" AND cr_dt IS NOT NULL AND cr_dt ~ '^[0-9]{8}$' AND to_date(cr_dt, 'YYYYMMDD') <= :crTo ");
					params.addValue("crTo", request.getCrDtTo());
				}
				return;
			}

			if ("dltDt".equals(dateField) || ("none".equals(dateField) && (request.getDltDtFrom() != null || request.getDltDtTo() != null))) {
				if (request.getDltDtFrom() != null) {
					where.append(" AND dlt_dt IS NOT NULL AND dlt_dt ~ '^[0-9]{8}$' AND to_date(dlt_dt, 'YYYYMMDD') >= :dltFrom ");
					params.addValue("dltFrom", request.getDltDtFrom());
				}
				if (request.getDltDtTo() != null) {
					where.append(" AND dlt_dt IS NOT NULL AND dlt_dt ~ '^[0-9]{8}$' AND to_date(dlt_dt, 'YYYYMMDD') <= :dltTo ");
					params.addValue("dltTo", request.getDltDtTo());
				}
			}
		}

		private static void applyUseYnFilter(LegalDongSearchRequest request, StringBuilder where) {
			String filter = request.getUseYnFilter();
			if (StringUtils.hasText(filter) == false || "all".equalsIgnoreCase(filter)) {
				return;
			}

			String normalized = filter.trim().toLowerCase();
			if ("used".equals(normalized) || "y".equals(normalized) || "yes".equals(normalized)) {
				where.append(" AND use_yn = 'Y' ");
				return;
			}

			if ("unused".equals(normalized) || "n".equals(normalized) || "no".equals(normalized) || "null".equals(normalized)) {
				where.append(" AND use_yn IS NULL ");
			}
		}
	}
}
