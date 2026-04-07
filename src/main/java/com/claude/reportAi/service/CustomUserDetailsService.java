package com.claude.reportAi.service;

import com.claude.reportAi.entities.User;
import com.claude.reportAi.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        log.debug("Loading user: {} with roles: {}", email, user.getRoles().stream()
                .map(r -> r.getName().name())
                .toList());

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .accountLocked(user.getAccountLocked())
                .accountExpired(user.getAccountExpired())
                .credentialsExpired(user.getCredentialsExpired())
                .disabled(!user.getEnabled())
                .authorities(buildAuthorities(user))
                .build();
    }

    private java.util.Collection<? extends GrantedAuthority> buildAuthorities(User user) {
        try {
            var authorities = user.getRoles().stream()
                    // Carica i nomi dei ruoli direttamente (già hanno il prefisso ROLE_ nel database)
                    .map(role -> {
                        String roleName = role.getName().name();
                        log.debug("Processing role: {}", roleName);
                        return new SimpleGrantedAuthority(roleName);
                    })
                    .collect(Collectors.toSet());
            
            // Aggiungi una authority di esempio dalla prima authority disponibile (se presente)
            user.getRoles().stream()
                    .flatMap(role -> role.getAuthorities().stream())
                    .limit(1) // Solo la prima authority come esempio
                    .map(authority -> new SimpleGrantedAuthority(authority.getName()))
                    .forEach(authorities::add);
            
            log.debug("Built authorities for user {}: {}", user.getEmail(), authorities.stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList());
            
            return authorities;
        } catch (Exception e) {
            log.error("Error building authorities for user: {}", user.getEmail(), e);
            throw new RuntimeException("Failed to load authorities for user: " + user.getEmail(), e);
        }
    }
}

