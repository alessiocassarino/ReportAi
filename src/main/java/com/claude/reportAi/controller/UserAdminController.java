package com.claude.reportAi.controller;

import com.claude.reportAi.dto.CreateUserRequest;
import com.claude.reportAi.dto.ResetPasswordRequest;
import com.claude.reportAi.dto.RoleResponse;
import com.claude.reportAi.dto.UpdateUserRequest;
import com.claude.reportAi.dto.UserDetailResponse;
import com.claude.reportAi.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - User Management", description = "CRUD endpoints for managing user accounts (ADMIN only)")
@Slf4j
public class UserAdminController {

    @Autowired
    private UserAdminService userAdminService;

    /**
     * Returns a paginated, filterable list of all users.
     *
     * @param page      0-indexed page number (default 0)
     * @param size      page size, capped at 50 (default 10)
     * @param search    optional free-text search on email, first/last name
     * @param role      optional role filter, e.g. "ROLE_ADMIN" or "ADMIN"
     * @param status    optional status filter: "active" | "inactive"
     * @param sortBy    field to sort by (default: createdAt)
     * @param sortDir   ASC | DESC (default: DESC)
     */
    @GetMapping
    @Operation(summary = "List all users", description = "Paginated list of users with optional filters")
    public ResponseEntity<Page<UserDetailResponse>> getUsers(
            @RequestParam(defaultValue = "0")          int    page,
            @RequestParam(defaultValue = "10")         int    size,
            @RequestParam(required = false)            String search,
            @RequestParam(required = false)            String role,
            @RequestParam(required = false)            String status,
            @RequestParam(defaultValue = "createdAt")  String sortBy,
            @RequestParam(defaultValue = "DESC")       String sortDir
    ) {
        Boolean enabled  = parseStatusFilter(status);
        int     safeSize = Math.min(size, 50);
        Sort    sort     = "ASC".equalsIgnoreCase(sortDir)
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, safeSize, sort);

        return ResponseEntity.ok(userAdminService.getUsers(pageable, search, role, enabled));
    }

    /**
     * Returns the full detail of a single user.
     * NOTE: must be declared before /{id} to prevent Spring from treating
     * "roles" as a UUID path variable.
     */
    @GetMapping("/roles")
    @Operation(summary = "List all available roles", description = "Returns the roles that can be assigned to users")
    public ResponseEntity<List<RoleResponse>> getRoles() {
        return ResponseEntity.ok(userAdminService.getAllRoles());
    }

    /**
     * Returns the full detail of a single user by ID.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID")
    public ResponseEntity<UserDetailResponse> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(userAdminService.getUserById(id));
    }

    /**
     * Creates a new user account with the specified profile and roles.
     */
    @PostMapping
    @Operation(summary = "Create user", description = "Creates a new user account and assigns the requested roles")
    public ResponseEntity<UserDetailResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        log.info("Admin request to create user: {}", request.getEmail());
        UserDetailResponse created = userAdminService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Updates an existing user's profile data and role assignments.
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update user", description = "Updates profile data and roles for an existing user")
    public ResponseEntity<UserDetailResponse> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        return ResponseEntity.ok(userAdminService.updateUser(id, request));
    }

    /**
     * Toggles the enabled/disabled status of a user account.
     */
    @PutMapping("/{id}/toggle-status")
    @Operation(summary = "Toggle account status", description = "Enables or disables a user account")
    public ResponseEntity<UserDetailResponse> toggleStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(userAdminService.toggleUserStatus(id));
    }

    /**
     * Resets the password for a user account.
     */
    @PutMapping("/{id}/reset-password")
    @Operation(summary = "Reset password", description = "Overwrites the user's password with the provided one")
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        userAdminService.resetPassword(id, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Soft-deletes a user by disabling and locking the account.
     * The record is never hard-deleted to preserve referential integrity.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete user", description = "Soft-deletes a user by disabling and locking the account")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userAdminService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private Boolean parseStatusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        return switch (status.toLowerCase()) {
            case "active"   -> true;
            case "inactive" -> false;
            default         -> null;
        };
    }
}
