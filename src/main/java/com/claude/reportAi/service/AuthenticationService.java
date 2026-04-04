package com.claude.reportAi.service;

import com.claude.reportAi.dto.LoginRequest;
import com.claude.reportAi.dto.LoginResponse;
import com.claude.reportAi.dto.UserRegisterRequest;
import com.claude.reportAi.entities.RefreshToken;
import com.claude.reportAi.entities.Role;
import com.claude.reportAi.entities.User;
import com.claude.reportAi.repository.RefreshTokenRepository;
import com.claude.reportAi.repository.RoleRepository;
import com.claude.reportAi.repository.UserRepository;
import com.claude.reportAi.utils.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AuthenticationService {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public LoginResponse authenticate(LoginRequest loginRequest, HttpServletRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getEmail(),
                            loginRequest.getPassword()
                    )
            );

            User user = userRepository.findByEmail(loginRequest.getEmail())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Aggiorna il timestamp dell'ultimo login
            user.setLastLogin(LocalDateTime.now());
            userRepository.save(user);

            String accessToken = jwtTokenProvider.generateAccessToken(authentication);
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(user, request);

            return buildLoginResponse(accessToken, refreshToken.getToken(), user, authentication);

        } catch (AuthenticationException e) {
            log.warn("Authentication failed for user: {}", loginRequest.getEmail());
            throw new BadCredentialsException("Invalid email or password", e);
        }
    }

    @Transactional
    public LoginResponse registerUser(UserRegisterRequest registerRequest, HttpServletRequest request) {
        if (!registerRequest.passwordsMatch()) {
            throw new IllegalArgumentException("Passwords do not match");
        }

        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }

        User newUser = new User();
        newUser.setEmail(registerRequest.getEmail());
        newUser.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        newUser.setFirstName(registerRequest.getFirstName());
        newUser.setLastName(registerRequest.getLastName());
        newUser.setEnabled(true);
        newUser.setLastLogin(LocalDateTime.now());

        // Assegna il ruolo USER di default
        Role userRole = roleRepository.findByName(Role.RoleName.USER)
                .orElseThrow(() -> new RuntimeException("Default USER role not found"));
        newUser.addRole(userRole);

        User savedUser = userRepository.save(newUser);
        log.info("New user registered: {}", savedUser.getEmail());

        RefreshToken refreshToken = refreshTokenService.createRefreshToken(savedUser, request);
        String accessToken = jwtTokenProvider.generateAccessToken(savedUser.getEmail());

        return buildLoginResponse(accessToken, refreshToken.getToken(), savedUser, null);
    }

    public LoginResponse refreshAccessToken(String refreshTokenValue, HttpServletRequest request) {
        RefreshToken refreshToken = refreshTokenService.findByToken(refreshTokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        refreshTokenService.validateTokenExpiry(refreshToken);

        User user = refreshToken.getUser();
        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getEmail());

        return LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshTokenValue)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getExpirationTime() / 1000)
                .user(buildUserInfo(user, null))
                .build();
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        RefreshToken refreshToken = refreshTokenService.findByToken(refreshTokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        refreshTokenService.revokeToken(refreshToken);
        log.info("User logged out successfully: {}", refreshToken.getUser().getEmail());
    }

    @Transactional
    public void logoutAllDevices(User user) {
        refreshTokenService.revokeAllUserTokens(user);
        log.info("User logged out from all devices: {}", user.getEmail());
    }

    private LoginResponse buildLoginResponse(String accessToken, String refreshToken, User user, Authentication auth) {
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getExpirationTime() / 1000)
                .user(buildUserInfo(user, auth))
                .build();
    }

    private LoginResponse.UserInfo buildUserInfo(User user, Authentication auth) {
        Set<String> roles = auth != null
                ? auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet())
                : user.getRoles().stream()
                .map(r -> r.getName().toString())
                .collect(Collectors.toSet());

        return new LoginResponse.UserInfo(
                user.getId().toString(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                roles.stream().toList()
        );
    }
}

