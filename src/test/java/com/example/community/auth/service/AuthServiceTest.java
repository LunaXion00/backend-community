package com.example.community.auth.service;

import com.example.community.auth.dto.LoginRequestDTO;
import com.example.community.auth.dto.LoginResponseDTO;
import com.example.community.auth.session.RefreshSession;
import com.example.community.auth.session.RefreshSessionStore;
import com.example.community.auth.session.RefreshTokenHasher;
import com.example.community.global.security.jwt.JwtToken;
import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.global.exceptions.NotRegisteredException;
import com.example.community.global.exceptions.PasswordInvalidException;
import com.example.community.global.exceptions.UnauthorizedException;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserCredential;
import com.example.community.user.entity.UserRole;
import com.example.community.user.entity.UserStatus;
import com.example.community.user.repository.UserCredentialRepository;
import com.example.community.user.repository.UserRepository;
import com.example.community.realtime.service.RealtimeStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    UserRepository userRepository;
    @Mock
    UserCredentialRepository userCredentialRepository;
    @Mock
    JwtTokenProvider jwtTokenProvider;
    @Mock
    RefreshSessionStore refreshSessionStore;
    @Mock
    RefreshTokenHasher refreshTokenHasher;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    RealtimeStreamService realtimeStreamService;

    @InjectMocks
    AuthService authService;

    private User user;
    private UserCredential credential;
    private LoginRequestDTO loginRequest;

    @BeforeEach
    void setUp() {
        user = new User(1L, "tester", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        credential = new UserCredential(user, "test@test.com", "encoded-password");
        loginRequest = new LoginRequestDTO();
        loginRequest.setEmail("test@test.com");
        loginRequest.setPassword("Test1234!");
    }

    @Test
    @DisplayName("로그인 성공 시 토큰과 사용자 정보를 반환하고 refresh session을 저장한다.")
    void login_returnsTokenAndSavesRefreshSession() {
        JwtToken token = new JwtToken("Bearer", "access-token", "refresh-token");
        when(userCredentialRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("Test1234!", "encoded-password")).thenReturn(true);
        when(jwtTokenProvider.createJwtToken(eq(user), anyString())).thenReturn(token);
        when(jwtTokenProvider.getRemainingValidityMillis("refresh-token")).thenReturn(604800000L);
        when(refreshTokenHasher.hash("refresh-token")).thenReturn("refresh-hash");

        LoginResponseDTO response = authService.login(loginRequest);

        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getToken()).isEqualTo(token);
        assertThat(response.getNickname()).isEqualTo("tester");

        ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(jwtTokenProvider).createJwtToken(eq(user), sessionIdCaptor.capture());
        verify(refreshSessionStore).replace(argThat(session ->
                session.userId() == user.getUserId()
                        && session.sessionId().equals(sessionIdCaptor.getValue())
                        && session.refreshTokenHash().equals("refresh-hash")
                        && session.expiresAt().isAfter(Instant.now())
        ));
        verifyNoInteractions(realtimeStreamService);
    }

    @Test
    @DisplayName("로그인 시 기존 session이 교체되면 이전 session의 SSE 연결을 종료한다")
    void login_replacesExistingSessionAndNotifiesPreviousSession() {
        JwtToken token = new JwtToken("Bearer", "access-token", "refresh-token");
        when(userCredentialRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("Test1234!", "encoded-password")).thenReturn(true);
        when(jwtTokenProvider.createJwtToken(eq(user), anyString())).thenReturn(token);
        when(jwtTokenProvider.getRemainingValidityMillis("refresh-token")).thenReturn(604800000L);
        when(refreshTokenHasher.hash("refresh-token")).thenReturn("refresh-hash");
        when(refreshSessionStore.replace(any(RefreshSession.class))).thenReturn(Optional.of("old-session"));

        authService.login(loginRequest);

        verify(realtimeStreamService).sendSessionReplaced("old-session");
    }

    @Test
    @DisplayName("이메일이 등록되지 않으면 401")
    void login_emailNotFound_throwsNotRegisteredException() {
        when(userCredentialRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(NotRegisteredException.class);
        verifyNoInteractions(jwtTokenProvider, refreshSessionStore, refreshTokenHasher);
    }

    @Test
    @DisplayName("비밀번호가 다르면 401")
    void login_passwordInvalid_throwsPasswordInvalidException() {
        when(userCredentialRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("Test1234!", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(PasswordInvalidException.class);
        verifyNoInteractions(jwtTokenProvider, refreshSessionStore, refreshTokenHasher);
    }

    @Test
    @DisplayName("탈퇴한 유저가 로그인 시도하면 401")
    void login_withdrawnAccount_throwsNotRegisteredException() {
        user.withDraw();
        when(userCredentialRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(NotRegisteredException.class);
        verifyNoInteractions(passwordEncoder, jwtTokenProvider, refreshSessionStore, refreshTokenHasher);
    }

    @Test
    @DisplayName("유효한 refresh token은 같은 sessionId로 rotation한다.")
    void refresh_rotatesSession(){
        String refreshToken = "refresh-token";
        String sessionId = "session-1";
        RefreshSession current = new RefreshSession(
                user.getUserId(),
                sessionId,
                "old-hash",
                Instant.now().plusSeconds(60)
        );
        JwtToken rotatedToken = new JwtToken("Bearer", "new-access", "new-refresh");

        when(jwtTokenProvider.validateRefreshToken(refreshToken)).thenReturn(true);
        when(jwtTokenProvider.getUserId(refreshToken)).thenReturn(user.getUserId());
        when(jwtTokenProvider.getSessionId(refreshToken)).thenReturn(sessionId);
        when(refreshTokenHasher.hash(refreshToken)).thenReturn("old-hash");
        when(refreshSessionStore.findByUserId(user.getUserId())).thenReturn(Optional.of(current));
        when(userRepository.findById(user.getUserId())).thenReturn(Optional.of(user));
        when(jwtTokenProvider.createJwtToken(user, sessionId)).thenReturn(rotatedToken);
        when(jwtTokenProvider.getRemainingValidityMillis("new-refresh")).thenReturn(604800000L);
        when(refreshTokenHasher.hash("new-refresh")).thenReturn("new-hash");
        when(refreshSessionStore.rotateIfHashMatches(eq(user.getUserId()), eq("old-hash"), any(RefreshSession.class)))
                .thenReturn(true);

        assertThat(authService.refresh(refreshToken)).isEqualTo(rotatedToken);

        verify(refreshSessionStore).rotateIfHashMatches(
                eq(user.getUserId()),
                eq("old-hash"),
                argThat(replacement -> replacement.sessionId().equals(sessionId)
                        && replacement.refreshTokenHash().equals("new-hash"))
        );
    }

    @Test
    @DisplayName("access token은 refresh에 사용할 수 없다.")
    void refresh_rejectsNonRefreshToken(){
        String accessToken = "access-token";
        when(jwtTokenProvider.validateRefreshToken(accessToken)).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh(accessToken))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(refreshSessionStore, refreshTokenHasher, userRepository);
    }

    @Test
    @DisplayName("현재 session이 없으면 refresh할 수 없다.")
    void refresh_rejectsMissingSession(){
        String refreshToken = "refresh-token";
        when(jwtTokenProvider.validateRefreshToken(refreshToken)).thenReturn(true);
        when(jwtTokenProvider.getUserId(refreshToken)).thenReturn(user.getUserId());
        when(jwtTokenProvider.getSessionId(refreshToken)).thenReturn("session-1");
        when(refreshTokenHasher.hash(refreshToken)).thenReturn("old-hash");
        when(refreshSessionStore.findByUserId(user.getUserId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(UnauthorizedException.class);
        verify(refreshSessionStore, never()).rotateIfHashMatches(anyLong(), anyString(), any(RefreshSession.class));
    }

    @Test
    @DisplayName("logout은 userId와 sessionId가 일치하는 세션을 삭제한다.")
    void logout_deletesMatchingSession() {
        when(refreshSessionStore.deleteIfSessionMatches(1L, "session-1")).thenReturn(true);

        authService.logout(1L, "session-1");

        verify(refreshSessionStore).deleteIfSessionMatches(1L, "session-1");
        verify(realtimeStreamService).closeSessionConnections("session-1");
    }

    @Test
    @DisplayName("이전 session의 늦은 logout은 새 session을 삭제하지 않고 성공한다.")
    void logout_withStaleSession_doesNotDeleteCurrentSession() {
        when(refreshSessionStore.deleteIfSessionMatches(1L, "old-session")).thenReturn(false);

        assertThatCode(() -> authService.logout(1L, "old-session")).doesNotThrowAnyException();

        verify(refreshSessionStore).deleteIfSessionMatches(1L, "old-session");
        verifyNoInteractions(realtimeStreamService);
        verifyNoMoreInteractions(refreshSessionStore);
    }
}
