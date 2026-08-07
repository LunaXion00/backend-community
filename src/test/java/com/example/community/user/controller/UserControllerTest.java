package com.example.community.user.controller;

import com.example.community.auth.session.RefreshSessionStore;
import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.global.security.config.SecurityConfig;
import com.example.community.global.security.filter.JwtFilter;
import com.example.community.global.exceptions.*;
import com.example.community.user.dto.*;
import com.example.community.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtFilter.class})
public class UserControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;
    @MockitoBean
    JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    RefreshSessionStore refreshSessionStore;

    Authentication authentication;

    @BeforeEach
    void setUp() {
        authentication = new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    @Test
    @DisplayName("회원가입 성공 시 201")
    void signUp_success_returns201() throws Exception {
        when(userService.signUp(any(SignUpRequestDTO.class))).thenReturn(new SignUpResponseDTO(1L));

        mockMvc.perform(post("/api/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "email": "new@test.com",
                          "password": "Test1234!",
                          "passwordConfirm": "Test1234!",
                          "nickname": "newbie",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("user_register_success"));
    }

    @Test
    @DisplayName("회원가입 이메일 양식이 잘못되면 400")
    void signUp_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "email": "wrong-email",
                          "password": "Test1234!",
                          "passwordConfirm": "Test1234!",
                          "nickname": "newbie",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));

        verify(userService, never()).signUp(any());
    }

    @Test
    @DisplayName("회원가입 비밀번호 양식이 잘못되면 400")
    void signUp_invalidPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "email": "new@test.com",
                          "password": "password",
                          "passwordConfirm": "password",
                          "nickname": "newbie",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));

        verify(userService, never()).signUp(any());
    }

    @Test
    @DisplayName("비밀번호와 비밀번호 확인이 다르면 400")
    void signUp_passwordConfirmMismatch_returns400() throws Exception {
        when(userService.signUp(any(SignUpRequestDTO.class))).thenThrow(new InvalidInputException());

        mockMvc.perform(post("/api/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "email": "new@test.com",
                          "password": "Test1234!",
                          "passwordConfirm": "Wrong1234!",
                          "nickname": "newbie",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));
    }

    @Test
    @DisplayName("이미 존재하는 이메일이면 409")
    void signUp_emailAlreadyExists_returns409() throws Exception {
        when(userService.signUp(any(SignUpRequestDTO.class))).thenThrow(new AlreadyExistsException());

        mockMvc.perform(post("/api/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "email": "exist@test.com",
                          "password": "Test1234!",
                          "passwordConfirm": "Test1234!",
                          "nickname": "newbie",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("already_exists"));
    }

    @Test
    @DisplayName("이미 존재하는 닉네임이면 409")
    void signUp_nicknameAlreadyExists_returns409() throws Exception {
        when(userService.signUp(any(SignUpRequestDTO.class))).thenThrow(new AlreadyExistsException());

        mockMvc.perform(post("/api/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "email": "new@test.com",
                          "password": "Test1234!",
                          "passwordConfirm": "Test1234!",
                          "nickname": "exist",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("already_exists"));
    }

    @Test
    @DisplayName("회원정보 수정 성공 시 200")
    void modifyInfo_success_returns200() throws Exception {
        when(userService.modifyInfo(eq(1L), eq(1L), any(ModifyInfoRequestDTO.class))).thenReturn(new ModifyInfoResponseDTO(1L, "newName", "", LocalDateTime.of(2026, 7, 4, 12, 0)));

        mockMvc.perform(patch("/api/users/1/info")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "nickname": "newName",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("user_modify_success"));

        verify(userService).modifyInfo(eq(1L), eq(1L), any(ModifyInfoRequestDTO.class));
    }

    @Test
    @DisplayName("닉네임 양식이 잘못되면 400")
    void modifyInfo_invalidNickname_returns400() throws Exception {
        mockMvc.perform(patch("/api/users/1/info")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "nickname": "",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));

        verify(userService, never()).modifyInfo(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("회원정보 수정 시 토큰이 유효하지 않으면 401")
    void modifyInfo_invalidToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/users/1/info")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "nickname": "newName",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isUnauthorized());

        verify(userService, never()).modifyInfo(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("요청자와 수정 대상이 다르면 403")
    void modifyInfo_notOwner_returns403() throws Exception {
        when(userService.modifyInfo(eq(2L), eq(1L), any(ModifyInfoRequestDTO.class))).thenThrow(new ForbiddenException());

        Authentication otherUser = new UsernamePasswordAuthenticationToken(
                "2",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        mockMvc.perform(patch("/api/users/1/info")
                        .with(authentication(otherUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "nickname": "newName",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("access_denied"));
    }

    @Test
    @DisplayName("변경할 닉네임이 이미 존재하면 409")
    void modifyInfo_nicknameAlreadyExists_returns409() throws Exception {
        when(userService.modifyInfo(eq(1L), eq(1L), any(ModifyInfoRequestDTO.class))).thenThrow(new AlreadyExistsException());

        mockMvc.perform(patch("/api/users/1/info")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "nickname": "exist",
                          "profileImageUrl": ""
                        }
                    """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("already_exists"));
    }
    @Test
    @DisplayName("비밀번호 변경 성공 시 200")
    void modifyPassword_success_returns200() throws Exception {
        mockMvc.perform(patch("/api/users/1/password")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "password": "New12345!",
                          "passwordConfirm": "New12345!"
                        }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("password_modify_success"));

        verify(userService).modifyPassword(eq(1L), eq(1L), any(ModifyPasswordRequestDTO.class));
    }

    @Test
    @DisplayName("비밀번호 양식이 잘못되면 400")
    void modifyPassword_invalidPassword_returns400() throws Exception {
        mockMvc.perform(patch("/api/users/1/password")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "password": "password",
                          "passwordConfirm": "password"
                        }
                    """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));
    }

    @Test
    @DisplayName("지금 비밀번호와 동일하면 400")
    void modifyPassword_samePassword_returns400() throws Exception {
        doThrow(new InvalidInputException()).when(userService).modifyPassword(eq(1L), eq(1L), any(ModifyPasswordRequestDTO.class));

        mockMvc.perform(patch("/api/users/1/password")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "password": "Test1234!",
                          "passwordConfirm": "Test1234!"
                        }
                    """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));
    }

    @Test
    @DisplayName("비밀번호 변경 시 토큰이 유효하지 않으면 401")
    void modifyPassword_invalidToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/users/1/password")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "password": "New12345!",
                          "passwordConfirm": "New12345!"
                        }
                    """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비밀번호 변경 요청자와 대상이 다르면 403")
    void modifyPassword_notOwner_returns403() throws Exception {
        doThrow(new ForbiddenException()).when(userService).modifyPassword(eq(2L), eq(1L), any(ModifyPasswordRequestDTO.class));

        Authentication otherUser = new UsernamePasswordAuthenticationToken(
                "2",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        mockMvc.perform(patch("/api/users/1/password")
                        .with(authentication(otherUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "password": "New12345!",
                          "passwordConfirm": "New12345!"
                        }
                    """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("access_denied"));
    }
    @Test
    @DisplayName("회원 탈퇴 성공 시 200")
    void withdraw_success_returns200() throws Exception {
        when(userService.withdraw(1L, 1L)).thenReturn(new WithdrawResponseDTO(LocalDateTime.of(2026, 7, 4, 12, 0)));

        mockMvc.perform(delete("/api/users/1")
                        .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("user_withdraw_success"));

        verify(userService).withdraw(1L, 1L);
    }

    @Test
    @DisplayName("회원 탈퇴 시 토큰이 유효하지 않으면 401")
    void withdraw_invalidToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/users/1")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());

        verify(userService, never()).withdraw(anyLong(), anyLong());
    }

    @Test
    @DisplayName("회원 탈퇴 요청자와 대상이 다르면 403")
    void withdraw_notOwner_returns403() throws Exception {
        when(userService.withdraw(2L, 1L)).thenThrow(new ForbiddenException());

        Authentication otherUser = new UsernamePasswordAuthenticationToken(
                "2",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        mockMvc.perform(delete("/api/users/1")
                        .with(authentication(otherUser)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("access_denied"));
    }
}
