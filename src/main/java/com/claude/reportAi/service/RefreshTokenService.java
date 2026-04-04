package com.claude.reportAi.service;

import com.claude.reportAi.entities.RefreshToken;
import com.claude.reportAi.entities.User;
import com.claude.reportAi.repository.RefreshTokenRepository;
import com.claude.reportAi.utils.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Slf4j
public class RefreshTokenService {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Value("${app.jwt.refresh-expiration}")
    private int refreshTokenExpirationMs;

    public RefreshToken createRefreshToken(User user, HttpServletRequest request) {
        // Revoca il vecchio refresh token se esiste
        refreshTokenRepository.findByUserAndRevokedFalse(user)
                .ifPresent(oldToken -> {
                    oldToken.setRevoked(true);
                    refreshTokenRepository.save(oldToken);
                });

        String token = jwtTokenProvider.generateRefreshToken(user.getEmail());
        LocalDateTime expiryDate = LocalDateTime.now().plusNanos((long) refreshTokenExpirationMs * 1_000_000);

        RefreshToken refreshToken = new RefreshToken(
                user,
                token,
                expiryDate,
                getClientIp(request),
                request.getHeader("User-Agent")
        );

        return refreshTokenRepository.save(refreshToken);
    }

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    @Transactional
    public void revokeToken(RefreshToken refreshToken) {
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);
        log.info("Refresh token revoked for user: {}", refreshToken.getUser().getEmail());
    }

    @Transactional
    public void revokeAllUserTokens(User user) {
        refreshTokenRepository.findByUserAndRevokedFalse(user)
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
        log.info("All refresh tokens revoked for user: {}", user.getEmail());
    }

    public boolean isTokenValid(RefreshToken token) {
        if (token.getRevoked() || token.isExpired()) {
            return false;
        }
        return true;
    }

    public void validateTokenExpiry(RefreshToken token) {
        if (token.isExpired()) {
            throw new IllegalArgumentException("Refresh token is expired");
        }
        if (token.getRevoked()) {
            throw new IllegalArgumentException("Refresh token has been revoked");
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0];
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}

