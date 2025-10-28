package com.mysite.sbb.household;

import com.mysite.sbb.household.Transaction;
import com.mysite.sbb.household.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
/**
 * 가계부 거래 내역을 조회/저장하는 Spring Data JPA 리포지토리.
 * 기간, 유형, 카테고리, 연/월 기준 검색 메서드를 제공한다.
 */
public interface TransactionRepository extends JpaRepository<Transaction, Long>{
    
    List<Transaction> findByTransactionDateBetween(LocalDateTime start, LocalDateTime end);
    
    List<Transaction> findByType(TransactionType type);
    
    List<Transaction> findByCategoryId(Long categoryId);
    
    @Query("SELECT t FROM Transaction t WHERE YEAR(t.transactionDate) = :year AND MONTH(t.transactionDate) = :month")
    List<Transaction> findByYearAndMonth(int year, int month);
}
