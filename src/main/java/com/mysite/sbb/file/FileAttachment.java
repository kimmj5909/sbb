package com.mysite.sbb.file;

import java.time.LocalDateTime;

import com.mysite.sbb.question.Question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
/**
 * 질문에 업로드되는 첨부파일 메타데이터를 보관하는 엔티티.
 * 원본 파일명, 저장 파일명, 경로, 크기, 업로드 시각과 소속 질문을 추적한다.
 */
public class FileAttachment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	@Column(nullable = false)
	private String originalFilename;
	
	@Column(nullable = false)
	private String storedFilename;
	
	private String filePath;
	
	private Long fileSize;
	
	private String contentType;
	
	private LocalDateTime uploadDate;
	
	@ManyToOne
	private Question question;
}
