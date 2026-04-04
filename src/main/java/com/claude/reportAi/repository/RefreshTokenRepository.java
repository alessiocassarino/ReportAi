package com.claude.reportAi.repository;

import com.claude.reportAi.entities.RefreshToken;
import com.claude.reportAi.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByToken(String token);
    Optional<RefreshToken> findByUserAndRevokedFalse(User user);
    void deleteByUser(User user);
    void deleteByUserAndRevokedTrue(User user);
}

