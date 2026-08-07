package com.example.community.realtime.connection;

import lombok.Getter;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Getter
public class RealtimeConnection {
    private final String connectionId;
    private final long userId;
    private final String sessionId;
    private final SseEmitter emitter;

    private RealtimeInterestType interestType;
    private Long postId;
    private long interestRevision;

    public RealtimeConnection(String connectionId, long userId, String sessionId, SseEmitter sseEmitter){
        this.connectionId = connectionId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.emitter = sseEmitter;
        interestType = RealtimeInterestType.NONE;
        postId = null;
        interestRevision = 0;
    }

    public synchronized boolean updateInterestIfNewer(RealtimeInterestType interestType, Long postId, long interestRevision){
        if(interestRevision <=  this.interestRevision) return false;
        this.interestType = interestType;
        this.postId = postId;
        this.interestRevision = interestRevision;
        return true;
    }

    public synchronized RealtimeInterestType getInterestType() {
        return interestType;
    }

    public synchronized Long getPostId() {
        return postId;
    }

    public synchronized long getInterestRevision() {
        return interestRevision;
    }
}
