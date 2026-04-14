package com.mysite.sbb.legaldong.mois;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MOIS(행정안전부) 법정동 변경내역 게시판 모니터링 배치 설정.
 *
 * - 설정 키를 @ConfigurationProperties로 수집해, application.properties에서 손쉽게 조정할 수 있게 한다.
 * - 스케줄/저장 경로/대상 게시판(bbsId) 등 운영 파라미터는 코드가 아니라 설정으로 관리한다.
 */
@Configuration
@EnableConfigurationProperties(MoisLegalDongProperties.class)
public class MoisLegalDongConfig {
}

