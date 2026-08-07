package com.example.community.realtime.connection;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeConnectionRegistry {
    private final ConcurrentHashMap<String, RealtimeConnection> connections = new ConcurrentHashMap<>();

    public RealtimeConnection register(long userId, String sessionId, SseEmitter sseEmitter){
        String connectionId = UUID.randomUUID().toString();
        RealtimeConnection connection = new RealtimeConnection(connectionId, userId, sessionId, sseEmitter);
        connections.put(connectionId, connection);
        return connection;
    }

    public Optional<RealtimeConnection> findById(String connectionId){
        return Optional.ofNullable(connections.get(connectionId));
    }

    public List<RealtimeConnection> findAll(){
        return List.copyOf(connections.values());
    }

    public List<RealtimeConnection> findBySessionId(String sessionId){
        return connections.values().stream()
                .filter(connection-> Objects.equals(connection.getSessionId(), sessionId)).toList();
    }

    public void remove(String connectionId, SseEmitter sseEmitter){
        connections.computeIfPresent(connectionId, (id, connection) ->
                connection.getEmitter() == sseEmitter ? null : connection);
    }
}
