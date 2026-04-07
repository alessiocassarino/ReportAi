package com.claude.reportAi.configuration;

import com.claude.reportAi.entities.Authority;
import com.claude.reportAi.entities.Role;
import com.claude.reportAi.entities.User;
import com.claude.reportAi.repository.AuthorityRepository;
import com.claude.reportAi.repository.RoleRepository;
import com.claude.reportAi.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Configuration
@Slf4j
public class DataInitializerConfig {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AuthorityRepository authorityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Bean
    public ApplicationRunner initializeDefaultData() {
        return args -> {
            log.info("Initializing default security data...");

            // Create default authorities
            createDefaultAuthorities();

            // Create default roles
            createDefaultRoles();

            // Create default user
            createDefaultUser();

            log.info("Security data initialization completed successfully");
        };
    }

    private void createDefaultAuthorities() {
        String[] authorityNames = {
                "READ_CONTRACTS",
                "CREATE_CONTRACTS",
                "UPDATE_CONTRACTS",
                "DELETE_CONTRACTS",
                "READ_ESTIMATES",
                "CREATE_ESTIMATES",
                "UPDATE_ESTIMATES",
                "DELETE_ESTIMATES",
                "MANAGE_USERS",
                "MANAGE_ROLES"
        };

        String[] descriptions = {
                "Can read contracts",
                "Can create contracts",
                "Can update contracts",
                "Can delete contracts",
                "Can read estimates",
                "Can create estimates",
                "Can update estimates",
                "Can delete estimates",
                "Can manage users",
                "Can manage roles"
        };

        for (int i = 0; i < authorityNames.length; i++) {
            if (!authorityRepository.findByName(authorityNames[i]).isPresent()) {
                Authority authority = new Authority(authorityNames[i], descriptions[i]);
                authorityRepository.save(authority);
                log.info("Created authority: {}", authorityNames[i]);
            }
        }
    }

    private void createDefaultRoles() {
        // Create USER role
        if (roleRepository.findByName(Role.RoleName.ROLE_USER).isEmpty()) {
            Role userRole = new Role(Role.RoleName.ROLE_USER);
            userRole.setDescription("Standard user role");
            
            // Assign one authority to USER role as example
            Set<Authority> userAuthorities = new HashSet<>();
            authorityRepository.findByName("READ_CONTRACTS").ifPresent(userAuthorities::add);
            
            userRole.setAuthorities(userAuthorities);
            roleRepository.save(userRole);
            log.info("Created role: ROLE_USER");
        }

        // Create ANALYST role
        if (roleRepository.findByName(Role.RoleName.ROLE_ANALYST).isEmpty()) {
            Role analystRole = new Role(Role.RoleName.ROLE_ANALYST);
            analystRole.setDescription("Analyst role");
            
            // Assign one authority to ANALYST role as example
            Set<Authority> analystAuthorities = new HashSet<>();
            authorityRepository.findByName("READ_CONTRACTS").ifPresent(analystAuthorities::add);
            
            analystRole.setAuthorities(analystAuthorities);
            roleRepository.save(analystRole);
            log.info("Created role: ROLE_ANALYST");
        }

        // Create ADMIN role
        if (roleRepository.findByName(Role.RoleName.ROLE_ADMIN).isEmpty()) {
            Role adminRole = new Role(Role.RoleName.ROLE_ADMIN);
            adminRole.setDescription("Administrator role");
            
            // Assign one authority to ADMIN role as example
            Set<Authority> adminAuthorities = new HashSet<>();
            authorityRepository.findByName("MANAGE_USERS").ifPresent(adminAuthorities::add);
            
            adminRole.setAuthorities(adminAuthorities);
            roleRepository.save(adminRole);
            log.info("Created role: ROLE_ADMIN");
        }
    }

    private void createDefaultUser() {
        String defaultEmail = "user@user.it";
        String defaultPassword = "password";

        if (!userRepository.existsByEmail(defaultEmail)) {
            User defaultUser = new User();
            defaultUser.setEmail(defaultEmail);
            defaultUser.setPassword(passwordEncoder.encode(defaultPassword));
            defaultUser.setFirstName("Default");
            defaultUser.setLastName("User");
            defaultUser.setEnabled(true);
            defaultUser.setAccountLocked(false);
            defaultUser.setAccountExpired(false);
            defaultUser.setCredentialsExpired(false);
            defaultUser.setCreatedAt(LocalDateTime.now());
            defaultUser.setLastLogin(LocalDateTime.now());

            // Assign USER role
            Role userRole = roleRepository.findByName(Role.RoleName.ROLE_ADMIN)
                    .orElseThrow(() -> new RuntimeException("USER role not found"));
            defaultUser.addRole(userRole);

            userRepository.save(defaultUser);
            log.info("Created default user with email: {} and password: {}", defaultEmail, defaultPassword);
        } else {
            log.info("Default user already exists, skipping creation");
        }
    }
}

