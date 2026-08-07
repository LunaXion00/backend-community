package com.example.community.global.security.jwt;

import com.example.community.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;


@Component
public class JwtTokenProvider {
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";

    private final SecretKey key;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;
    public JwtTokenProvider(@Value("${jwt.secret}") String secretKey,
                            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
                            @Value("${jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs){

        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        key = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    public JwtToken createJwtToken(User user, String sessionId){
        validateSessionId(sessionId);
        String accessToken = createToken(user, accessTokenExpirationMs, sessionId, ACCESS_TOKEN_TYPE);
        String refreshToken = createToken(user, refreshTokenExpirationMs, sessionId, REFRESH_TOKEN_TYPE);

        return JwtToken.builder()
                .grantType("Bearer")
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }
    public Long getUserId(String token){
        return Long.valueOf(parseClaims(token).getSubject());
    }
    public String getRole(String token){
        return parseClaims(token).get("role", String.class);
    }
    public String getSessionId(String token){
        return parseClaims(token).get("sessionId", String.class);
    }
    public String getTokenType(String token){
        return parseClaims(token).get("tokenType", String.class);
    }
    public boolean validateAccessToken(String token){
        return validateTokenType(token, ACCESS_TOKEN_TYPE);
    }

    public boolean validateRefreshToken(String token){
        return validateTokenType(token, REFRESH_TOKEN_TYPE);
    }

    private boolean validateTokenType(String token, String expectedType) {
        try{
            return expectedType.equals(getTokenType(token));
        } catch(Exception e){
            return false;
        }
    }

    public Authentication getAuthentication(String token){
        long userId = getUserId(token);
        String role = getRole(token);

        return new UsernamePasswordAuthenticationToken(
                String.valueOf(userId),
                null,
                List.of(new SimpleGrantedAuthority(role))
        );
    }

    private String createToken(User user, long expirationMs, String sessionId, String tokenType) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim("role", user.getRole().name())
                .claim("sessionId", sessionId)
                .claim("tokenType", tokenType)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }
    private Claims parseClaims(String token){
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    public long getRemainingValidityMillis(String token){
        try {
            long remainingMillis =
                    parseClaims(token).getExpiration().getTime()
                            - System.currentTimeMillis();

            return Math.max(remainingMillis, 0L);
        } catch (ExpiredJwtException exception) {
            return 0L;
        }
    }
}
