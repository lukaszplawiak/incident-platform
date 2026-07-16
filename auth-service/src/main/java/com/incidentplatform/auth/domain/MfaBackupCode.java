package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use backup code for MFA recovery.
 *
 * <p>Plain backup codes are shown to the user exactly once (at MFA enable time)
 * and never stored. Only the Argon2id hash is persisted here — identical
 * approach to password storage.
 *
 * <p>{@link #usedAt} is set atomically when a backup code is redeemed.
 * Once used, the code cannot be reused.
 */
@Entity
@Table(name = "mfa_backup_codes")
public class MfaBackupCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Argon2id hash of the plain backup code. */
    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    /** NULL = not yet used. Set atomically on redemption. */
    @Column(name = "used_at")
    private Instant usedAt;

    protected MfaBackupCode() {}

    public static MfaBackupCode create(User user, String codeHash) {
        final MfaBackupCode code = new MfaBackupCode();
        code.user     = user;
        code.codeHash = codeHash;
        return code;
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public UUID getId()         { return id; }
    public User getUser()       { return user; }
    public String getCodeHash() { return codeHash; }
    public Instant getUsedAt()  { return usedAt; }
}