package com.mysite.sbb.tmp;

import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
/**
 * Lombok 테스트용 임시 Book 클래스.
 * 제목과 저자 필드를 보유한다.
 */
public class Book {
	private String title;
	private String auther;
	/**
	private final String title;
	private final String auther;
	
	public static void main(String[] args)  {
		Book book = new Book("title", "auther");
		System.out.println(book.getTitle());
		System.out.println(book.getAuther());
	}
	**/
}
