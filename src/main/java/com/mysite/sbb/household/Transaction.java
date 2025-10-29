package com.mysite.sbb.household;

import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
/**
 * 가계부의 개별 거래 내역을 표현하는 엔티티.
 * 금액, 유형(수입/지출), 카테고리, 거래 시각, 상세 설명을 저장한다.
 */
public class Transaction {

    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String title;
    
    private BigDecimal amount;
    
    @Enumerated(EnumType.STRING)
    private TransactionType type; // INCOME, EXPENSE
    
    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;
    
    private LocalDateTime transactionDate;
    
    private String description;
}
