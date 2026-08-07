package com.example.community.realtime.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeConnectionRegistryTest {

    @Test
    @DisplayName("같은 사용자의 여러 연결을 각각 등록하고 조회한다")
    void registersAndFindsMultipleConnectionsForSameUser() {
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry();

        RealtimeConnection first = registry.register(1L, "session-1", new SseEmitter());
        RealtimeConnection second = registry.register(1L, "session-1", new SseEmitter());

        assertThat(first.getConnectionId()).isNotBlank();
        assertThat(second.getConnectionId()).isNotEqualTo(first.getConnectionId());
        assertThat(registry.findById(first.getConnectionId())).contains(first);
        assertThat(registry.findById(second.getConnectionId())).contains(second);
        assertThat(registry.findAll()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("연결 등록 시 sessionId를 저장한다")
    void registersConnectionWithSessionId() {
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry();
        RealtimeConnection connection = registry.register(1L, "session-1", new SseEmitter());

        assertThat(connection.getSessionId()).isEqualTo("session-1");
    }

    @Test
    @DisplayName("sessionId가 일치하는 연결만 조회한다")
    void findsConnectionsBySessionId() {
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry();
        RealtimeConnection oldSession = registry.register(1L, "session-old", new SseEmitter());
        RealtimeConnection anotherOldSession = registry.register(1L, "session-old", new SseEmitter());
        registry.register(1L, "session-current", new SseEmitter());

        assertThat(registry.findBySessionId("session-old"))
                .containsExactlyInAnyOrder(oldSession, anotherOldSession);
    }

    @Test
    @DisplayName("같은 emitter일 때만 연결을 제거한다")
    void removesConnectionOnlyWhenEmitterMatches() {
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry();
        SseEmitter emitter = new SseEmitter();
        RealtimeConnection connection = registry.register(1L, "session-1", emitter);

        registry.remove(connection.getConnectionId(), new SseEmitter());
        assertThat(registry.findById(connection.getConnectionId())).contains(connection);

        registry.remove(connection.getConnectionId(), emitter);
        assertThat(registry.findById(connection.getConnectionId())).isEmpty();
    }
}
