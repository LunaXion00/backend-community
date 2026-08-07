package com.example.community.auth.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class RefreshSessionStore {
    private static final String KEY_PREFIX = "refresh:session:user:";
    private static final RedisScript<Long> DELETE_IF_SESSION_MATCHES = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return 0 end
            local session = cjson.decode(value)
            if session.sessionId ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);
    private static final RedisScript<Long> ROTATE_IF_HASH_MATCHES = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return 0 end
            local session = cjson.decode(value)
            if session.refreshTokenHash ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);
    private static final RedisScript<String> REPLACE_SESSION = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            local previousSessionId = ''
            if value then
                local session = cjson.decode(value)
                previousSessionId = session.sessionId
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return previousSessionId
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration sessionTtl;

    public RefreshSessionStore(StringRedisTemplate redisTemplate, @Value("${jwt.refresh-token-expiration-ms}") long sessionTtlMs) {
        if (sessionTtlMs <= 0) {
            throw new IllegalArgumentException("Refresh session TTL must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.sessionTtl = Duration.ofMillis(sessionTtlMs);
    }

    public void save(RefreshSession session) {
        redisTemplate.opsForValue().set(
                key(session.userId()),
                write(session),
                Duration.ofMillis(ttlMillis(session))
        );
    }

    public Optional<RefreshSession> findByUserId(long userId) {
        String value = redisTemplate.opsForValue().get(key(userId));
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(read(value));
    }

    public Optional<String> replace(RefreshSession session) {
        String previousSessionId = redisTemplate.execute(
                REPLACE_SESSION,
                List.of(key(session.userId())),
                write(session),
                String.valueOf(ttlMillis(session))
        );
        return Optional.ofNullable(previousSessionId)
                .filter(value -> !value.isBlank());
    }

    public boolean deleteIfSessionMatches(long userId, String sessionId) {
        Long result = redisTemplate.execute(
                DELETE_IF_SESSION_MATCHES,
                List.of(key(userId)),
                sessionId
        );
        return Long.valueOf(1L).equals(result);
    }

    public boolean deleteByUserId(long userId){
        return Boolean.TRUE.equals(redisTemplate.delete(key(userId)));
    }

    public boolean rotateIfHashMatches(long userId, String currentHash, RefreshSession replacement) {
        Long result = redisTemplate.execute(
                ROTATE_IF_HASH_MATCHES,
                List.of(key(userId)),
                currentHash,
                write(replacement),
                String.valueOf(ttlMillis(replacement))
        );
        return Long.valueOf(1L).equals(result);
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }

    private long ttlMillis(RefreshSession session) {
        long remainingMillis = session.expiresAt().toEpochMilli() - Instant.now().toEpochMilli();
        if (remainingMillis <= 0) {
            throw new IllegalArgumentException("Refresh session is expired");
        }
        return Math.min(sessionTtl.toMillis(), remainingMillis);
    }

    private String write(RefreshSession session) {
        try {
            ObjectNode value = objectMapper.createObjectNode()
                    .put("userId", session.userId())
                    .put("sessionId", session.sessionId())
                    .put("refreshTokenHash", session.refreshTokenHash())
                    .put("expiresAt", session.expiresAt().toEpochMilli());
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize refresh session", exception);
        }
    }

    private RefreshSession read(String value) {
        try {
            JsonNode json = objectMapper.readTree(value);
            return new RefreshSession(
                    json.required("userId").asLong(),
                    json.required("sessionId").asText(),
                    json.required("refreshTokenHash").asText(),
                    java.time.Instant.ofEpochMilli(json.required("expiresAt").asLong())
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize refresh session", exception);
        }
    }
}
