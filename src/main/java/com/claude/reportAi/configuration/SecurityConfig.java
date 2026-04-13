package com.claude.reportAi.configuration;

import com.claude.reportAi.utils.JwtAuthenticationFilter;
import com.claude.reportAi.utils.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(
        securedEnabled = true,
        jsr250Enabled = true,
        prePostEnabled = true
)
@Slf4j
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:4200}")
    private String allowedOrigins;

    @Value("${app.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethods;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // Strength 12 per maggiore sicurezza
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CORS Configuration
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // CSRF Protection - Disabilitato per API REST con JWT
                .csrf(csrf -> csrf.disable())

                // Session Management - Stateless per JWT
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Exception Handling
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint())
                        .accessDeniedHandler(new JwtAccessDeniedHandler())
                )

                // Authorization
                .authorizeHttpRequests(authz -> authz
                        // Public endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/health", "/actuator/**").permitAll()

                        // HTTP Methods
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Protected endpoints
                        .requestMatchers("/api/contracts/**").hasAnyRole("ADMIN", "USER", "ANALYST")
                        .requestMatchers("/api/estimates/**").hasAnyRole("ADMIN", "USER", "ANALYST")
                        .requestMatchers("/api/documents/**").hasAnyRole("ADMIN", "USER", "ANALYST")
                        .requestMatchers("/api/price-comparison/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // Default - require authentication
                        .anyRequest().authenticated()
                )

                // Add JWT filter
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Parse allowed origins
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        configuration.setAllowedOrigins(origins);

        // Parse allowed methods
        List<String> methods = Arrays.asList(allowedMethods.split(","));
        configuration.setAllowedMethods(methods);

        configuration.setAllowedHeaders(Arrays.asList(
                "Content-Type",
                "Accept",
                "Authorization",
                "X-Requested-With",
                "X-CSRF-Token",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers"
        ));

        configuration.setExposedHeaders(Arrays.asList(
                "Authorization",
                "X-Total-Count",
                "X-Page-Number",
                "X-Page-Size"
        ));

        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        log.info("CORS configuration enabled for origins: {}", origins);
        return source;
    }
}

