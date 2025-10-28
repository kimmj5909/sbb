package com.mysite.sbb.household;

//import com.mysite.sbb.household.Transaction;
//import com.mysite.sbb.household.TransactionType;
//import com.mysite.sbb.household.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
/**
 * 가계부 거래 내역에 대한 비즈니스 로직을 처리하는 서비스.
 * CRUD와 기간/유형별 조회, 월별 합계 계산을 제공한다.
 */
public class TransactionService {
	
	@Autowired
	private TransactionRepository transactionRepository;
	
	/**
	 * 거래를 저장하거나 수정한다.
	 */
	public Transaction saveTransaction(Transaction transaction) {
		return transactionRepository.save(transaction);
	}
	
	/**
	 * 전체 거래 목록을 반환한다.
	 */
	public List<Transaction> getAllTransactions(){
		return transactionRepository.findAll();
	}
	
	/**
	 * 식별자로 거래를 조회한다.
	 */
	public Optional<Transaction> getTransactionById(Long id){
		return transactionRepository.findById(id);
	}
	
	/**
	 * 거래를 삭제한다.
	 */
	public void deleteTransaction(Long id) {
		transactionRepository.deleteById(id);
	}
	
	/**
	 * 특정 일자의 거래를 조회한다.
	 */
	public List<Transaction> getTransactionByDate(LocalDate date){
		LocalDateTime startOfDay = date.atStartOfDay();
		LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
		return transactionRepository.findByTransactionDateBetween(startOfDay, endOfDay);
	}
	
	/**
	 * 특정 연도/월의 거래를 조회한다.
	 */
	public List<Transaction> getTransactionByMonth(int year, int month) {
		return transactionRepository.findByYearAndMonth(year, month);
	}
	
	/**
	 * 거래 유형 기반 필터링.
	 */
	public List<Transaction> getTransactionByType(TransactionType type) {
		return transactionRepository.findByType(type);
	}
	
	/**
	 * 카테고리별 거래 목록 조회.
	 */
	public List<Transaction> getTransactionByCategory(Long categoryId) { 
		return transactionRepository.findByCategoryId(categoryId);
	}
	
	/**
	 * 월별 총 수입 합계를 계산한다.
	 */
	public BigDecimal calculateMonthlyIncome(int year, int month) {
		List<Transaction> incomes = transactionRepository.findByYearAndMonth(year, month)
				.stream()
				.filter(t -> t.getType() == TransactionType.INCOME)
				.toList();
		
		return incomes.stream()
				.map(Transaction::getAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}
	
	/**
	 * 월별 총 지출 합계를 계산한다.
	 */
	public BigDecimal calculateMonthlyExpense(int year, int month) {
		List<Transaction> expenses = transactionRepository.findByYearAndMonth(year, month)
				.stream()
				.filter(t-> t.getType() == TransactionType.EXPENSE)
				.toList();
		
        return expenses.stream()
				.map(Transaction::getAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

	}
}
