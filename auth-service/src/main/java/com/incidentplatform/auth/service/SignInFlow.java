package com.incidentplatform.auth.service;

/**
 * Which way into a session was refused for a suspended tenant (backlog #0-82,
 * step 2): the {@code flow} of the {@code USER_SIGN_IN_REFUSED_TENANT_SUSPENDED}
 * audit event and of the {@value SignInRefusals#COUNTER} counter.
 */
public enum SignInFlow {
    LOGIN,
    REFRESH,
    /** Finishing a login with a TOTP or backup code. */
    MFA_VERIFY,
    /** Finishing a login by enabling the MFA the tenant requires. */
    MFA_SETUP_REQUIRED,
    ACCEPT_INVITE,
    PASSWORD_RESET
}
