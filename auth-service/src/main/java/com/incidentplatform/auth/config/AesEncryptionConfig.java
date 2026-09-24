package com.incidentplatform.auth.config;

import com.incidentplatform.auth.service.AesEncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Produces the two independently-keyed {@link AesEncryptionService}
 * instances this service needs.
 *
 * <h2>Why two beans, not one shared key (backlog #0-21)</h2>
 * MFA secrets and Slack bot tokens are both secrets we must be able to
 * decrypt (unlike a password/API-key hash), but they have no reason to
 * share a blast radius: a compromise of one key must not expose the other
 * category of secret. Each bean is keyed by its own environment variable
 * ({@code MFA_ENCRYPTION_KEY}, {@code SLACK_ENCRYPTION_KEY}) and injected
 * where needed via {@code @Qualifier}.
 */
@Configuration
public class AesEncryptionConfig {

    @Bean("mfaEncryptionService")
    public AesEncryptionService mfaEncryptionService(
            @Value("${mfa.encryption-key}") String base64Key) {
        return new AesEncryptionService(base64Key);
    }

    @Bean("slackEncryptionService")
    public AesEncryptionService slackEncryptionService(
            @Value("${slack.encryption-key}") String base64Key) {
        return new AesEncryptionService(base64Key);
    }
}
