package com.example.community.realtime.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

public class RealtimeConnectionTest {
    @Test
    void connection_init_test(){
        SseEmitter emitter = new SseEmitter();

        RealtimeConnection connection = new RealtimeConnection(
                "connection-1", 1L, "session-1", emitter
        );

        assertThat(connection.getConnectionId()).isEqualTo("connection-1");
        assertThat(connection.getUserId()).isEqualTo(1L);
        assertThat(connection.getEmitter()).isSameAs(emitter);
        assertThat(connection.getInterestType()).isEqualTo(RealtimeInterestType.NONE);
        assertThat(connection.getPostId()).isNull();
        assertThat(connection.getInterestRevision()).isZero();
    }

    @Test
    @DisplayName("연결은 로그인 sessionId를 보존한다")
    void connectionStoresSessionId() {
        RealtimeConnection connection = new RealtimeConnection(
                "connection-1",
                1L,
                "session-1",
                new SseEmitter()
        );

        assertThat(connection.getSessionId()).isEqualTo("session-1");
    }

    @Test
    @DisplayName("더 큰 revision만 관심 상태에 반영한다")
    void updatesInterestOnlyWhenRevisionIsNewer() {
        RealtimeConnection connection = new RealtimeConnection(
                "connection-1",
                1L,
                "session-1",
                new SseEmitter()
        );

        assertThat(connection.updateInterestIfNewer(
                RealtimeInterestType.POST_DETAIL, 10L, 2L
        )).isTrue();

        assertThat(connection.updateInterestIfNewer(
                RealtimeInterestType.POST_LIST, null, 2L
        )).isFalse();

        assertThat(connection.getInterestType()).isEqualTo(RealtimeInterestType.POST_DETAIL);
        assertThat(connection.getPostId()).isEqualTo(10L);
        assertThat(connection.getInterestRevision()).isEqualTo(2L);
    }
}
