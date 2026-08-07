package com.example.community.auth.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshSessionStoreTest {

    private static final long USER_ID = 7L;
    private static final String SESSION_ID = "session-7";
    private static final String REFRESH_TOKEN_HASH = "hash-1";
    private static final String KEY = "refresh:session:user:7";
    private static final long REFRESH_SESSION_TTL_MS = Duration.ofDays(7).toMillis();
    private static final Instant EXPIRES_AT = Instant.parse("2030-08-10T00:00:00Z");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RefreshSessionStore store;

    @BeforeEach
    void setUp() {
        store = new RefreshSessionStore(redisTemplate, REFRESH_SESSION_TTL_MS);
    }

    @Test
    @DisplayName("세션을 사용자별 키와 7일 TTL로 저장한다")
    void savesSessionWithUserKeyAndTtl() throws Exception {
        RefreshSession session = session(REFRESH_TOKEN_HASH, EXPIRES_AT);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        store.save(session);

        verify(valueOperations).set(eq(KEY), valueCaptor.capture(), eq(Duration.ofMillis(REFRESH_SESSION_TTL_MS)));
        JsonNode stored = objectMapper.readTree(valueCaptor.getValue());
        assertThat(stored.path("userId").asLong()).isEqualTo(USER_ID);
        assertThat(stored.path("sessionId").asText()).isEqualTo(SESSION_ID);
        assertThat(stored.path("refreshTokenHash").asText()).isEqualTo(REFRESH_TOKEN_HASH);
        assertThat(stored.path("expiresAt").asLong()).isEqualTo(EXPIRES_AT.toEpochMilli());
    }

    @Test
    @DisplayName("새 세션으로 원자 교체하고 기존 sessionId를 반환한다")
    void replacesSessionAndReturnsPreviousSessionId() {
        RefreshSession replacement = session("hash-2", EXPIRES_AT);
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                eq(List.of(KEY)),
                any(String.class),
                any(String.class)
        )).thenReturn("old-session");

        assertThat(store.replace(replacement)).contains("old-session");
    }

    @Test
    @DisplayName("기존 세션이 없으면 교체 결과가 비어 있다")
    void replacesMissingSessionWithEmptyPreviousSessionId() {
        RefreshSession replacement = session("hash-2", EXPIRES_AT);
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                eq(List.of(KEY)),
                any(String.class),
                any(String.class)
        )).thenReturn(null);

        assertThat(store.replace(replacement)).isEmpty();
    }

    @Test
    @DisplayName("사용자 키로 저장된 세션을 조회한다")
    void findsSessionByUserId() throws Exception {
        RefreshSession expected = session(REFRESH_TOKEN_HASH, EXPIRES_AT);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn(storedJson(expected));

        Optional<RefreshSession> actual = store.findByUserId(USER_ID);

        assertThat(actual).contains(expected);
    }

    @Test
    @DisplayName("세션이 없으면 빈 Optional을 반환한다")
    void returnsEmptyWhenSessionMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn(null);

        assertThat(store.findByUserId(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("현재 sessionId가 일치할 때만 세션을 삭제한다")
    void deletesOnlyWhenSessionIdMatches() {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), eq(SESSION_ID))).thenReturn(1L);

        assertThat(store.deleteIfSessionMatches(USER_ID, SESSION_ID)).isTrue();
    }

    @Test
    @DisplayName("다른 sessionId이면 세션을 삭제하지 않는다")
    void doesNotDeleteWhenSessionIdDiffers() {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), eq("other-session"))).thenReturn(0L);

        assertThat(store.deleteIfSessionMatches(USER_ID, "other-session")).isFalse();
    }

    @Test
    @DisplayName("사용자 기준으로 현재 refresh session을 삭제한다")
    void deletesSessionByUserId() {
        when(redisTemplate.delete(KEY)).thenReturn(true);

        assertThat(store.deleteByUserId(USER_ID)).isTrue();
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("기존 refresh hash가 일치할 때만 Rotation한다")
    void rotatesOnlyWhenRefreshHashMatches() {
        RefreshSession replacement = session("hash-2", EXPIRES_AT);
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(KEY)),
                eq(REFRESH_TOKEN_HASH),
                any(String.class),
                any(String.class)
        )).thenReturn(1L);

        assertThat(store.rotateIfHashMatches(USER_ID, REFRESH_TOKEN_HASH, replacement)).isTrue();
    }

    @Test
    @DisplayName("기존 refresh hash가 다르면 Rotation하지 않는다")
    void doesNotRotateWhenRefreshHashDiffers() {
        RefreshSession replacement = session("hash-2", EXPIRES_AT);
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(KEY)),
                eq("other-hash"),
                any(String.class),
                any(String.class)
        )).thenReturn(0L);

        assertThat(store.rotateIfHashMatches(USER_ID, "other-hash", replacement)).isFalse();
    }

    private RefreshSession session(String refreshTokenHash, Instant expiresAt) {
        return new RefreshSession(USER_ID, SESSION_ID, refreshTokenHash, expiresAt);
    }

    private String storedJson(RefreshSession session) throws Exception {
        ObjectNode value = objectMapper.createObjectNode()
                .put("userId", session.userId())
                .put("sessionId", session.sessionId())
                .put("refreshTokenHash", session.refreshTokenHash())
                .put("expiresAt", session.expiresAt().toEpochMilli());
        return objectMapper.writeValueAsString(value);
    }
}
