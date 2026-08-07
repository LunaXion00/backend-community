package com.example.community.global.security.jwt;

import com.example.community.user.entity.User;
import com.example.community.user.entity.UserRole;
import com.example.community.user.entity.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenContractTest {
    private static final String TEST_SECRET = "Y29tbXVuaXR5LXRlc3Qtb25seS1qd3Qtc2lnbmluZy1rZXktMjAyNg==";
    private static final String SESSION_ID = "session-1";
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(TEST_SECRET, 3000L, 6000L);
    private final User user = new User(1L, "tester", "", UserRole.ROLE_USER, UserStatus.ACTIVE);

    @Test
    @DisplayName("Access와 Refresh Token은 같은 sessionId를 공유한다")
    void accessAndRefreshShareSessionId() {
        JwtToken token = jwtTokenProvider.createJwtToken(user, SESSION_ID);

        assertThat(jwtTokenProvider.getSessionId(token.getAccessToken())).isEqualTo(SESSION_ID);
        assertThat(jwtTokenProvider.getSessionId(token.getRefreshToken())).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("Access와 Refresh Token은 서로 다른 tokenType을 가진다")
    void accessAndRefreshHaveDifferentTokenTypes() {
        JwtToken token = jwtTokenProvider.createJwtToken(user, SESSION_ID);

        assertThat(jwtTokenProvider.getTokenType(token.getAccessToken())).isEqualTo("access");
        assertThat(jwtTokenProvider.getTokenType(token.getRefreshToken())).isEqualTo("refresh");
    }

    @Test
    @DisplayName("Refresh Token은 Access Token 검증에 사용할 수 없다")
    void refreshTokenCannotBeUsedAsAccessToken() {
        JwtToken token = jwtTokenProvider.createJwtToken(user, SESSION_ID);

        assertThat(jwtTokenProvider.validateAccessToken(token.getRefreshToken())).isFalse();
    }

    @Test
    @DisplayName("Access Token은 Refresh Token 검증에 사용할 수 없다")
    void accessTokenCannotBeUsedAsRefreshToken() {
        JwtToken token = jwtTokenProvider.createJwtToken(user, SESSION_ID);

        assertThat(jwtTokenProvider.validateRefreshToken(token.getAccessToken())).isFalse();
    }
}
