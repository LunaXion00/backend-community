package com.example.community.realtime.service;

import com.example.community.CommunityApplication;
import com.example.community.global.security.AuthValidator;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.global.exceptions.ForbiddenException;
import com.example.community.global.exceptions.InvalidInputException;
import com.example.community.realtime.connection.RealtimeConnection;
import com.example.community.realtime.connection.RealtimeConnectionRegistry;
import com.example.community.realtime.connection.RealtimeInterestType;
import com.example.community.realtime.event.CommentCreatedEvent;
import com.example.community.realtime.event.PostCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeStreamServiceTest {

    private static final String SESSION_ID = "session-1";

    RealtimeConnectionRegistry registry;
    SseEmitter emitter;
    RealtimeConnection connection;
    RealtimeStreamService service;
    AuthValidator authValidator;

    @BeforeEach
    void setUp() {
        registry = mock(RealtimeConnectionRegistry.class);
        emitter = mock(SseEmitter.class);
        authValidator = mock(AuthValidator.class);
        connection = new RealtimeConnection("connection-1", 1L, SESSION_ID, emitter);
        when(registry.register(eq(1L), eq(SESSION_ID), same(emitter))).thenReturn(connection);
        service = new RealtimeStreamService(registry, authValidator);
    }

    @Test
    @DisplayName("연결을 등록하고 connected 이벤트를 전송한다")
    void connectsAndSendsConnectedEvent() throws Exception {
        SseEmitter result = service.connect(1L, SESSION_ID, emitter);

        assertThat(result).isSameAs(emitter);
        verify(registry).register(eq(1L), eq(SESSION_ID), same(emitter));

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(eventCaptor.capture());

        List<Object> eventParts = eventCaptor.getValue().build().stream()
                .map(SseEmitter.DataWithMediaType::getData)
                .toList();
        assertThat(eventParts.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(part -> part.contains("event:connected"))).isTrue();
        assertThat(eventParts).contains(Map.of("connectionId", "connection-1"));
    }

    @Test
    @DisplayName("연결 종료 callback은 registry에서 연결을 제거한다")
    void cleanupCallbacksRemoveRegisteredConnection() throws Exception {
        service.connect(1L, SESSION_ID, emitter);

        ArgumentCaptor<Runnable> completionCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Throwable>> errorCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onCompletion(completionCaptor.capture());
        verify(emitter).onTimeout(timeoutCaptor.capture());
        verify(emitter).onError(errorCaptor.capture());

        completionCaptor.getValue().run();
        timeoutCaptor.getValue().run();
        errorCaptor.getValue().accept(new RuntimeException("connection failed"));

        verify(registry, times(3)).remove("connection-1", emitter);
    }

    @Test
    @DisplayName("connected 이벤트 전송 실패 시 등록된 연결을 제거한다")
    void sendFailureRemovesRegisteredConnection() throws Exception {
        doThrow(new IOException("send failed"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        assertThatThrownBy(() -> service.connect(1L, SESSION_ID, emitter))
                .isInstanceOf(IOException.class)
                .hasMessage("send failed");

        verify(registry).remove("connection-1", emitter);
    }

    @Test
    @DisplayName("heartbeat는 25초마다 공통 scheduler로 실행된다")
    void heartbeatUsesCommonScheduler() throws Exception {
        Scheduled scheduled = RealtimeStreamService.class
                .getDeclaredMethod("sendHeartbeat")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(25_000L);
        assertThat(CommunityApplication.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
    }

    @Test
    @DisplayName("heartbeat 전송 실패 연결들만 제거하고 나머지 연결은 계속 전송한다")
    void heartbeatFailureRemovesOnlyFailedConnection() throws Exception {
        SseEmitter ioFailedEmitter = mock(SseEmitter.class);
        SseEmitter runtimeFailedEmitter = mock(SseEmitter.class);
        SseEmitter activeEmitter = mock(SseEmitter.class);
        RealtimeConnection ioFailedConnection =
                new RealtimeConnection("io-failed", 1L, SESSION_ID, ioFailedEmitter);
        RealtimeConnection runtimeFailedConnection =
                new RealtimeConnection("runtime-failed", 2L, SESSION_ID, runtimeFailedEmitter);
        RealtimeConnection activeConnection =
                new RealtimeConnection("active-connection", 3L, SESSION_ID, activeEmitter);
        when(registry.findAll()).thenReturn(List.of(
                ioFailedConnection,
                runtimeFailedConnection,
                activeConnection
        ));
        doThrow(new IOException("heartbeat failed"))
                .when(ioFailedEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException("emitter completed"))
                .when(runtimeFailedEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        service.sendHeartbeat();

        verify(registry).remove("io-failed", ioFailedEmitter);
        verify(registry).remove("runtime-failed", runtimeFailedEmitter);
        verify(registry, never()).remove("active-connection", activeEmitter);

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(activeEmitter).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().build().stream()
                .map(SseEmitter.DataWithMediaType::getData)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(part -> part.contains(":heartbeat"))).isTrue();
    }

    @Test
    @DisplayName("연결 소유자는 더 큰 revision으로 관심 상태를 변경한다")
    void ownerUpdatesInterestWithNewerRevision() {
        when(registry.findById("connection-1")).thenReturn(Optional.of(connection));

        boolean updated = service.updateInterest(
                1L,
                "connection-1",
                RealtimeInterestType.POST_DETAIL,
                10L,
                1L
        );

        assertThat(updated).isTrue();
        assertThat(connection.getInterestType()).isEqualTo(RealtimeInterestType.POST_DETAIL);
        assertThat(connection.getPostId()).isEqualTo(10L);
        assertThat(connection.getInterestRevision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("같거나 작은 revision은 기존 관심 상태를 변경하지 않는다")
    void staleRevisionDoesNotChangeInterest() {
        connection.updateInterestIfNewer(RealtimeInterestType.POST_DETAIL, 10L, 2L);
        when(registry.findById("connection-1")).thenReturn(Optional.of(connection));

        boolean updated = service.updateInterest(
                1L,
                "connection-1",
                RealtimeInterestType.POST_LIST,
                null,
                2L
        );

        assertThat(updated).isFalse();
        assertThat(connection.getInterestType()).isEqualTo(RealtimeInterestType.POST_DETAIL);
        assertThat(connection.getPostId()).isEqualTo(10L);
        assertThat(connection.getInterestRevision()).isEqualTo(2L);
    }

    @Test
    @DisplayName("POST_DETAIL은 양수 postId가 필요하다")
    void postDetailRequiresPositivePostId() {
        when(registry.findById("connection-1")).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.updateInterest(
                1L,
                "connection-1",
                RealtimeInterestType.POST_DETAIL,
                null,
                1L
        )).isInstanceOf(InvalidInputException.class);

        assertThatThrownBy(() -> service.updateInterest(
                1L,
                "connection-1",
                RealtimeInterestType.POST_DETAIL,
                0L,
                1L
        )).isInstanceOf(InvalidInputException.class);

        assertThat(connection.getInterestType()).isEqualTo(RealtimeInterestType.NONE);
        assertThat(connection.getInterestRevision()).isZero();
    }

    @Test
    @DisplayName("POST_DETAIL이 아닌 관심 상태는 postId를 저장하지 않는다")
    void nonPostDetailInterestClearsPostId() {
        connection.updateInterestIfNewer(RealtimeInterestType.POST_DETAIL, 10L, 1L);
        when(registry.findById("connection-1")).thenReturn(Optional.of(connection));

        boolean updated = service.updateInterest(
                1L,
                "connection-1",
                RealtimeInterestType.POST_LIST,
                99L,
                2L
        );

        assertThat(updated).isTrue();
        assertThat(connection.getInterestType()).isEqualTo(RealtimeInterestType.POST_LIST);
        assertThat(connection.getPostId()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 연결은 404 예외로 처리한다")
    void missingConnectionIsNotFound() {
        when(registry.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateInterest(
                1L,
                "missing",
                RealtimeInterestType.POST_LIST,
                null,
                1L
        )).isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    @DisplayName("다른 사용자의 연결은 변경할 수 없다")
    void cannotUpdateAnotherUsersConnection() {
        when(registry.findById("connection-1")).thenReturn(Optional.of(connection));
        doThrow(new ForbiddenException())
                .when(authValidator)
                .validateOwner(2L, 1L);

        assertThatThrownBy(() -> service.updateInterest(
                2L,
                "connection-1",
                RealtimeInterestType.POST_LIST,
                null,
                1L
        )).isInstanceOf(ForbiddenException.class);

        assertThat(connection.getInterestType()).isEqualTo(RealtimeInterestType.NONE);
        assertThat(connection.getInterestRevision()).isZero();
    }

    @Test
    @DisplayName("post-created는 작성자를 제외한 POST_LIST 관심 연결에만 전송한다")
    void sendsPostCreatedOnlyToPostListConnectionsExceptActor() throws Exception {
        SseEmitter firstListEmitter = mock(SseEmitter.class);
        SseEmitter secondListEmitter = mock(SseEmitter.class);
        SseEmitter detailEmitter = mock(SseEmitter.class);
        SseEmitter actorEmitter = mock(SseEmitter.class);
        RealtimeConnection firstListConnection =
                new RealtimeConnection("list-1", 2L, SESSION_ID, firstListEmitter);
        RealtimeConnection secondListConnection =
                new RealtimeConnection("list-2", 2L, SESSION_ID, secondListEmitter);
        RealtimeConnection detailConnection =
                new RealtimeConnection("detail", 3L, SESSION_ID, detailEmitter);
        RealtimeConnection actorConnection =
                new RealtimeConnection("actor", 1L, SESSION_ID, actorEmitter);
        firstListConnection.updateInterestIfNewer(RealtimeInterestType.POST_LIST, null, 1L);
        secondListConnection.updateInterestIfNewer(RealtimeInterestType.POST_LIST, null, 1L);
        detailConnection.updateInterestIfNewer(RealtimeInterestType.POST_DETAIL, 10L, 1L);
        actorConnection.updateInterestIfNewer(RealtimeInterestType.POST_LIST, null, 1L);
        when(registry.findAll()).thenReturn(List.of(
                firstListConnection,
                secondListConnection,
                detailConnection,
                actorConnection
        ));
        PostCreatedEvent event = new PostCreatedEvent(
                "post-event-1",
                10L,
                1L
        );

        service.sendPostCreated(event);

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(firstListEmitter).send(eventCaptor.capture());
        verify(secondListEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(detailEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(actorEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));

        List<Object> eventParts = eventCaptor.getValue().build().stream()
                .map(SseEmitter.DataWithMediaType::getData)
                .toList();
        assertThat(eventParts.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(part -> part.contains("event:post-created"))).isTrue();
        assertThat(eventParts.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(part -> part.contains("id:post-event-1"))).isTrue();
        assertThat(eventParts).contains(Map.of("postId", 10L));
        assertThat(eventParts).noneMatch(part -> part instanceof Map<?, ?> map
                && (map.containsKey("actorUserId") || map.containsKey("post")));
    }

    @Test
    @DisplayName("post-created 전송은 transaction commit 이후 listener로 실행된다")
    void postCreatedUsesAfterCommitListener() throws Exception {
        TransactionalEventListener listener = RealtimeStreamService.class
                .getDeclaredMethod("sendPostCreated", PostCreatedEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("post-created 전송 중 한 연결이 실패해도 다른 연결에는 계속 전송한다")
    void postCreatedFailureDoesNotBlockOtherConnections() throws Exception {
        SseEmitter failedEmitter = mock(SseEmitter.class);
        SseEmitter activeEmitter = mock(SseEmitter.class);
        RealtimeConnection failedConnection = new RealtimeConnection("failed", 2L, SESSION_ID, failedEmitter);
        RealtimeConnection activeConnection = new RealtimeConnection("active", 3L, SESSION_ID, activeEmitter);
        failedConnection.updateInterestIfNewer(RealtimeInterestType.POST_LIST, null, 1L);
        activeConnection.updateInterestIfNewer(RealtimeInterestType.POST_LIST, null, 1L);
        when(registry.findAll()).thenReturn(List.of(failedConnection, activeConnection));
        doThrow(new IOException("post event failed"))
                .when(failedEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        service.sendPostCreated(new PostCreatedEvent(
                "post-event-2",
                10L,
                1L
        ));

        verify(registry).remove("failed", failedEmitter);
        verify(activeEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("comment-created는 작성자를 제외한 같은 게시글의 POST_DETAIL 연결에만 전송한다")
    void sendsCommentCreatedOnlyToMatchingPostDetailConnectionsExceptActor() throws Exception {
        SseEmitter matchingEmitter = mock(SseEmitter.class);
        SseEmitter otherPostEmitter = mock(SseEmitter.class);
        SseEmitter listEmitter = mock(SseEmitter.class);
        SseEmitter actorEmitter = mock(SseEmitter.class);
        RealtimeConnection matchingConnection =
                new RealtimeConnection("matching", 2L, SESSION_ID, matchingEmitter);
        RealtimeConnection otherPostConnection =
                new RealtimeConnection("other-post", 3L, SESSION_ID, otherPostEmitter);
        RealtimeConnection listConnection =
                new RealtimeConnection("list", 4L, SESSION_ID, listEmitter);
        RealtimeConnection actorConnection =
                new RealtimeConnection("actor", 1L, SESSION_ID, actorEmitter);
        matchingConnection.updateInterestIfNewer(RealtimeInterestType.POST_DETAIL, 10L, 1L);
        otherPostConnection.updateInterestIfNewer(RealtimeInterestType.POST_DETAIL, 11L, 1L);
        listConnection.updateInterestIfNewer(RealtimeInterestType.POST_LIST, null, 1L);
        actorConnection.updateInterestIfNewer(RealtimeInterestType.POST_DETAIL, 10L, 1L);
        when(registry.findAll()).thenReturn(List.of(
                matchingConnection,
                otherPostConnection,
                listConnection,
                actorConnection
        ));
        CommentCreatedEvent event = new CommentCreatedEvent(
                "comment-event-1",
                10L,
                20L,
                1L
        );

        service.sendCommentCreated(event);

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(matchingEmitter).send(eventCaptor.capture());
        verify(otherPostEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(listEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(actorEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));

        List<Object> eventParts = eventCaptor.getValue().build().stream()
                .map(SseEmitter.DataWithMediaType::getData)
                .toList();
        assertThat(eventParts.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(part -> part.contains("event:comment-created"))).isTrue();
        assertThat(eventParts.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(part -> part.contains("id:comment-event-1"))).isTrue();
        assertThat(eventParts).contains(Map.of("postId", 10L, "commentId", 20L));
        assertThat(eventParts).noneMatch(part -> part instanceof Map<?, ?> map
                && (map.containsKey("actorUserId")
                || map.containsKey("commentId") && map.containsKey("parentCommentId")));
    }

    @Test
    @DisplayName("comment-created 전송 중 한 연결이 실패해도 다른 연결에는 계속 전송한다")
    void commentCreatedFailureDoesNotBlockOtherConnections() throws Exception {
        SseEmitter failedEmitter = mock(SseEmitter.class);
        SseEmitter activeEmitter = mock(SseEmitter.class);
        RealtimeConnection failedConnection = new RealtimeConnection("failed", 2L, SESSION_ID, failedEmitter);
        RealtimeConnection activeConnection = new RealtimeConnection("active", 3L, SESSION_ID, activeEmitter);
        failedConnection.updateInterestIfNewer(RealtimeInterestType.POST_DETAIL, 10L, 1L);
        activeConnection.updateInterestIfNewer(RealtimeInterestType.POST_DETAIL, 10L, 1L);
        when(registry.findAll()).thenReturn(List.of(failedConnection, activeConnection));
        doThrow(new IllegalStateException("comment event failed"))
                .when(failedEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        service.sendCommentCreated(new CommentCreatedEvent(
                "comment-event-2",
                10L,
                21L,
                1L
        ));

        verify(registry).remove("failed", failedEmitter);
        verify(activeEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("session-replaced는 이전 session의 모든 연결에 전송하고 연결을 종료한다")
    void sendsSessionReplacedToOldSessionConnectionsAndClosesThem() throws Exception {
        SseEmitter firstOldEmitter = mock(SseEmitter.class);
        SseEmitter secondOldEmitter = mock(SseEmitter.class);
        RealtimeConnection firstOldConnection = new RealtimeConnection(
                "old-1", 1L, "session-old", firstOldEmitter
        );
        RealtimeConnection secondOldConnection = new RealtimeConnection(
                "old-2", 1L, "session-old", secondOldEmitter
        );
        when(registry.findBySessionId("session-old")).thenReturn(List.of(
                firstOldConnection,
                secondOldConnection
        ));

        service.sendSessionReplaced("session-old");

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(firstOldEmitter).send(eventCaptor.capture());
        verify(secondOldEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(firstOldEmitter).complete();
        verify(secondOldEmitter).complete();
        List<Object> eventParts = eventCaptor.getValue().build().stream()
                .map(SseEmitter.DataWithMediaType::getData)
                .toList();
        assertThat(eventParts.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(part -> part.contains("event:session-replaced"))).isTrue();
        assertThat(eventParts).anySatisfy(part -> {
            assertThat(part).isInstanceOf(Map.class);
            Map<?, ?> payload = (Map<?, ?>) part;
            assertThat(payload.containsKey("reason")).isTrue();
            assertThat(payload.containsKey("sessionId")).isFalse();
            assertThat(payload.containsKey("accessToken")).isFalse();
            assertThat(payload.containsKey("refreshToken")).isFalse();
            assertThat(payload.containsKey("userId")).isFalse();
        });
    }

    @Test
    @DisplayName("session-replaced 전송 실패가 다른 연결 전달을 막지 않는다")
    void sessionReplacedFailureDoesNotBlockOtherConnections() throws Exception {
        SseEmitter failedEmitter = mock(SseEmitter.class);
        SseEmitter activeEmitter = mock(SseEmitter.class);
        RealtimeConnection failedConnection = new RealtimeConnection(
                "failed", 1L, "session-old", failedEmitter
        );
        RealtimeConnection activeConnection = new RealtimeConnection(
                "active", 1L, "session-old", activeEmitter
        );
        when(registry.findBySessionId("session-old"))
                .thenReturn(List.of(failedConnection, activeConnection));
        doThrow(new IOException("session replacement failed"))
                .when(failedEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        service.sendSessionReplaced("session-old");

        verify(registry).remove("failed", failedEmitter);
        verify(failedEmitter).complete();
        verify(activeEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(activeEmitter).complete();
    }

    @Test
    @DisplayName("로그아웃 시 해당 session의 모든 SSE 연결을 종료한다")
    void closesConnectionsBySessionId() {
        SseEmitter matchingEmitter = mock(SseEmitter.class);
        SseEmitter otherEmitter = mock(SseEmitter.class);
        RealtimeConnection matchingConnection = new RealtimeConnection(
                "matching", 1L, "session-logout", matchingEmitter
        );
        RealtimeConnection otherConnection = new RealtimeConnection(
                "other", 1L, "session-current", otherEmitter
        );
        when(registry.findBySessionId("session-logout")).thenReturn(List.of(matchingConnection));

        service.closeSessionConnections("session-logout");

        verify(matchingEmitter).complete();
        verify(otherEmitter, never()).complete();
    }

    @Test
    @DisplayName("회원 탈퇴 시 해당 사용자의 모든 SSE 연결을 종료한다")
    void closesConnectionsByUserId() {
        SseEmitter matchingEmitter = mock(SseEmitter.class);
        SseEmitter otherEmitter = mock(SseEmitter.class);
        RealtimeConnection matchingConnection = new RealtimeConnection(
                "matching", 1L, "session-1", matchingEmitter
        );
        RealtimeConnection otherConnection = new RealtimeConnection(
                "other", 2L, "session-2", otherEmitter
        );
        when(registry.findAll()).thenReturn(List.of(matchingConnection, otherConnection));

        service.closeUserConnections(1L);

        verify(matchingEmitter).complete();
        verify(otherEmitter, never()).complete();
    }
}
