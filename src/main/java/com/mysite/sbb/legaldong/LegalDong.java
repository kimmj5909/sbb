package com.mysite.sbb.legaldong;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tb_legal_dong_l")
@Data
@NoArgsConstructor
@AllArgsConstructor
/**
 * 법정동 코드(법정동/읍면동/시군구/시도) 정보를 저장하는 엔티티.
 *
 * 담당 역할
 * - 테스트/운영 DB의 기준 테이블 `tb_legal_dong_l`에 매핑된다.
 * - 마이그레이션 업서트 결과를 JPA로 조회하거나, 향후 API/화면 확장 시 재사용한다.
 *
 * 요구사항 포인트
 * - 법정동코드는 "10자리 문자열"로 저장한다(엑셀 숫자 서식/앞 0 유실 방지).
 * - 단위별로 "하위 코드가 없음"은 꼬리값 00/000 패턴으로 판단해 해당 컬럼을 null 처리한다.
 * - 말소 여부는 dltDt만으로 판단하고 useYn은 기본 null 유지한다.
 */
public class LegalDong {

	/**
	 * 10자리 법정동 코드(문자열).
	 */
	@Id
	@Column(name = "legal_dong_cd", length = 10, nullable = false)
	private String legalDongCd;

	/**
	 * 단위별 명칭을 공백으로 연결한 최종 표기명(예: "경기도 화성시 만세구 우정읍 원안리").
	 */
	@Column(name = "legal_dong_nm", length = 200, nullable = false)
	private String legalDongNm;

	@Column(name = "ctprv_cd", length = 2, nullable = false)
	private String ctprvCd;

	@Column(name = "ctprv_nm", length = 50, nullable = false)
	private String ctprvNm;

	@Column(name = "sgng_cd", length = 5)
	private String sgngCd;

	@Column(name = "sgng_nm", length = 50)
	private String sgngNm;

	@Column(name = "emndn_cd", length = 8)
	private String emndnCd;

	@Column(name = "emndn_nm", length = 50)
	private String emndnNm;

	@Column(name = "li_cd", length = 10)
	private String liCd;

	@Column(name = "li_nm", length = 50)
	private String liNm;

	/**
	 * 단일 컬럼 rank.
	 *
	 * 요구사항(변경 반영)
	 * - 같은 시군구(sgng_cd) 내에서 읍면동(emndn) 단위 상위 행은 1,2,3...으로 증가한다.
	 * - 리(li) 단위 하위 행은 해당 읍면동 그룹 내에서 1,2,3...으로 증가하고, 읍면동이 바뀌면 1로 리셋한다.
	 * - 시군구/시도 단위처럼 읍면동이 없는 행은 1로 둔다.
	 */
	@Column(name = "rank")
	private Integer rank;

	/**
	 * 생성일자(yyyyMMdd) - 입력값이 없으면 null 허용(요구사항에서 명확히 지정되지 않아 nullable로 유지).
	 */
	@Column(name = "cr_dt")
	private String crDt;

	/**
	 * 말소일자(yyyyMMdd) - 말소일자가 없으면 null.
	 */
	@Column(name = "dlt_dt")
	private String dltDt;

	/**
//	 * 말소된 이전 코드(10자리) - 이번 마이그레이션 범위에서는 업데이트하지 않는다.
	 */
	@Column(name = "past_legal_dong_cd", length = 10)
	private String pastLegalDongCd;

	@Column(name = "frst_wrtng_dtm")
	private LocalDateTime frstWrtngDtm;

	@Column(name = "frst_writr_id", length = 100)
	private String frstWritrId;

	@Column(name = "last_updt_dtm")
	private LocalDateTime lastUpdtDtm;

	@Column(name = "last_upusr_id", length = 100)
	private String lastUpusrId;

	/**
	 * 사용 여부 플래그. 요구사항 확정: 기본값 null 유지.
	 */
	@Column(name = "use_yn", length = 1)
	private String useYn;
}
