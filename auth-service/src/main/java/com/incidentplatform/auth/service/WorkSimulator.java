package com.incidentplatform.auth.service;

/**
 * Strategy for simulating work in timing-sensitive code paths.
 *
 * <h2>Why this exists — user enumeration timing attack</h2>
 * {@code ForgotPasswordService.initiateReset()} has two execution paths:
 * <ol>
 *   <li><b>User exists</b> — DB lookup + token generation + two DB writes ≈ 10ms</li>
 *   <li><b>User not found</b> — single DB lookup ≈ 2ms</li>
 * </ol>
 * An attacker sending thousands of requests and measuring response times can
 * statistically distinguish the two paths even when both return {@code 202 Accepted}.
 * This interface allows the no-op path to perform equivalent work, equalising timing.
 *
 * <h2>Why a @FunctionalInterface instead of a private method</h2>
 * A private method cannot be replaced in tests — the test suite would incur
 * real sleep delays on every run of the "user not found" path. As a
 * {@code @FunctionalInterface} {@code @Bean}, tests inject {@code () -> {}}
 * (no-op lambda) making them deterministic and instant.
 *
 * <h2>Pattern</h2>
 * Mirrors {@link com.incidentplatform.shared.security.TokenRevocationChecker}:
 * a {@code @FunctionalInterface} whose production implementation is registered
 * as a {@code @Bean} in {@code SchedulerConfig}, and whose test implementation
 * is a no-op lambda passed directly to the constructor.
 */
@FunctionalInterface
public interface WorkSimulator {

    /**
     * Performs simulated work to equalise response timing.
     *
     * <p>Production implementation: {@code Thread.sleep(8 + random(0..3) ms)}.
     * Test implementation: {@code () -> {}} (no-op).
     */
    void simulate();
}