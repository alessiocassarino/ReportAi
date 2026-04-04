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
        if (roleRepository.findByName(Role.RoleName.USER).isEmpty()) {
            Role userRole = new Role(Role.RoleName.USER);
            userRole.setDescription("Standard user role");
            
            // Assign authorities to USER role
            Set<Authority> userAuthorities = new HashSet<>();
            userAuthorities.add(authorityRepository.findByName("READ_CONTRACTS").orElse(null));
            userAuthorities.add(authorityRepository.findByName("CREATE_CONTRACTS").orElse(null));
            userAuthorities.add(authorityRepository.findByName("READ_ESTIMATES").orElse(null));
            userAuthorities.add(authorityRepository.findByName("CREATE_ESTIMATES").orElse(null));
            userAuthorities.removeIf(auth -> auth == null);
            
            userRole.setAuthorities(userAuthorities);
            roleRepository.save(userRole);
            log.info("Created role: USER");
        }

        // Create ANALYST role
        if (roleRepository.findByName(Role.RoleName.ANALYST).isEmpty()) {
            Role analystRole = new Role(Role.RoleName.ANALYST);
            analystRole.setDescription("Analyst role");
            
            // Assign authorities to ANALYST role
            Set<Authority> analystAuthorities = new HashSet<>();
            analystAuthorities.add(authorityRepository.findByName("READ_CONTRACTS").orElse(null));
            analystAuthorities.add(authorityRepository.findByName("READ_ESTIMATES").orElse(null));
            analystAuthorities.removeIf(auth -> auth == null);
            
            analystRole.setAuthorities(analystAuthorities);
            roleRepository.save(analystRole);
            log.info("Created role: ANALYST");
        }

        // Create ADMIN role
        if (roleRepository.findByName(Role.RoleName.ADMIN).isEmpty()) {
            Role adminRole = new Role(Role.RoleName.ADMIN);
            adminRole.setDescription("Administrator role");
            
            // Assign all authorities to ADMIN role
            Set<Authority> allAuthorities = new HashSet<>(
                    authorityRepository.findAll()
            );
            adminRole.setAuthorities(allAuthorities);
            roleRepository.save(adminRole);
            log.info("Created role: ADMIN");
        }
    }

    private void createDefaultUser() {
        String defaultEmail = "user";
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
            Role userRole = roleRepository.findByName(Role.RoleName.USER)
                    .orElseThrow(() -> new RuntimeException("USER role not found"));
            defaultUser.addRole(userRole);

            userRepository.save(defaultUser);
            log.info("Created default user with email: {} and password: {}", defaultEmail, defaultPassword);
        } else {
            log.info("Default user already exists, skipping creation");
        }
    }
}

