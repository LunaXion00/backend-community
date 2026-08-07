package com.example.community.auth.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RefreshSessionStoreRedisIntegrationTest {

    private static final long USER_ID = 9001L;
    private static final String SESSION_ID = "session-redis";
    private static final String KEY = "refresh:session:user:" + USER_ID;
    private static final long SESSION_TTL_MS = Duration.ofDays(7).toMillis();

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RefreshSessionStore store;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        store = new RefreshSessionStore(redisTemplate, SESSION_TTL_MS);
        redisTemplate.delete(KEY);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("실제 Redis Lua로 refresh hash가 일치할 때만 세션을 교체한다")
    void rotatesOnlyWhenHashMatchesOnRedis() {
        RefreshSession original = session("hash-1");
        RefreshSession replacement = session("hash-2");

        store.save(original);

        assertThat(store.rotateIfHashMatches(USER_ID, "wrong-hash", replacement)).isFalse();
        assertThat(store.findByUserId(USER_ID)).contains(original);

        assertThat(store.rotateIfHashMatches(USER_ID, "hash-1", replacement)).isTrue();
        assertThat(store.findByUserId(USER_ID)).contains(replacement);

        assertThat(store.rotateIfHashMatches(USER_ID, "hash-1", original)).isFalse();
        assertThat(store.findByUserId(USER_ID)).contains(replacement);
    }

    @Test
    @DisplayName("실제 Redis Lua로 sessionId가 일치할 때만 세션을 삭제한다")
    void deletesOnlyWhenSessionIdMatchesOnRedis() {
        RefreshSession session = session("hash-1");
        store.save(session);

        assertThat(store.deleteIfSessionMatches(USER_ID, "other-session")).isFalse();
        assertThat(store.findByUserId(USER_ID)).contains(session);

        assertThat(store.deleteIfSessionMatches(USER_ID, SESSION_ID)).isTrue();
        assertThat(store.findByUserId(USER_ID)).isEmpty();
        assertThat(store.deleteIfSessionMatches(USER_ID, SESSION_ID)).isFalse();
    }

    private RefreshSession session(String hash) {
        return new RefreshSession(
                USER_ID,
                SESSION_ID,
                hash,
                Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS)
        );
    }
}
