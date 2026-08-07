package com.example.community.global.security.jwt;

import com.example.community.user.entity.User;
import com.example.community.user.entity.UserRole;
import com.example.community.user.entity.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class JwtTokenProviderTest {
    private static final String TEST_SECRET = "Y29tbXVuaXR5LXRlc3Qtb25seS1qd3Qtc2lnbmluZy1rZXktMjAyNg==";
    private static final String SESSION_ID = "session-1";
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(TEST_SECRET, 3000L, 6000L);

    @Test
    @DisplayName("JWT 토큰을 생성할 수 있다.")
    void createJwtToken() {
        User user = new User(1L, "tester", "", UserRole.ROLE_USER, UserStatus.ACTIVE);

        JwtToken jwtToken = jwtTokenProvider.createJwtToken(user, SESSION_ID);

        assertThat(jwtToken.getGrantType()).isEqualTo("Bearer");
        assertThat(jwtToken.getAccessToken()).isNotBlank();
        assertThat(jwtToken.getRefreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("Access Token을 토대로 UserId를 추출.")
    void createJwtToken_andExtractUserId(){
        User user = new User(1L, "test", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        JwtToken token = jwtTokenProvider.createJwtToken(user, SESSION_ID);
        assertThat(jwtTokenProvider.getUserId(token.getAccessToken())).isEqualTo(1L);
    }

    @Test
    @DisplayName("Access Token은 role기반 생성, 추출 가능.")
    void createJwtToken_andExtractUserRole(){
        User admin = new User(1L, "test", "", UserRole.ROLE_ADMIN, UserStatus.ACTIVE);
        JwtToken token = jwtTokenProvider.createJwtToken(admin, SESSION_ID);
        assertThat(jwtTokenProvider.getRole(token.getAccessToken())).isEqualTo("ROLE_ADMIN");
    }

    @Test
    @DisplayName("정상적으로 발급된 토큰은 유효.")
    void validTokenTest(){
        User user = new User(1L, "test", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        JwtToken token = jwtTokenProvider.createJwtToken(user, SESSION_ID);
        assertThat(jwtTokenProvider.validateAccessToken(token.getAccessToken())).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰은 유효하지 않음.")
    void expiredTokenTest() throws InterruptedException {
        JwtTokenProvider shortProvider = new JwtTokenProvider(TEST_SECRET, 0L, 1000L);
        User user = new User(1L, "test", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        JwtToken token = shortProvider.createJwtToken(user, SESSION_ID);
        assertThat(jwtTokenProvider.validateAccessToken(token.getAccessToken())).isFalse();
    }

    @Test
    @DisplayName("잘못된 토큰은 유효하지 않음.")
    void invalidTokenTest(){
        assertThat(jwtTokenProvider.validateAccessToken("invalid_token")).isFalse();
    }

    @Test
    @DisplayName("Access Token의 남은 유효시간을 밀리초로 반환한다")
    void getsRemainingAccessTokenValidityMillis() {
        User user = new User(1L, "test", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        String accessToken = jwtTokenProvider.createJwtToken(user, SESSION_ID).getAccessToken();

        long remainingMillis = jwtTokenProvider.getRemainingValidityMillis(accessToken);

        assertThat(remainingMillis).isBetween(1L, 3000L);
    }

    @Test
    @DisplayName("만료된 Access Token의 남은 유효시간은 0이다")
    void expiredAccessTokenHasNoRemainingValidity() {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(TEST_SECRET, 0L, 1000L);
        User user = new User(1L, "test", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        String accessToken = expiredProvider.createJwtToken(user, SESSION_ID).getAccessToken();

        assertThat(expiredProvider.getRemainingValidityMillis(accessToken)).isZero();
    }
}
