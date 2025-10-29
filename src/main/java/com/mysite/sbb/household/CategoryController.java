package com.mysite.sbb.household;

import com.mysite.sbb.household.Category;
import com.mysite.sbb.household.TransactionType;
import com.mysite.sbb.household.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/categories")
/**
 * 가계부 카테고리를 CRUD 하는 웹 컨트롤러.
 * - 목록 조회, 생성/수정 폼, 삭제까지 Thymeleaf 템플릿과 연동한다.
 */
public class CategoryController {
	
	@Autowired
	private CategoryService categoryService;
	
	/**
	 * 카테고리 목록 페이지를 렌더링한다.
	 */
	@GetMapping
	public String listCategories(Model model) {
		model.addAttribute("categories", categoryService.getAllCategories());
		return "categories/list";
	}
	
	/**
	 * 신규 카테고리 등록 폼을 노출한다.
	 * - 거래 유형 선택을 위해 enum 값을 모델에 전달한다.
	 */
	@GetMapping("/new")
	public String newCategoryForm(Model model) {
		model.addAttribute("category", new Category());
		model.addAttribute("types", TransactionType.values());
		return "categories/form";
	}

	/**
	 * 카테고리를 저장한다. 새 엔티티 저장과 수정 모두 처리한다.
	 */
	@PostMapping
	public String saveCategory(@ModelAttribute Category category) {
		categoryService.saveCategory(category);
		return "redirect:/categories";
	}
	
	/**
	 * 카테고리 수정 폼을 노출한다.
	 * - 존재하지 않는 경우 IllegalArgumentException을 던져 에러 페이지로 유도한다.
	 */
	@GetMapping("/{id}/edit")
	public String editCategoryForm(@PathVariable Long id, Model model) {
		Category category = categoryService.getCategoryById(id)
				.orElseThrow(() -> new IllegalArgumentException("Invalid category Id : " + id));
		model.addAttribute("category", category);
		model.addAttribute("types", TransactionType.values());
		return "categories/form";
	}
	
	/**
	 * 카테고리를 삭제하고 목록으로 되돌아간다.
	 */
	@GetMapping("/{id}/delete")
	public String deleteCategory(@PathVariable Long id) {
		categoryService.deleteCategory(id);
		return "redirect:/categories";
	}
}
