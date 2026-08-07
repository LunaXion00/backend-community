package com.example.community.realtime.controller;

import com.example.community.auth.session.RefreshSessionStore;
import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.global.security.config.SecurityConfig;
import com.example.community.global.security.filter.JwtFilter;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.global.exceptions.ForbiddenException;
import com.example.community.global.exceptions.InvalidInputException;
import com.example.community.realtime.connection.RealtimeInterestType;
import com.example.community.realtime.service.RealtimeStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RealtimeStreamController.class)
@Import({SecurityConfig.class, JwtFilter.class})
class RealtimeStreamControllerTest {

    private static final long MAX_CONNECTION_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RealtimeStreamService realtimeStreamService;

    @MockitoBean
    JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    RefreshSessionStore refreshSessionStore;

    Authentication authentication;

    @BeforeEach
    void setUp() throws Exception {
        authentication = new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(jwtTokenProvider.validateAccessToken("access-token")).thenReturn(true);
        when(jwtTokenProvider.getAuthentication("access-token")).thenReturn(authentication);
        when(jwtTokenProvider.getUserId("access-token")).thenReturn(1L);
        when(refreshSessionStore.isCurrentSession(1L, "session-1")).thenReturn(true);
        when(jwtTokenProvider.getSessionId("access-token")).thenReturn("session-1");
        when(realtimeStreamService.connect(eq(1L), eq("session-1"), any(SseEmitter.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    @DisplayName("인증 사용자는 SSE stream을 연다")
    void opensStreamForAuthenticatedUser() throws Exception {
        when(jwtTokenProvider.getRemainingValidityMillis("access-token")).thenReturn(10_000L);

        mockMvc.perform(get("/api/realtime/stream")
                        .with(authentication(authentication))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        ArgumentCaptor<SseEmitter> emitterCaptor = ArgumentCaptor.forClass(SseEmitter.class);
        verify(realtimeStreamService).connect(
                eq(1L), eq("session-1"), emitterCaptor.capture()
        );
        assertThat(emitterCaptor.getValue().getTimeout()).isEqualTo(10_000L);
        emitterCaptor.getValue().complete();
    }

    @Test
    @DisplayName("SSE 연결시간은 서버 최대 연결시간을 넘지 않는다")
    void limitsStreamToServerMaximumTimeout() throws Exception {
        when(jwtTokenProvider.getRemainingValidityMillis("access-token"))
                .thenReturn(Duration.ofHours(1).toMillis());

        mockMvc.perform(get("/api/realtime/stream")
                        .with(authentication(authentication))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        ArgumentCaptor<SseEmitter> emitterCaptor = ArgumentCaptor.forClass(SseEmitter.class);
        verify(realtimeStreamService).connect(
                eq(1L), eq("session-1"), emitterCaptor.capture()
        );
        assertThat(emitterCaptor.getValue().getTimeout()).isEqualTo(MAX_CONNECTION_TIMEOUT_MILLIS);
        emitterCaptor.getValue().complete();
    }

    @Test
    @DisplayName("남은 유효시간이 없는 Access Token은 SSE stream을 열 수 없다")
    void rejectsAccessTokenWithoutRemainingValidity() throws Exception {
        when(jwtTokenProvider.getRemainingValidityMillis("access-token")).thenReturn(0L);

        mockMvc.perform(get("/api/realtime/stream")
                        .with(authentication(authentication))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted());

        verifyNoInteractions(realtimeStreamService);
    }

    @Test
    @DisplayName("Bearer 형식이 아닌 Authorization header는 거부한다")
    void rejectsMalformedAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/realtime/stream")
                        .with(authentication(authentication))
                        .header(HttpHeaders.AUTHORIZATION, "Basic access-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted());

        verifyNoInteractions(realtimeStreamService);
    }

    @Test
    @DisplayName("인증되지 않은 사용자는 SSE stream을 열 수 없다")
    void rejectsUnauthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/realtime/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted());

        verifyNoInteractions(realtimeStreamService);
    }

    @Test
    @DisplayName("인증 사용자는 연결의 관심 상태를 변경한다")
    void updatesInterestForAuthenticatedUser() throws Exception {
        when(realtimeStreamService.updateInterest(
                1L,
                "connection-1",
                RealtimeInterestType.POST_DETAIL,
                10L,
                1L
        )).thenReturn(true);

        mockMvc.perform(patch("/api/realtime/connections/connection-1/interest")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "type": "POST_DETAIL",
                                    "postId": 10,
                                    "revision": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("realtime_interest_update_success"))
                .andExpect(jsonPath("$.data").value(true));

        verify(realtimeStreamService).updateInterest(
                1L,
                "connection-1",
                RealtimeInterestType.POST_DETAIL,
                10L,
                1L
        );
    }

    @Test
    @DisplayName("관심 상태 type과 revision은 필수다")
    void requiresInterestTypeAndRevision() throws Exception {
        mockMvc.perform(patch("/api/realtime/connections/connection-1/interest")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "postId": 10,
                                    "revision": 1
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/realtime/connections/connection-1/interest")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "type": "POST_DETAIL",
                                    "postId": 10
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(realtimeStreamService);
    }

    @Test
    @DisplayName("알 수 없는 관심 상태 type은 400으로 거부한다")
    void rejectsUnknownInterestType() throws Exception {
        mockMvc.perform(patch("/api/realtime/connections/connection-1/interest")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "type": "UNKNOWN",
                                    "revision": 1
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(realtimeStreamService);
    }

    @Test
    @DisplayName("POST_DETAIL의 유효하지 않은 postId는 400으로 반환한다")
    void rejectsInvalidPostIdForPostDetail() throws Exception {
        when(realtimeStreamService.updateInterest(
                1L,
                "connection-1",
                RealtimeInterestType.POST_DETAIL,
                0L,
                1L
        )).thenThrow(new InvalidInputException());

        mockMvc.perform(patch("/api/realtime/connections/connection-1/interest")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "type": "POST_DETAIL",
                                    "postId": 0,
                                    "revision": 1
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("오래된 revision은 200과 false를 반환한다")
    void returnsFalseWhenRevisionIsStale() throws Exception {
        when(realtimeStreamService.updateInterest(
                1L,
                "connection-1",
                RealtimeInterestType.POST_LIST,
                null,
                1L
        )).thenReturn(false);

        mockMvc.perform(patch("/api/realtime/connections/connection-1/interest")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "type": "POST_LIST",
                                    "revision": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
    }

    @Test
    @DisplayName("없는 연결의 관심 상태 변경은 404다")
    void returnsNotFoundForMissingConnection() throws Exception {
        when(realtimeStreamService.updateInterest(
                1L,
                "missing",
                RealtimeInterestType.POST_LIST,
                null,
                1L
        )).thenThrow(new ContentNotFoundException());

        mockMvc.perform(patch("/api/realtime/connections/missing/interest")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "type": "POST_LIST",
                                    "revision": 1
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 사용자의 연결 관심 상태 변경은 403이다")
    void returnsForbiddenForAnotherUsersConnection() throws Exception {
        when(realtimeStreamService.updateInterest(
                1L,
                "connection-2",
                RealtimeInterestType.POST_LIST,
                null,
                1L
        )).thenThrow(new ForbiddenException());

        mockMvc.perform(patch("/api/realtime/connections/connection-2/interest")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "type": "POST_LIST",
                                    "revision": 1
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인증되지 않은 사용자는 연결 관심 상태를 변경할 수 없다")
    void rejectsUnauthenticatedInterestUpdate() throws Exception {
        mockMvc.perform(patch("/api/realtime/connections/connection-1/interest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "type": "POST_LIST",
                                    "revision": 1
                                }
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(realtimeStreamService);
    }
}
