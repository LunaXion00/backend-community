package com.example.community.auth.service;

import com.example.community.auth.dto.LoginRequestDTO;
import com.example.community.auth.dto.LoginResponseDTO;
import com.example.community.global.security.jwt.JwtToken;
import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.auth.session.RefreshSession;
import com.example.community.auth.session.RefreshSessionStore;
import com.example.community.auth.session.RefreshTokenHasher;
import com.example.community.global.exceptions.NotRegisteredException;
import com.example.community.global.exceptions.PasswordInvalidException;
import com.example.community.global.exceptions.UnauthorizedException;
import com.example.community.realtime.service.RealtimeStreamService;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserCredential;
import com.example.community.user.repository.UserCredentialRepository;
import com.example.community.user.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private final UserCredentialRepository userCredentialRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshSessionStore refreshSessionStore;
    private final RefreshTokenHasher refreshTokenHasher;
    private final PasswordEncoder passwordEncoder;
    private final RealtimeStreamService realtimeStreamService;

    public AuthService(UserCredentialRepository userCredentialRepository,
                       UserRepository userRepository,
                       JwtTokenProvider jwtTokenProvider,
                       RefreshSessionStore refreshSessionStore,
                       RefreshTokenHasher refreshTokenHasher,
                       PasswordEncoder passwordEncoder,
                       RealtimeStreamService realtimeStreamService) {
        this.userCredentialRepository = userCredentialRepository;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshSessionStore = refreshSessionStore;
        this.refreshTokenHasher = refreshTokenHasher;
        this.passwordEncoder = passwordEncoder;
        this.realtimeStreamService = realtimeStreamService;
    }

    public LoginResponseDTO login(@Valid LoginRequestDTO requestDTO) {
        String email = requestDTO.getEmail();
        String password = requestDTO.getPassword();

        UserCredential credential = userCredentialRepository.findByEmail(email).orElseThrow(NotRegisteredException::new);
        User user = credential.getUser();

        if (!user.isActive()) throw new NotRegisteredException();
        if (!passwordEncoder.matches(password, credential.getPassword())) throw new PasswordInvalidException();

        String sessionId = UUID.randomUUID().toString();

        JwtToken token = jwtTokenProvider.createJwtToken(user, sessionId);
        long refreshValidityMillis = jwtTokenProvider.getRemainingValidityMillis(token.getRefreshToken());
        Optional<String> previousSessionId = refreshSessionStore.replace(new RefreshSession(
                user.getUserId(),
                sessionId,
                refreshTokenHasher.hash(token.getRefreshToken()),
                Instant.now().plusMillis(refreshValidityMillis)
        ));
        previousSessionId.ifPresent(realtimeStreamService::sendSessionReplaced);

        return new LoginResponseDTO(user.getUserId(), token, user.getNickname(), user.getProfileImageUrl());
    }
    
    public void logout(long userId, String sessionId){
        if (refreshSessionStore.deleteIfSessionMatches(userId, sessionId)) {
            realtimeStreamService.closeSessionConnections(sessionId);
        }
    }

    public JwtToken refresh(String refreshToken){
        if (refreshToken == null || refreshToken.isBlank() || !jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw new UnauthorizedException();
        }

        Long userId;
        String sessionId;

        try {
            userId = jwtTokenProvider.getUserId(refreshToken);
            sessionId = jwtTokenProvider.getSessionId(refreshToken);
        } catch (RuntimeException exception) {
            throw new UnauthorizedException();
        }

        if (sessionId == null || sessionId.isBlank()) throw new UnauthorizedException();

        String currentHash = refreshTokenHasher.hash(refreshToken);
        RefreshSession current = refreshSessionStore.findByUserId(userId)
                .filter(session -> session.userId() == userId
                        && session.sessionId().equals(sessionId)
                        && session.expiresAt().isAfter(Instant.now()))
                .orElseThrow(UnauthorizedException::new);

        if(!sameHash(current.refreshTokenHash(), currentHash)){
            refreshSessionStore.deleteIfSessionMatches(userId, sessionId);
            throw new UnauthorizedException();
        }

        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(UnauthorizedException::new);

        JwtToken rotatedToken = jwtTokenProvider.createJwtToken(user, current.sessionId());
        long remainingValidityMillis = jwtTokenProvider.getRemainingValidityMillis(rotatedToken.getRefreshToken());
        if (remainingValidityMillis <= 0) throw new UnauthorizedException();

        Instant now = Instant.now();
        Instant replacementExpiry = now.plusMillis(remainingValidityMillis);
        if (replacementExpiry.isAfter(current.expiresAt())) {
            replacementExpiry = current.expiresAt();
        }
        RefreshSession replacement = new RefreshSession(
                userId,
                current.sessionId(),
                refreshTokenHasher.hash(rotatedToken.getRefreshToken()),
                replacementExpiry
        );

        if (!refreshSessionStore.rotateIfHashMatches(userId, currentHash, replacement)) {
            refreshSessionStore.deleteIfSessionMatches(userId, sessionId);
            throw new UnauthorizedException();
        }

        return rotatedToken;
    }

    private boolean sameHash(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }
}
