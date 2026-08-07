package com.example.community.auth.controller;

import com.example.community.auth.dto.LoginRequestDTO;
import com.example.community.auth.dto.LoginResponseDTO;
import com.example.community.auth.service.AuthService;
import com.example.community.global.ApiResponse;
import com.example.community.global.exceptions.UnauthorizedException;
import com.example.community.global.security.jwt.JwtToken;
import com.example.community.global.security.jwt.JwtTokenProvider;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final String refreshCookieName;
    private final String refreshCookiePath;
    private final boolean refreshCookieSecure;
    private final String refreshCookieSameSite;
    private final long refreshTokenExpirationMs;

    public AuthController(AuthService authService,
                           JwtTokenProvider jwtTokenProvider,
                           @Value("${jwt.refresh-cookie-name}") String refreshCookieName,
                           @Value("${jwt.refresh-cookie-path}") String refreshCookiePath,
                           @Value("${jwt.refresh-cookie-secure}") boolean refreshCookieSecure,
                           @Value("${jwt.refresh-cookie-same-site}") String refreshCookieSameSite,
                           @Value("${jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs){
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshCookieName = refreshCookieName;
        this.refreshCookiePath = refreshCookiePath;
        this.refreshCookieSecure = refreshCookieSecure;
        this.refreshCookieSameSite = refreshCookieSameSite;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponseDTO>> login(@Valid @RequestBody LoginRequestDTO requestDTO) {
        LoginResponseDTO responseDTO = authService.login(requestDTO);
        ResponseCookie refreshCookie = ResponseCookie.from(refreshCookieName, responseDTO.getToken().getRefreshToken())
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite(refreshCookieSameSite)
                .path(refreshCookiePath)
                .maxAge(Duration.ofMillis(refreshTokenExpirationMs))
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(new ApiResponse<>("user_login_success", responseDTO));
    }
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<JwtToken>> refresh(@CookieValue(name = "${jwt.refresh-cookie-name}", required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                    .body(new ApiResponse<>("refresh_token_missing", null));
        }

        try {
            JwtToken jwtToken = authService.refresh(refreshToken);
            ResponseCookie refreshCookie = ResponseCookie.from(refreshCookieName, jwtToken.getRefreshToken())
                    .httpOnly(true)
                    .secure(refreshCookieSecure)
                    .sameSite(refreshCookieSameSite)
                    .path(refreshCookiePath)
                    .maxAge(Duration.ofMillis(refreshTokenExpirationMs))
                    .build();

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                    .body(new ApiResponse<>("token_refresh_success", jwtToken));
        } catch (UnauthorizedException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                    .body(new ApiResponse<>("refresh_token_invalid", null));
        }
    }
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(Authentication authentication, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authentication == null) {
            throw new UnauthorizedException();
        }

        long loginUserId = Long.parseLong(authentication.getName());
        String accessToken = resolveAccessToken(authorization);
        String sessionId;
        try {
            sessionId = jwtTokenProvider.getSessionId(accessToken);
        } catch (RuntimeException exception) {
            throw new UnauthorizedException();
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new UnauthorizedException();
        }

        authService.logout(loginUserId, sessionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(new ApiResponse<>("logout_success", null));
    }

    private String resolveAccessToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() == 7) {
            throw new UnauthorizedException();
        }
        return authorization.substring(7);
    }

    private ResponseCookie expiredRefreshCookie() {
        return ResponseCookie.from(refreshCookieName, "")
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite(refreshCookieSameSite)
                .path(refreshCookiePath)
                .maxAge(Duration.ZERO)
                .build();
    }
}
