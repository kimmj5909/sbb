package com.mysite.sbb.household;

import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
/**
 * 가계부 카테고리를 정의하는 엔티티.
 * - 지출/수입 구분(`TransactionType`)과 이름, 설명을 관리한다.
 */
public class Category {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	private String name;
	
	private String description;
	
	@Enumerated(EnumType.STRING)
	private TransactionType type; // 어떤 거래 유형에 해당하는 카테고리인지
}
