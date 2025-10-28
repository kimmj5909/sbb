package com.mysite.sbb.household;

import java.util.List;
import java.util.Optional;

import com.mysite.sbb.household.Category;
import com.mysite.sbb.household.TransactionType;
import com.mysite.sbb.household.CategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
/**
 * 가계부 카테고리에 대한 비즈니스 로직을 캡슐화한 서비스.
 * 컨트롤러에서 요청받은 CRUD 작업을 리포지토리로 위임한다.
 */
public class CategoryService {
	
	@Autowired
	private CategoryRepository categoryRepository;
	
	/**
	 * 카테고리를 저장하거나 갱신한다.
	 */
	public Category saveCategory(Category category) {
		return categoryRepository.save(category);
	}
	
	/**
	 * 모든 카테고리를 조회한다.
	 */
	public List<Category> getAllCategories() {
		return categoryRepository.findAll();
	}
	
	/**
	 * 식별자로 카테고리를 조회한다.
	 */
	public Optional<Category> getCategoryById(Long id) {
		return categoryRepository.findById(id);
	}
	
	/**
	 * 카테고리를 삭제한다.
	 */
	public void deleteCategory(Long id) {
		categoryRepository.deleteById(id);
	}
	
	/**
	 * 거래 유형으로 카테고리를 필터링한다.
	 */
	public List<Category> getCategoriesByType(TransactionType type) {
		return categoryRepository.findByType(type);
	}
}
