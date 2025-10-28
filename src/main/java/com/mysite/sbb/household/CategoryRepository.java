package com.mysite.sbb.household;

import com.mysite.sbb.household.Category;
import com.mysite.sbb.household.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
/**
 * 카테고리 엔티티에 대한 CRUD 및 커스텀 조회 메서드를 제공하는 리포지토리.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {	
	List<Category> findByType(TransactionType type);
}
