package com.example.community.auth.session;

import java.time.Instant;

public record RefreshSession(
        long userId,
        String sessionId,
        String refreshTokenHash,
        Instant expiresAt
) {
}
