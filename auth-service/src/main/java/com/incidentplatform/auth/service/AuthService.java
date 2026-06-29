package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.LoginRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, JwtUtils jwtUtils) {
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        final String tenantId = TenantContext.get();

        // Same error message for "not found", "inactive", and "no local password"
        // (OAuth2-only account) — prevents user enumeration.
        final User user = userRepository
                .findByEmailAndTenantId(request.email(), tenantId)
                .filter(User::isActive)
                .filter(u -> u.getPasswordHash() != null)
                .orElseThrow(() -> {
                    log.warn("Login failed — user not found, inactive, or OAuth2-only: " +
                            "email={}, tenant={}", request.email(), tenantId);
                    return new ResponseStatusException(
                            HttpStatus.UNAUTHORIZED, "Invalid credentials");
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Login failed — wrong password: email={}, tenant={}",
                    request.email(), tenantId);
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        final String token = jwtUtils.generateToken(
                user.getId(),
                tenantId,
                user.getEmail(),
                user.getRoleNames()
        );

        final Instant expiresAt = Instant.now()
                .plusMillis(jwtUtils.getServiceExpirationMs());

        log.info("Login successful: email={}, tenant={}, roles={}",
                user.getEmail(), tenantId, user.getRoleNames());

        return new LoginResponse(
                token,
                user.getId(),
                tenantId,
                user.getEmail(),
                user.getRoleNames(),
                expiresAt
        );
    }
}