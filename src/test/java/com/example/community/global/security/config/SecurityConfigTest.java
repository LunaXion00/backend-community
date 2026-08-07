package com.example.community.global.security.config;

import com.example.community.global.security.jwt.JwtToken;
import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.auth.session.RefreshSessionStore;
import com.example.community.auth.controller.AuthController;
import com.example.community.auth.dto.LoginResponseDTO;
import com.example.community.auth.service.AuthService;
import com.example.community.global.controller.AdminController;
import com.example.community.post.controller.PostController;
import com.example.community.post.dto.PostPageResponseDTO;
import com.example.community.post.service.PostService;
import com.example.community.user.controller.UserController;
import com.example.community.user.dto.SignUpRequestDTO;
import com.example.community.user.dto.SignUpResponseDTO;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserRole;
import com.example.community.user.factory.UserFactory;
import com.example.community.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, UserController.class, PostController.class, AdminController.class})
@Import(SecurityConfig.class)
public class SecurityConfigTest {
    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    UserService userService;
    @MockitoBean
    AuthService authService;
    @MockitoBean
    PostService postService;
    @MockitoBean
    JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    RefreshSessionStore refreshSessionStore;

    private final UserFactory userFactory = new UserFactory();

    @Test
    @DisplayName("로그인 요청은 인증 없이도 로그인이 가능하다.")
    void loginRequest_canBeAccessedWithoutLogin() throws Exception {
        when(authService.login(any())).thenReturn(new LoginResponseDTO(1, new JwtToken("Bearer", "access-token1", "access-token2"), "nickname", ""));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                    {
                        "email":"test@test.com",
                        "password":"Test1234!"
                    }
                """)).andExpect(status().isOk());
    }
    @Test
    @DisplayName("회원가입 요청은 인증 없이도 가능하다.")
    void signupRequest_canBeAccessedWithoutLogin() throws Exception{
        when(userService.signUp(any())).thenReturn(new SignUpResponseDTO(1L));

        mockMvc.perform(post("/api/users/signup").contentType(MediaType.APPLICATION_JSON).content("""
                    {
                        "email":"test@test.com",
                        "password":"Test1234!",
                        "passwordConfirm":"Test1234!",
                        "nickname":"test1",
                        "profileImageUrl":""
                    }
                """)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("그 외 엔드포인트들은 인증이 필요하다.")
    void otherRequest_deniedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("unauthorized_user"));
    }

    @Test
    @DisplayName("인증된 사용자는 그 외 엔드포인트에도 요청이 가능하다.")
    void otherRequest_canBeAccessedWithAuthentication() throws Exception{
        when(postService.getPostList(0)).thenReturn(new PostPageResponseDTO(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/posts").with(user("test"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("관리자는 admin 엔드포인트로 요청이 가능하다.")
    void adminRequest_canBeAccessedWithRole() throws Exception{
        User admin = userFactory.create("admin1", "", UserRole.ROLE_ADMIN);
        mockMvc.perform(get("/api/admin").with(user(admin.getNickname()).authorities(new SimpleGrantedAuthority(admin.getRole().name())))).andExpect(status().isOk());
    }
    @Test
    @DisplayName("일반 유저는 admin 엔드포인트로 요청이 불가능.")
    void adminRequest_deniedWithoutRole() throws Exception{
        User user = userFactory.create(new SignUpRequestDTO("test1@email.com", "Test1234!", "Test1234!", "user", ""));
        mockMvc.perform(get("/api/admin").with(user(user.getNickname()).authorities(new SimpleGrantedAuthority(user.getRole().name())))).andExpect(status().isForbidden());
    }
}
