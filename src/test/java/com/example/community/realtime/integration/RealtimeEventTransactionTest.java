package com.example.community.realtime.integration;

import com.example.community.realtime.connection.RealtimeConnection;
import com.example.community.realtime.connection.RealtimeConnectionRegistry;
import com.example.community.realtime.connection.RealtimeInterestType;
import com.example.community.realtime.event.CommentCreatedEvent;
import com.example.community.realtime.event.PostCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class RealtimeEventTransactionTest {

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    RealtimeConnectionRegistry registry;

    SseEmitter listEmitter;
    SseEmitter detailEmitter;

    @BeforeEach
    void setUp() {
        listEmitter = mock(SseEmitter.class);
        detailEmitter = mock(SseEmitter.class);

        RealtimeConnection listConnection = new RealtimeConnection("list", 2L, "session-list", listEmitter);
        listConnection.updateInterestIfNewer(RealtimeInterestType.POST_LIST, null, 1L);
        RealtimeConnection detailConnection = new RealtimeConnection("detail", 3L, "session-detail", detailEmitter);
        detailConnection.updateInterestIfNewer(RealtimeInterestType.POST_DETAIL, 10L, 1L);
        when(registry.findAll()).thenReturn(List.of(listConnection, detailConnection));
    }

    @Test
    @DisplayName("commit된 post/comment event만 SSE로 전달한다")
    void deliversEventsAfterCommit() throws Exception {
        new TransactionTemplate(transactionManager).execute(status -> {
            eventPublisher.publishEvent(postCreatedEvent());
            eventPublisher.publishEvent(commentCreatedEvent());
            verifyNoInteractions(listEmitter, detailEmitter);
            return null;
        });

        verify(listEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(detailEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("rollback된 post/comment event는 SSE로 전달하지 않는다")
    void doesNotDeliverEventsAfterRollback() {
        new TransactionTemplate(transactionManager).execute(status -> {
            eventPublisher.publishEvent(postCreatedEvent());
            eventPublisher.publishEvent(commentCreatedEvent());
            status.setRollbackOnly();
            return null;
        });

        verifyNoInteractions(listEmitter, detailEmitter);
    }

    private PostCreatedEvent postCreatedEvent() {
        return new PostCreatedEvent(
                "post-event-1",
                10L,
                1L
        );
    }

    private CommentCreatedEvent commentCreatedEvent() {
        return new CommentCreatedEvent(
                "comment-event-1",
                10L,
                20L,
                1L
        );
    }
}
