package com.example.community.global.security.filter;

import com.example.community.auth.session.RefreshSessionStore;
import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.global.security.jwt.JwtToken;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserRole;
import com.example.community.user.entity.UserStatus;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class JwtFilterTest {
    @Mock
    JwtTokenProvider jwtTokenProvider;

    @Mock
    RefreshSessionStore refreshSessionStore;

    JwtFilter jwtFilter;

    @BeforeEach
    void setup(){
        jwtFilter = new JwtFilter(jwtTokenProvider, refreshSessionStore);
    }

    @AfterEach
    void tearDown(){
        SecurityContextHolder.clearContext();
    }
    @Test
    @DisplayName("유효한 토큰이면 SecurityContext에 인증 정보 저장")
    void validToken_setsAuthentication() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-jwt-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        Authentication mockAuthentication =
                new UsernamePasswordAuthenticationToken(
                        "1",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                );

        when(jwtTokenProvider.validateAccessToken("valid-jwt-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("valid-jwt-token")).thenReturn(1L);
        when(jwtTokenProvider.getSessionId("valid-jwt-token")).thenReturn("session-1");
        when(refreshSessionStore.isCurrentSession(1L, "session-1")).thenReturn(true);
        when(jwtTokenProvider.getAuthentication("valid-jwt-token")).thenReturn(mockAuthentication);
        jwtFilter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("유효하지 않은 토큰이면 SecurityContext에 저장하지 않는다.")
    void invalidToken_doesNotSetsAuthentication() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid-jwt-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(jwtTokenProvider.validateAccessToken("invalid-jwt-token")).thenReturn(false);

        jwtFilter.doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("만료된 Access Token이면 access_token_expired를 반환하고 chain을 진행하지 않는다.")
    void expiredAccessToken_returnsExpiredMessage() throws ServletException, IOException {
        String testSecret = "Y29tbXVuaXR5LXRlc3Qtb25seS1qd3Qtc2lnbmluZy1rZXktMjAyNg==";
        JwtTokenProvider realProvider = new JwtTokenProvider(testSecret, 0L, 6000L);
        User user = new User(1L, "tester", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        JwtToken token = realProvider.createJwtToken(user, "session-1");
        JwtFilter expiredFilter = new JwtFilter(realProvider, refreshSessionStore);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/posts");
        request.addHeader("Authorization", "Bearer " + token.getAccessToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        expiredFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("access_token_expired");
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(refreshSessionStore);
    }

    @Test
    @DisplayName("잘못된 Access Token이면 access_token_invalid를 반환하고 chain을 진행하지 않는다.")
    void invalidAccessToken_returnsInvalidMessage() throws ServletException, IOException {
        String testSecret = "Y29tbXVuaXR5LXRlc3Qtb25seS1qd3Qtc2lnbmluZy1rZXktMjAyNg==";
        JwtFilter invalidFilter = new JwtFilter(new JwtTokenProvider(testSecret, 3000L, 6000L), refreshSessionStore);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/posts");
        request.addHeader("Authorization", "Bearer malformed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        invalidFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("access_token_invalid");
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(refreshSessionStore);
    }

    @Test
    @DisplayName("현재 sessionId와 일치하지 않으면 access token을 거부한다")
    void revokedSession_doesNotContinueChain() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/posts");
        request.addHeader("Authorization", "Bearer revoked-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(jwtTokenProvider.validateAccessToken("revoked-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("revoked-token")).thenReturn(1L);
        when(jwtTokenProvider.getSessionId("revoked-token")).thenReturn("old-session");
        when(refreshSessionStore.isCurrentSession(1L, "old-session")).thenReturn(false);

        jwtFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("access_token_invalid");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("Redis 세션 검증에 실패하면 session_unavailable을 반환한다")
    void redisUnavailable_returns503() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/posts");
        request.addHeader("Authorization", "Bearer valid-jwt-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(jwtTokenProvider.validateAccessToken("valid-jwt-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("valid-jwt-token")).thenReturn(1L);
        when(jwtTokenProvider.getSessionId("valid-jwt-token")).thenReturn("session-1");
        when(refreshSessionStore.isCurrentSession(1L, "session-1"))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        jwtFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("session_unavailable");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("refresh 공개 경로는 만료된 Authorization 헤더가 있어도 필터를 통과한다")
    void refreshEndpoint_skipsAccessSessionValidation() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/auth/refresh");
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        verifyNoInteractions(jwtTokenProvider, refreshSessionStore);
    }
}
