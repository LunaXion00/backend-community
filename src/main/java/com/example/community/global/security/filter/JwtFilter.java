package com.example.community.global.security.filter;

import com.example.community.auth.session.RefreshSessionStore;
import com.example.community.global.security.jwt.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshSessionStore refreshSessionStore;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null) {
            if (jwtTokenProvider.validateAccessToken(token)) {
                try {
                    long userId = jwtTokenProvider.getUserId(token);
                    String sessionId = jwtTokenProvider.getSessionId(token);
                    if (!refreshSessionStore.isCurrentSession(userId, sessionId)) {
                        writeUnauthorizedResponse(response, "access_token_invalid");
                        return;
                    }
                } catch (RedisConnectionFailureException | RedisSystemException exception) {
                    writeServiceUnavailableResponse(response);
                    return;
                }

                Authentication authentication = jwtTokenProvider.getAuthentication(token);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                writeUnauthorizedResponse(response, getTokenErrorMessage(token));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getTokenErrorMessage(String token) {
        try {
            return jwtTokenProvider.getRemainingValidityMillis(token) == 0L
                    ? "access_token_expired"
                    : "access_token_invalid";
        } catch (Exception exception) {
            return "access_token_invalid";
        }
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        writeResponse(response, HttpServletResponse.SC_UNAUTHORIZED, message);
    }

    private void writeServiceUnavailableResponse(HttpServletResponse response) throws IOException {
        writeResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "session_unavailable");
    }

    private void writeResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\",\"data\":null}");
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        return "OPTIONS".equals(method) || "POST".equals(method) && (
                "/api/auth/login".equals(uri) || "/api/auth/refresh".equals(uri) || "/api/users/signup".equals(uri)
        ) || uri.startsWith("/h2-console/");
    }
}
