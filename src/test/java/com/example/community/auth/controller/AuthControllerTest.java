package com.example.community.auth.controller;

import com.example.community.auth.dto.LoginRequestDTO;
import com.example.community.auth.dto.LoginResponseDTO;
import com.example.community.global.security.jwt.JwtToken;
import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.auth.service.AuthService;
import com.example.community.global.exceptions.GlobalExceptionHandler;
import com.example.community.global.exceptions.NotRegisteredException;
import com.example.community.global.exceptions.PasswordInvalidException;
import com.example.community.global.exceptions.UnauthorizedException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = {
        "jwt.refresh-cookie-name=refresh_token",
        "jwt.refresh-cookie-path=/api/auth",
        "jwt.refresh-cookie-secure=true",
        "jwt.refresh-cookie-same-site=Lax",
        "jwt.refresh-token-expiration-ms=604800000"
})
class AuthControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthService authService;
    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("로그인 성공 시 access token과 refresh cookie를 반환한다.")
    void login_success_returns200() throws Exception {
        JwtToken token = new JwtToken("Bearer", "access-token", "refresh-token");
        when(authService.login(any(LoginRequestDTO.class)))
                .thenReturn(new LoginResponseDTO(1L, token, "tester", ""));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "email": "test@test.com",
                              "password": "Test1234!"
                            }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("user_login_success"))
                .andExpect(jsonPath("$.data.token.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.token.refreshToken").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("refresh_token=refresh-token"),
                                containsString("Max-Age=604800"),
                                containsString("Path=/api/auth"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax"),
                                containsString("Secure")
                        )));

        verify(authService).login(any(LoginRequestDTO.class));
    }

    @Test
    @DisplayName("로그인 이메일 양식이 잘못되면 400")
    void login_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "email": "wrong-email",
                              "password": "Test1234!"
                            }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("로그인 비밀번호 양식이 잘못되면 400")
    void login_invalidPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "email": "test@test.com",
                              "password": "password"
                            }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("등록되지 않은 이메일이면 401")
    void login_emailNotFound_returns401() throws Exception {
        when(authService.login(any(LoginRequestDTO.class))).thenThrow(new NotRegisteredException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "email": "none@test.com",
                              "password": "Test1234!"
                            }
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("user_not_found"));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401")
    void login_passwordInvalid_returns401() throws Exception {
        when(authService.login(any(LoginRequestDTO.class))).thenThrow(new PasswordInvalidException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "email": "test@test.com",
                              "password": "Wrong1234!"
                            }
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("password_invalid"));
    }

    @Test
    @DisplayName("로그인 중 Redis 장애는 503으로 반환한다.")
    void login_redisUnavailable_returns503() throws Exception {
        when(authService.login(any(LoginRequestDTO.class)))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "email": "test@test.com",
                              "password": "Test1234!"
                            }
                        """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("session_unavailable"));
    }

    @Test
    @DisplayName("refresh는 access token 없이 refresh cookie만으로 새 access token을 반환한다.")
    void refresh_withoutAccessToken_returnsAccessToken() throws Exception {
        JwtToken rotatedToken = new JwtToken("Bearer", "new-access-token", "new-refresh-token");
        when(authService.refresh("refresh-token")).thenReturn(rotatedToken);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("token_refresh_success"))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("refresh_token=new-refresh-token"),
                                containsString("Max-Age=604800"),
                                containsString("Path=/api/auth"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax"),
                                containsString("Secure")
                        )));
        verify(authService).refresh("refresh-token");
    }

    @Test
    @DisplayName("refresh cookie가 없으면 401과 만료 cookie를 반환한다.")
    void refresh_withoutCookie_returns401AndExpiresCookie() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("refresh_token_missing"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("refresh_token="),
                                containsString("Max-Age=0"),
                                containsString("Path=/api/auth"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax"),
                                containsString("Secure")
                        )));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("AuthService 검증 실패는 401과 refresh_token_invalid를 반환한다.")
    void refresh_invalidToken_returns401AndExpiresCookie() throws Exception {
        when(authService.refresh("invalid-refresh-token")).thenThrow(new UnauthorizedException());

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", "invalid-refresh-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("refresh_token_invalid"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("refresh_token="),
                                containsString("Max-Age=0"),
                                containsString("Path=/api/auth"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax"),
                                containsString("Secure")
                        )));

        verify(authService).refresh("invalid-refresh-token");
    }

    @Test
    @DisplayName("refresh 중 Redis 장애는 503으로 반환하고 cookie를 유지한다.")
    void refresh_redisUnavailable_returns503() throws Exception {
        when(authService.refresh("refresh-token"))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", "refresh-token")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("session_unavailable"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("logout은 현재 Access Token session을 종료하고 refresh cookie를 만료한다.")
    void logout_success_deletesSessionAndExpiresCookie() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "1",
                null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(jwtTokenProvider.getSessionId("access-token")).thenReturn("session-1");

        mockMvc.perform(post("/api/auth/logout")
                        .with(authentication(authentication))
                        .with(request -> {
                            request.setUserPrincipal(authentication);
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("logout_success"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("refresh_token="),
                                containsString("Max-Age=0"),
                                containsString("Path=/api/auth"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax"),
                                containsString("Secure")
                        )));

        verify(jwtTokenProvider).getSessionId("access-token");
        verify(authService).logout(1L, "session-1");
    }

    @Test
    @DisplayName("logout 중 Redis 장애는 503으로 반환한다.")
    void logout_redisUnavailable_returns503() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "1",
                null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(jwtTokenProvider.getSessionId("access-token")).thenReturn("session-1");
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(authService).logout(1L, "session-1");

        mockMvc.perform(post("/api/auth/logout")
                        .with(authentication(authentication))
                        .with(request -> {
                            request.setUserPrincipal(authentication);
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("session_unavailable"));
    }
}
