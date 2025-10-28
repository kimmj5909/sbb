package com.mysite.sbb;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

@Component
/**
 * 게시글 본문 등을 렌더링할 때 필요한 공통 유틸리티.
 * 현재는 Markdown 텍스트를 HTML로 변환하거나 이미 HTML일 경우 그대로 반환한다.
 */
public class CommonUtil {
	/**
	 * 입력 문자열이 HTML 구조인지 확인 후, 아니면 CommonMark 파서를 이용해 HTML로 변환한다.
	 * null 입력 시 빈 문자열을 반환하여 템플릿 렌더링 시 NPE를 방지한다.
	 */
	public String markdown(String markdown) {
        // HTML 태그가 있는지 확인 (주요 HTML 태그 검사)
        if (markdown == null) {
            return "";
        }
        
        // CKEditor 등으로 작성된 HTML 콘텐츠인지 확인
        if (markdown.contains("<table") || 
            markdown.contains("<p>") || 
            markdown.contains("<div") || 
            markdown.contains("<h1") || 
            markdown.contains("<h2") || 
            markdown.contains("<ul") || 
            markdown.contains("<ol")) {
            // HTML 태그가 있으면 그대로 반환
            return markdown;
        } else {
		Parser parser = Parser.builder().build();
		Node document = parser.parse(markdown);
		HtmlRenderer renderer = HtmlRenderer.builder().build();
		return renderer.render(document);			
        }
	}
}
