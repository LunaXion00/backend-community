package com.example.community.realtime.service;

import com.example.community.global.security.AuthValidator;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.global.exceptions.InvalidInputException;
import com.example.community.realtime.connection.RealtimeConnection;
import com.example.community.realtime.connection.RealtimeConnectionRegistry;
import com.example.community.realtime.connection.RealtimeInterestType;
import com.example.community.realtime.event.CommentCreatedEvent;
import com.example.community.realtime.event.PostCreatedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class RealtimeStreamService {
    private final RealtimeConnectionRegistry registry;
    private final AuthValidator authValidator;

    public RealtimeStreamService(RealtimeConnectionRegistry registry, AuthValidator authValidator){
        this.registry = registry;
        this.authValidator = authValidator;
    }

    public SseEmitter connect(long userId, String sessionId, SseEmitter sseEmitter) throws IOException{
        RealtimeConnection connection = registry.register(userId, sessionId, sseEmitter);
        String connectionId = connection.getConnectionId();
        try {
            sseEmitter.onCompletion(() -> registry.remove(connectionId, sseEmitter));
            sseEmitter.onTimeout(() -> {
                registry.remove(connectionId, sseEmitter);
                sseEmitter.complete();
            });
            sseEmitter.onError(error -> registry.remove(connectionId, sseEmitter));
            sseEmitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("connectionId", connectionId)));
            return sseEmitter;
        } catch(IOException | RuntimeException exception){
            registry.remove(connectionId, sseEmitter);
            throw exception;
        }
    }

    public boolean updateInterest(long userId, String connectionId, RealtimeInterestType interestType, Long postId, long interestRevision){
        RealtimeConnection connection = registry.findById(connectionId).orElseThrow(ContentNotFoundException::new);
        authValidator.validateOwner(userId, connection.getUserId());
        if(interestType == RealtimeInterestType.POST_DETAIL && (postId == null || postId <= 0)) {
            throw new InvalidInputException();
        }

        return connection.updateInterestIfNewer(
                interestType,
                interestType == RealtimeInterestType.POST_DETAIL ? postId : null,
                interestRevision
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendPostCreated(PostCreatedEvent event){
        for(RealtimeConnection connection : registry.findAll()){
            if(connection.getUserId() == event.actorUserId() || connection.getInterestType() != RealtimeInterestType.POST_LIST) continue;
            SseEmitter.SseEventBuilder sseEvent = SseEmitter.event()
                    .id(event.eventId())
                    .name("post-created")
                    .data(Map.of("postId", event.postId()));
            sendEventToClient(connection.getConnectionId(), connection.getEmitter(), sseEvent);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendCommentCreated(CommentCreatedEvent event){
        for(RealtimeConnection connection : registry.findAll()){
            if(connection.getUserId() == event.actorUserId()
                    || connection.getInterestType() != RealtimeInterestType.POST_DETAIL
                    || !event.postId().equals(connection.getPostId())) continue;

            SseEmitter.SseEventBuilder sseEvent = SseEmitter.event()
                    .id(event.eventId())
                    .name("comment-created")
                    .data(Map.of(
                            "postId", event.postId(),
                            "commentId", event.commentId()
                    ));
            sendEventToClient(connection.getConnectionId(), connection.getEmitter(), sseEvent);
        }
    }

    public void sendSessionReplaced(String sessionId){
        for(RealtimeConnection connection : registry.findBySessionId(sessionId)){
            SseEmitter.SseEventBuilder sseEvent = SseEmitter.event()
                    .name("session-replaced")
                    .data(Map.of("reason", "new_login"));
            sendEventToClient(connection.getConnectionId(), connection.getEmitter(), sseEvent);
            closeConnection(connection);
        }
    }

    public void closeSessionConnections(String sessionId) {
        for (RealtimeConnection connection : registry.findBySessionId(sessionId)) {
            closeConnection(connection);
        }
    }

    public void closeUserConnections(long userId) {
        for (RealtimeConnection connection : registry.findAll()) {
            if (connection.getUserId() == userId) {
                closeConnection(connection);
            }
        }
    }

    @Scheduled(fixedDelay=25_000)
    public void sendHeartbeat() {
        List<RealtimeConnection> connections = registry.findAll();
        for(RealtimeConnection connection : connections){
            sendEventToClient(connection.getConnectionId(), connection.getEmitter(), SseEmitter.event().comment("heartbeat"));
        }
    }
    private void sendEventToClient(String connectionId, SseEmitter sseEmitter, SseEmitter.SseEventBuilder event){
        try{
            sseEmitter.send(event);
        } catch(IOException | RuntimeException exception){
            registry.remove(connectionId, sseEmitter);
        }
    }

    private void closeConnection(RealtimeConnection connection) {
        try {
            connection.getEmitter().complete();
        } catch (RuntimeException exception) {
            registry.remove(connection.getConnectionId(), connection.getEmitter());
        }
    }

}
