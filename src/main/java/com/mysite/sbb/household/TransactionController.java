package com.mysite.sbb.household;

import com.mysite.sbb.household.TransactionType;
import com.mysite.sbb.household.Transaction;
import com.mysite.sbb.household.CategoryService;
import com.mysite.sbb.household.TransactionService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/transactions")
/**
 * 가계부 거래 내역과 관련된 화면을 처리하는 컨트롤러.
 * - 전체 목록, 일별/월별 뷰, 통계 페이지 등 다양한 조회 기능을 제공한다.
 */
public class TransactionController {
	
    @Autowired
    private TransactionService transactionService;
    
    @Autowired
    private CategoryService categoryService;
    
    /**
     * 전체 거래 목록 페이지.
     */
    @GetMapping
    public String listTransactions(Model model) {
        model.addAttribute("transactions", transactionService.getAllTransactions());
        return "transactions/list";
    }
    
    /**
     * 신규 거래 등록 폼.
     * - 수입/지출 카테고리를 나눠 전달한다.
     */
    @GetMapping("/new")
    public String newTransactionForm(Model model) {
        model.addAttribute("transaction", new Transaction());
        model.addAttribute("incomeCategories", categoryService.getCategoriesByType(TransactionType.INCOME));
        model.addAttribute("expenseCategories", categoryService.getCategoriesByType(TransactionType.EXPENSE));
        model.addAttribute("types", TransactionType.values());
        return "transactions/form";
    }
    
    /**
     * 거래 저장 처리.
     */
    @PostMapping
    public String saveTransaction(@ModelAttribute Transaction transaction) {
        transactionService.saveTransaction(transaction);
        return "redirect:/transactions";
    }
    
    /**
     * 거래 수정 폼.
     */
    @GetMapping("/{id}/edit")
    public String editTransactionForm(@PathVariable Long id, Model model) {
        Transaction transaction = transactionService.getTransactionById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid transaction Id:" + id));
        model.addAttribute("transaction", transaction);
        model.addAttribute("incomeCategories", categoryService.getCategoriesByType(TransactionType.INCOME));
        model.addAttribute("expenseCategories", categoryService.getCategoriesByType(TransactionType.EXPENSE));
        model.addAttribute("types", TransactionType.values());
        return "transactions/form";
    }
    
    /**
     * 거래 삭제 후 목록으로 리다이렉트.
     */
    @GetMapping("/{id}/delete")
    public String deleteTransaction(@PathVariable Long id) {
        transactionService.deleteTransaction(id);
        return "redirect:/transactions";
    }
    
    /**
     * 특정 일자의 거래를 조회하는 뷰.
     */
    @GetMapping("/daily")
    public String dailyView(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model model) {
        if (date == null) {
              date = LocalDate.now();
        }
        model.addAttribute("date", date);
        model.addAttribute("transactions", transactionService.getTransactionByDate(date));
        return "transactions/daily";
    }
    
    /**
     * 월별 거래 목록 및 요약 금액을 표시한다.
     */
    @GetMapping("/monthly")
    public String monthlyView(@RequestParam(required = false) Integer year, 
                             @RequestParam(required = false) Integer month, 
                             Model model) {
        if (year == null || month == null) {
            YearMonth current = YearMonth.now();
            year = current.getYear();
            month = current.getMonthValue();
        }
        
        model.addAttribute("year", year);
        model.addAttribute("month", month);
        model.addAttribute("transactions", transactionService.getTransactionByMonth(year, month));
        
        BigDecimal totalIncome = transactionService.calculateMonthlyIncome(year, month);
        BigDecimal totalExpense = transactionService.calculateMonthlyExpense(year, month);
        BigDecimal balance = totalIncome.subtract(totalExpense);
        
        model.addAttribute("totalIncome", totalIncome);
        model.addAttribute("totalExpense", totalExpense);
        model.addAttribute("balance", balance);
        
        return "transactions/monthly";
    }
    
    /**
     * 월별 카테고리 지출 통계를 계산해 시각화 페이지로 전달한다.
     */
    @GetMapping("/stats")
    public String statisticsView(@RequestParam(required = false) Integer year, 
                               @RequestParam(required = false) Integer month, 
                               Model model) {
        if (year == null || month == null) {
            YearMonth current = YearMonth.now();
            year = current.getYear();
            month = current.getMonthValue();
        }
        
        // 카테고리별 지출 합계를 계산
        Map<String, BigDecimal> categoryExpenses = new HashMap<>();
        transactionService.getTransactionByMonth(year, month).stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .forEach(t -> {
                    String categoryName = t.getCategory().getName();
                    categoryExpenses.merge(categoryName, t.getAmount(), BigDecimal::add);
                });
        
        model.addAttribute("year", year);
        model.addAttribute("month", month);
        model.addAttribute("categoryExpenses", categoryExpenses);
        model.addAttribute("totalIncome", transactionService.calculateMonthlyIncome(year, month));
        model.addAttribute("totalExpense", transactionService.calculateMonthlyExpense(year, month));
        
        return "transactions/stats";
    }
}
