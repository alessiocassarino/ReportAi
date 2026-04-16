package com.claude.reportAi.service;

import com.claude.reportAi.dto.CreateUserRequest;
import com.claude.reportAi.dto.ResetPasswordRequest;
import com.claude.reportAi.dto.RoleResponse;
import com.claude.reportAi.dto.UpdateUserRequest;
import com.claude.reportAi.dto.UserDetailResponse;
import com.claude.reportAi.entities.Role;
import com.claude.reportAi.entities.User;
import com.claude.reportAi.repository.RoleRepository;
import com.claude.reportAi.repository.UserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserAdminService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ─────────────────────────────────────────────────────────────────
    //  Query
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns a paginated, filtered list of all users.
     *
     * @param pageable pagination and sorting parameters
     * @param search   optional free-text search (email, firstName, lastName)
     * @param roleName optional role filter (e.g. "ROLE_ADMIN")
     * @param enabled  optional status filter (true=active, false=disabled)
     */
    public Page<UserDetailResponse> getUsers(Pageable pageable, String search, String roleName, Boolean enabled) {
        Specification<User> spec = buildUserSpecification(search, roleName, enabled);
        return userRepository.findAll(spec, pageable).map(this::toDetailResponse);
    }

    /**
     * Returns the details of a single user by ID.
     */
    public UserDetailResponse getUserById(UUID id) {
        User user = findUserOrThrow(id);
        return toDetailResponse(user);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Commands
    // ─────────────────────────────────────────────────────────────────

    /**
     * Creates a new user account with the given profile and roles.
     * Throws {@link IllegalArgumentException} if the email is already taken.
     */
    @Transactional
    public UserDetailResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already in use: " + request.getEmail());
        }

        Set<Role> roles = resolveRoles(request.getRoleNames());

        User user = new User();
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEnabled(true);
        user.setAccountLocked(false);
        user.setAccountExpired(false);
        user.setCredentialsExpired(false);
        user.getRoles().addAll(roles);

        User saved = userRepository.save(user);
        log.info("Admin created user: {}", saved.getEmail());
        return toDetailResponse(saved);
    }

    /**
     * Updates an existing user's profile data and role assignments.
     */
    @Transactional
    public UserDetailResponse updateUser(UUID id, UpdateUserRequest request) {
        User user = findUserOrThrow(id);

        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());

        if (request.getEnabled() != null) {
            user.setEnabled(request.getEnabled());
        }

        Set<Role> newRoles = resolveRoles(request.getRoleNames());
        user.getRoles().clear();
        user.getRoles().addAll(newRoles);

        User saved = userRepository.save(user);
        log.info("Admin updated user: {}", saved.getEmail());
        return toDetailResponse(saved);
    }

    /**
     * Toggles the enabled/disabled state of a user account.
     * When re-enabling, also clears accountLocked so that accounts previously
     * soft-deleted (which set both enabled=false and accountLocked=true) are
     * fully restored to an active state.
     */
    @Transactional
    public UserDetailResponse toggleUserStatus(UUID id) {
        User user = findUserOrThrow(id);
        boolean newStatus = !user.getEnabled();
        user.setEnabled(newStatus);

        if (newStatus) {
            // Re-enabling: lift the lock set by soft-delete
            user.setAccountLocked(false);
        }

        User saved = userRepository.save(user);
        log.info("Admin toggled status for user: {} → enabled={}, locked={}", saved.getEmail(), saved.getEnabled(), saved.getAccountLocked());
        return toDetailResponse(saved);
    }

    /**
     * Resets the password for the given user account.
     */
    @Transactional
    public void resetPassword(UUID id, ResetPasswordRequest request) {
        User user = findUserOrThrow(id);
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setCredentialsExpired(false);
        userRepository.save(user);
        log.info("Admin reset password for user: {}", user.getEmail());
    }

    /**
     * Soft-deletes a user by disabling and locking the account.
     * Hard deletion is intentionally avoided to preserve data integrity
     * with existing jobs, contracts, and documents linked to the user.
     */
    @Transactional
    public void deleteUser(UUID id) {
        User user = findUserOrThrow(id);
        user.setEnabled(false);
        user.setAccountLocked(true);
        userRepository.save(user);
        log.info("Admin soft-deleted user: {}", user.getEmail());
    }

    // ─────────────────────────────────────────────────────────────────
    //  Roles
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns all available roles that can be assigned to users.
     */
    public List<RoleResponse> getAllRoles() {
        return roleRepository.findAll().stream()
                .map(r -> RoleResponse.builder()
                        .id(r.getId().toString())
                        .name(r.getName().toString())
                        .description(r.getDescription())
                        .build())
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found with id: " + id));
    }

    private Set<Role> resolveRoles(Set<String> roleNames) {
        return roleNames.stream()
                .map(name -> {
                    String normalized = name.startsWith("ROLE_") ? name : "ROLE_" + name;
                    Role.RoleName roleName = Role.RoleName.valueOf(normalized);
                    return roleRepository.findByName(roleName)
                            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + name));
                })
                .collect(Collectors.toSet());
    }

    private UserDetailResponse toDetailResponse(User user) {
        List<UserDetailResponse.RoleInfo> roleInfos = user.getRoles().stream()
                .map(r -> UserDetailResponse.RoleInfo.builder()
                        .id(r.getId().toString())
                        .name(r.getName().toString())
                        .description(r.getDescription())
                        .build())
                .toList();

        return UserDetailResponse.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .enabled(user.getEnabled())
                .accountLocked(user.getAccountLocked())
                .roles(roleInfos)
                .createdAt(user.getCreatedAt())
                .lastLogin(user.getLastLogin())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    /**
     * Builds a JPA Specification for dynamic user filtering.
     * All parameters are optional: null values are simply ignored.
     *
     * <ul>
     *   <li>{@code search}   — case-insensitive LIKE on email, firstName, lastName</li>
     *   <li>{@code roleName} — exact match on role.name via INNER JOIN</li>
     *   <li>{@code enabled}  — exact match on user.enabled flag</li>
     * </ul>
     */
    private Specification<User> buildUserSpecification(String search, String roleName, Boolean enabled) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Full-text search across email, firstName, lastName
            if (StringUtils.hasText(search)) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")),     pattern),
                        cb.like(cb.lower(root.get("firstName")), pattern),
                        cb.like(cb.lower(root.get("lastName")),  pattern)
                ));
            }

            // Role filter: join on the roles collection
            if (StringUtils.hasText(roleName)) {
                String normalized = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
                Role.RoleName role = Role.RoleName.valueOf(normalized);
                Join<User, Role> roleJoin = root.join("roles", JoinType.INNER);
                predicates.add(cb.equal(roleJoin.get("name"), role));
                // Prevent duplicate rows from the collection join
                query.distinct(true);
            }

            // Status filter
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
