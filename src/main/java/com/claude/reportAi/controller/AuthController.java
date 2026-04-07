package com.claude.reportAi.controller;

import com.claude.reportAi.dto.*;
import com.claude.reportAi.entities.User;
import com.claude.reportAi.repository.UserRepository;
import com.claude.reportAi.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Authentication endpoints")
@Slf4j
public class AuthController {

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Login endpoint - Restituisce access token e refresh token
     */
    @PostMapping("/login")
    @Operation(summary = "User login", description = "Authenticate user and receive JWT tokens")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request) {
        log.info("Login attempt for user: {}", loginRequest.getEmail());
        LoginResponse response = authenticationService.authenticate(loginRequest, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Registration endpoint - Registrazione nuovo utente
     */
    @PostMapping("/register")
    @Operation(summary = "User registration", description = "Register a new user account")
    public ResponseEntity<LoginResponse> register(
            @Valid @RequestBody UserRegisterRequest registerRequest,
            HttpServletRequest request) {
        log.info("Registration attempt for user: {}", registerRequest.getEmail());
        LoginResponse response = authenticationService.registerUser(registerRequest, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Refresh token endpoint - Genera un nuovo access token
     */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token", description = "Generate a new access token using refresh token")
    public ResponseEntity<LoginResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest httpRequest) {
        log.info("Token refresh requested");
        LoginResponse response = authenticationService.refreshAccessToken(request.getRefreshToken(), httpRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Logout endpoint - Revoca il refresh token
     * Il refresh token può essere passato nel body oppure viene utilizzato quello della sessione
     */
    @PostMapping("/logout")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "User logout", description = "Logout user by revoking refresh token")
    public ResponseEntity<?> logout(
            @RequestBody(required = false) RefreshTokenRequest request) {
        log.info("Logout requested");
        
        // Se il request contiene un refresh token, usa quello
        if (request != null && request.getRefreshToken() != null) {
            authenticationService.logout(request.getRefreshToken());
        } else {
            // Altrimenti prendi l'utente autenticato e revoca tutti i token
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            authenticationService.logoutAllDevices(user);
        }
        
        return ResponseEntity.ok(new ApiResponse("User logged out successfully"));
    }

    /**
     * Logout from all devices endpoint
     */
    @PostMapping("/logout-all-devices")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Logout from all devices", description = "Revoke all refresh tokens for the current user")
    public ResponseEntity<?> logoutAllDevices() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        authenticationService.logoutAllDevices(user);
        log.info("User {} logged out from all devices", email);
        return ResponseEntity.ok(new ApiResponse("Logged out from all devices successfully"));
    }

    /**
     * Get current user profile
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get current user profile", description = "Retrieve the profile of the authenticated user")
    public ResponseEntity<UserDTO> getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        UserDTO userDTO = UserDTO.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .enabled(user.getEnabled())
                .roles(user.getRoles().stream()
                        .map(role -> role.getName().toString())
                        .toList())
                .build();

        return ResponseEntity.ok(userDTO);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Verify if authentication service is running")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(new ApiResponse("Authentication service is running"));
    }

    // Inner class for generic API response
    public static class ApiResponse {
        public String message;

        public ApiResponse(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }
}

