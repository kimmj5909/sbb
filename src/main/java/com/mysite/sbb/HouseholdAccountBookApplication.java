package com.mysite.sbb;

import com.mysite.sbb.household.Category;
import com.mysite.sbb.household.TransactionType;
import com.mysite.sbb.household.CategoryService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
/**
 * 가계부 모듈의 Spring Boot 애플리케이션 엔트리 포인트.
 * 기본 카테고리 데이터를 초기화하는 CommandLineRunner를 등록한다.
 */
public class HouseholdAccountBookApplication {
	
	public static void main(String[] args) {
		SpringApplication.run(HouseholdAccountBookApplication.class, args);
	}
	
	//초기데이터 생성
	/**
	 * 애플리케이션 시작 시 기본 카테고리를 저장한다.
	 * 이미 데이터가 존재하면 추가하지 않는다.
	 */
	@Bean
	public CommandLineRunner initData(CategoryService categoryService) {
		return args -> {
			//기본 수입 카테고리
			if(categoryService.getAllCategories().isEmpty()) {
				categoryService.saveCategory(new Category(null, "급여", "월급, 보너스 등", TransactionType.INCOME));
				categoryService.saveCategory(new Category(null, "투자수익", "주식, 펀드 등", TransactionType.INCOME));
				categoryService.saveCategory(new Category(null, "용돈", "받은 용돈", TransactionType.INCOME));
				categoryService.saveCategory(new Category(null, "기타수입", "기타수입", TransactionType.INCOME));
		
			//기본 지출 카테고리	
				categoryService.saveCategory(new Category(null, "식비","음식, 식료품", TransactionType.EXPENSE));
				categoryService.saveCategory(new Category(null, "교통비","대중교통, 택시, 유류비", TransactionType.EXPENSE));
				categoryService.saveCategory(new Category(null, "주거비","월세, 관리비", TransactionType.EXPENSE));
				categoryService.saveCategory(new Category(null, "의료비","병원, 약국", TransactionType.EXPENSE));
				categoryService.saveCategory(new Category(null, "문화생활","영화, 공연, 취미", TransactionType.EXPENSE));
				categoryService.saveCategory(new Category(null, "쇼핑","의류, 생필품", TransactionType.EXPENSE));
				categoryService.saveCategory(new Category(null, "기타지출","기타 지출", TransactionType.EXPENSE));
			}	
		};
	}
}
