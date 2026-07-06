package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.ChangePasswordRequest;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles self-service password change for authenticated users.
 *
 * <h2>Security design</h2>
 * Current password verification is mandatory — even if the user has a valid
 * JWT, they must prove knowledge of the current password before changing it.
 * This protects against session hijacking: an attacker with a stolen token
 * cannot change the password without knowing the current one.
 *
 * <h2>Error messages</h2>
 * Wrong current password returns 401 "Invalid credentials" — the same message
 * used by the login endpoint. This prevents an attacker from using this
 * endpoint to verify whether a guessed password is correct.
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public PasswordService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Transactional
    public void changePassword(UserPrincipal principal,
                               ChangePasswordRequest request) {
        final String tenantId = TenantContext.get();

        final User user = userRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(principal.userId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", principal.userId()));

        // Verify current password before allowing the change.
        // Guards against session hijacking — attacker with stolen token
        // cannot change the password without knowing the current one.
        if (!passwordEncoder.matches(
                request.currentPassword(), user.getPasswordHash())) {
            log.warn("Password change failed — wrong current password: " +
                    "userId={}, tenant={}", principal.userId(), tenantId);
            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    "Invalid credentials",
                    HttpStatus.UNAUTHORIZED);
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        log.info("Password changed: userId={}, tenant={}",
                principal.userId(), tenantId);
    }
}