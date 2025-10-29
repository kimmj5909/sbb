package com.mysite.sbb.user;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Controller
@RequestMapping("/user")
/**
 * 회원 가입, 로그인 화면을 제공하는 사용자 컨트롤러.
 * 관리자 여부에 따라 템플릿에서 분기하도록 모델 데이터를 구성한다.
 */
public class UserController {
	private final UserService userService;
	/*
	@GetMapping("/login")
	public String login() {
		return "login_form";
	}
	*/
	/**
	 * 로그인 페이지를 렌더링하며, 현재 로그인한 사용자가 관리자 권한인지 여부를 모델에 담는다.
	 */
	@GetMapping("/login")
		public String login(Model model){
		model.addAttribute("isAdmin", false);
		
		Authentication athentication = SecurityContextHolder.getContext().getAuthentication();
		if (athentication != null && athentication.isAuthenticated() && athentication.getPrincipal() instanceof UserDetails) {
			UserDetails userDetails = (UserDetails) athentication.getPrincipal();
			
			boolean isAdmin = userDetails.getUsername().startsWith("admin") ||
					userDetails.getAuthorities().stream()
					.anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
			model.addAttribute("isAdmin", isAdmin);
		}
		return "login_form";
	}
	
	/**
	 * 회원 가입 폼 진입.
	 */
	@GetMapping("/signup")
	public String signup(UserCreateForm userCreateForm) {
		return "signup_form";
	}
	
	/**
	 * 회원 가입 요청 처리.
	 * - 비밀번호 일치 여부를 검증하고 중복 가입을 방지한다.
	 */
	@PostMapping("/signup")
	public String signup(@Valid UserCreateForm userCreateForm, BindingResult bindingResult) {
		if(bindingResult.hasErrors()) {
			return "signup_form";
		} 
		
		if(!userCreateForm.getPassword1().equals(userCreateForm.getPassword2())) {
			bindingResult.rejectValue("password2","passwordInCorrect", "비밀번호가 일치하지 않습니다.");
			return "signup_form";
		}
	
	try {
		userService.create(userCreateForm.getUsername(), userCreateForm.getEmail(), userCreateForm.getPassword1()
							,userCreateForm.getPhone());
		}
			catch(DataIntegrityViolationException e) {
				e.printStackTrace();
				bindingResult.reject("signupFaild", "이미 등록된 사용자입니다.");
				return "signup_form";
			}
			catch(Exception e) {
				e.printStackTrace();
				bindingResult.reject("signupFailed", e.getMessage());
				return "signup_form";
			}
			return "redirect:/";
	}
	
	/**
	 * 로그인 이후 관리자 여부를 확인해 모델에 저장한다.
	 * 확인 후 다시 메인 페이지로 리다이렉트한다.
	 */
	@GetMapping("/check-admin")//로그인 성공 후 리다이렉트되는 페이지에서 관리자 확인을 위한 메서드
	public String checkAdmin(Model model) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if(authentication != null && authentication.isAuthenticated() &&
			authentication.getPrincipal() instanceof UserDetails) {
			UserDetails userDetails = (UserDetails) authentication.getPrincipal();
			boolean isAdmin = userDetails.getUsername().startsWith("admin") ||
					userDetails.getAuthorities().stream()
					.anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
			model.addAttribute("isAdmin", isAdmin);
		}else {
			model.addAttribute("isAdmin", false);
		}
		return "redirect:/";
					
	}
}

