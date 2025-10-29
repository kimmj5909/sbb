package com.mysite.sbb.question;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.file.FileAttachment;
import com.mysite.sbb.user.SiteUser;

import lombok.Getter;
import lombok.Setter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Transient;

//import lombok.NoArgsConstructor;
//import lombok.AllArgsConstructor;
//import lombok.Builder;

//@NoArgsConstructor
//@AllArgsConstructor
//@Builder

@Entity
@Setter
@Getter
/**
 * 질문 게시글 엔티티.
 * - 제목/내용, 작성자, 생성/수정 시각을 저장하며 답변·첨부파일·추천자 정보를 연결한다.
 * - 총 답변/댓글 수를 계산하는 유틸리티 메서드를 제공한다.
 */
public class Question {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	@Column(length = 200)
	private String subject;

	@Column(columnDefinition = "TEXT")
	private String content;
	
	private LocalDateTime createDate;

	@OneToMany(mappedBy = "question", cascade = CascadeType.REMOVE)
	private List<Answer> answerList = new ArrayList<>();

	@ManyToOne
	private SiteUser author;
	
	private LocalDateTime modifyDate;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean notice = false;
	
	@ManyToMany
	Set<SiteUser> voter = new HashSet<>();

	@OneToMany(mappedBy ="question", cascade = CascadeType.REMOVE)
	private List<FileAttachment> fileAttachments = new ArrayList<>();

	/**
	 * 목록 화면에서 사전 계산한 이미지 포함 여부를 저장한다.
	 * - JPA 영속 필드가 아니므로 @Transient로 마킹해 DB 칼럼 생성을 방지한다.
	 * - null이면 아직 계산되지 않은 상태로 간주하고, 연관 첨부파일/본문을 검사한다.
	 */
	@Transient
	private Boolean imageAttachmentPrefetched;
	
	/**
	 * 목록에 노출할 첨부파일 유무를 캐시한다.
	 * - 파일이 하나라도 있으면 true, 비어 있으면 false.
	 */
	@Transient
	private Boolean attachmentsPrefetched;
	
	/**
	 * 총 댓글 수를 계산할 때 NPE를 피하기 위해 관계가 없을 수도 있는 컬렉션을 방어적으로 검사한다.
	 * - answerList가 아직 초기화되지 않았거나 비어 있으면 0을 반환한다.
	 * - 각 Answer의 commentList 역시 null 가능성이 있으므로 안전하게 건너뛴다.
	 */
	public int getTotalCommentsCount() { // 게시물에 표시되는 답변 갯수 합산
		if (this.answerList == null || this.answerList.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (Answer answer : this.answerList) {
			if (answer == null || answer.getCommentList() == null) {
				continue;
			}
			count += answer.getCommentList().size();
		}
		return count;
	}

	public int getTotalRepliesCount() {
		int answerCount = this.answerList != null ? this.answerList.size() : 0;
		return answerCount + getTotalCommentsCount();
	}

	/**
	 * 게시글에 이미지 첨부가 포함되어 있는지 확인한다.
	 * - contentType이 image/로 시작하거나 파일 확장자를 기반으로 판별한다.
	 */
	public boolean hasImageAttachment() {
		if (Boolean.TRUE.equals(this.imageAttachmentPrefetched)) {
			return true;
		}
		if (Boolean.FALSE.equals(this.imageAttachmentPrefetched)) {
			return false;
		}
		if (this.fileAttachments != null) {
			for (FileAttachment attachment : this.fileAttachments) {
				if (attachment == null) {
					continue;
				}
				String contentType = attachment.getContentType();
				if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
					return true;
				}
				if (hasImageExtension(attachment.getOriginalFilename())) {
					return true;
				}
				if (hasImageExtension(attachment.getStoredFilename())) {
					return true;
				}
			}
		}
		return containsEmbeddedImage();
	}

	/**
	 * CKEditor 등에서 본문에 직접 삽입된 이미지 여부를 확인한다.
	 * - 단순 문자열 검색으로 `<img` 태그 존재를 검사한다.
	 */
	public boolean containsEmbeddedImage() {
		return this.content != null && this.content.toLowerCase(Locale.ROOT).contains("<img");
	}

	/**
	 * 일반 첨부파일(이미지 포함)의 존재 여부를 반환한다.
	 * - 목록 조회시 사전 계산된 값이 있으면 그대로 활용한다.
	 * - 그렇지 않으면 연관 리스트를 확인한다.
	 */
	public boolean hasAttachment() {
		if (Boolean.TRUE.equals(this.attachmentsPrefetched)) {
			return true;
		}
		if (Boolean.FALSE.equals(this.attachmentsPrefetched)) {
			return false;
		}
		return this.fileAttachments != null && !this.fileAttachments.isEmpty();
	}

	private boolean hasImageExtension(String filename) {
		if (filename == null) {
			return false;
		}
		String lower = filename.toLowerCase(Locale.ROOT);
		return lower.endsWith(".png")
				|| lower.endsWith(".jpg")
				|| lower.endsWith(".jpeg")
				|| lower.endsWith(".gif")
				|| lower.endsWith(".bmp")
				|| lower.endsWith(".webp");
	}
}
