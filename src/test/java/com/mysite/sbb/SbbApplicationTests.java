package com.mysite.sbb;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.answer.AnswerRepository;
import com.mysite.sbb.question.Question;
import com.mysite.sbb.question.QuestionService;
import com.mysite.sbb.question.QuestionRepository;

import org.junit.jupiter.api.Test;
 
@SpringBootTest
@SpringBootApplication
@ContextConfiguration(classes = SbbApplicationTests.class) // -.- DB데이터 안들어간게 이거때문이었음
class SbbApplicationTests {

	@Autowired
	private QuestionRepository questionRepository;
	@Autowired
	private AnswerRepository answerRepository;
	@Autowired
	private QuestionService questionService;
	@Test
//	@Transactional
	void testJpa() {
		
		for(int i = 1; i <=1002; i++) {
			String subject = String.format("테스트 데이터입니다:[%03d]", i) ;
			String content = "내용 없음";
			this.questionService.create(subject, content, null);
		}
		/**
		Optional<Answer> oa = this.answerRepository.findById(2);
		assertTrue(oa.isPresent());
		Answer a = oa.get();
		assertEquals(2, a.getQuestion().getId());


		Optional<Question> oq = this.questionRepository.findById(8); 
		assertTrue(oq.isPresent());
		Question q = oq.get();
		
		Answer a = new Answer();
		a.setContent("ㅇㅇ자동생성됨");
		a.setQuestion(q);
		a.setCreateDate(LocalDateTime.now());
		this.answerRepository.save(a);
		
		
/**
		//	질문데이터 삭제
		assertEquals(2, this.questionRepository.count());
		Optional<Question> oq = this.questionRepository.findById(1);
		assertTrue(oq.isPresent());
		Question q = oq.get();
		this.questionRepository.delete(q);
		assertEquals(1, this.questionRepository.count());
**/
/** 질문데이터 수정
		Optional<Question> oq = this.questionRepository.findById(1);
		assertTrue(oq.isPresent());
		Question q = oq.get();
		q.setSubject("수정된 제목");
		this.questionRepository.save(q);
**/		
/**		특정 문자열 포함하는 데이터 조회
		List<Question> qList = this.questionRepository.findBySubjectLike("sbb%");
		Question q = qList.get(0);
		assertEquals("sbb가 무엇?", q.getSubject());
	**/	
/**		subject를 이용한 검색
		Question q = this.questionRepository.findBySubject("sbb가 무엇?");
		assertEquals(1, q.getId());
**/
/**  id를 이용한 검색		
		Optional<Question> oq = this.questionRepository.findById(1);
		if(oq.isPresent()) {
			Question q =oq.get();
			assertEquals("sbb가 무엇?", q.getSubject());
			}

//  DB 데이터 저장
		Question q1 = new Question();
//		Question q1 = Question.builder();
		q1.setSubject("sbb가 무엇?");
		q1.setCreateDate(LocalDateTime.now());
		q1.setContent("sbb가 알고싶다!");

//		questionRepository.saveAndFlush(q1);
		this.questionRepository.save(q1);
//		q1.build();
		
		
		
		Question q2 = new Question();
		q2.setSubject("spring 무엇인가요?");
		q2.setCreateDate(LocalDateTime.now());
		q2.setContent("id자동생성?");
		this.questionRepository.save(q2);
//		questionRepository.saveAndFlush(q2);
//		questionRepository.flush();

/**
		Question question = Question.builder()
			    .subject("제목")
			    .content("내용")
			    .createDate(LocalDateTime.now())
			    .build();
	// findAll 테스트
		List<Question> all = this.questionRepository.findAll();
		assertEquals(2, all.size());
		
		Question q = all.get(0);
		assertEquals("sbb가 무엇?", q.getSubject());
**/	
	}
	

}
