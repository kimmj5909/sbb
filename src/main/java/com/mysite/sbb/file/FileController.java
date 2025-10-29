package com.mysite.sbb.file;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Controller
@RequestMapping("/file")
/**
 * 첨부파일 다운로드 엔드포인트를 제공하는 컨트롤러.
 * 파일 ID를 이용해 저장소에서 리소스를 찾아 클라이언트로 전송한다.
 */
public class FileController {
	
	private final FileService fileService;
	
	/**
	 * 파일 ID로 메타데이터를 조회하고 실제 파일을 Resource 형태로 내려준다.
	 * - 존재하지 않거나 읽을 수 없는 경우 IOException을 던져 상위에서 처리한다.
	 * - Content-Disposition 헤더에 UTF-8 인코딩된 원본 파일명을 설정한다.
	 */
	@GetMapping("/download/{fileId}")
	public ResponseEntity<Resource> downloadFile(@PathVariable("fileId") Integer fileId) throws IOException {
		FileAttachment file = fileService.getFileById(fileId);
		
		Path path = Paths.get(file.getFilePath());
		Resource resource = new UrlResource(path.toUri());
		
		if(!resource.exists() || !resource.isReadable()) {
			throw new IOException("Could not read file : " + file.getOriginalFilename());
		}
		
		String contentType = file.getContentType();
		if(contentType == null) {
			contentType = Files.probeContentType(path);
		}
		if(contentType == null) {
			contentType = "application/octet-stream";
		}
//		엥? 브라우저에서 바로 열려서 주석 아래 새로 작성함 -> URLEncoder로 변경           
		/*
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(contentType))
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getOriginalFilename() + "\"")
				.body(resource);
*/	
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(contentType))
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + URLEncoder.encode(file.getOriginalFilename(),"UTF-8") + "\"")
				//다른이름으로 저장 변경해보기.....
				.body(resource);
	
	}

}
