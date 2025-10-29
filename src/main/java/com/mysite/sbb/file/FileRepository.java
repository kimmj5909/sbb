package com.mysite.sbb.file;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mysite.sbb.question.Question;

/**
 * 첨부파일 엔티티 리포지토리.
 * - 질문별 첨부파일 목록 조회, ID 오름차순 전체 조회 메서드를 제공한다.
 */
public interface FileRepository extends JpaRepository<FileAttachment, Integer>{
	List<FileAttachment> findByQuestion(Question question);
	List<Question> findAllByOrderByIdAsc();

	/**
	 * 질문에 최소 한 개 이상의 첨부파일이 존재하는지 여부를 반환한다.
	 */
	boolean existsByQuestionId(Integer questionId);

	/**
	 * 특정 질문에 연결된 첨부파일 중 이미지 유형이 존재하는지 빠르게 확인한다.
	 * - contentType과 확장자를 동시에 검사해 브라우저가 채우지 않은 MIME 타입도 보완한다.
	 */
	@Query("""
		select (count(f) > 0) from FileAttachment f
		where f.question.id = :questionId
		  and (
			lower(coalesce(f.contentType, '')) like 'image/%'
			or lower(coalesce(f.originalFilename, '')) like '%.png'
			or lower(coalesce(f.originalFilename, '')) like '%.jpg'
			or lower(coalesce(f.originalFilename, '')) like '%.jpeg'
			or lower(coalesce(f.originalFilename, '')) like '%.gif'
			or lower(coalesce(f.originalFilename, '')) like '%.bmp'
			or lower(coalesce(f.originalFilename, '')) like '%.webp'
			or lower(coalesce(f.storedFilename, '')) like '%.png'
			or lower(coalesce(f.storedFilename, '')) like '%.jpg'
			or lower(coalesce(f.storedFilename, '')) like '%.jpeg'
			or lower(coalesce(f.storedFilename, '')) like '%.gif'
			or lower(coalesce(f.storedFilename, '')) like '%.bmp'
			or lower(coalesce(f.storedFilename, '')) like '%.webp'
		  )
	""")
	boolean existsImageByQuestionId(@Param("questionId") Integer questionId);
}
