package com.incidentplatform.auth.service;

import io.micrometer.core.instrument.Counter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counts an event once its transaction has committed (backlog #0-90 review,
 * #0-82): a counter behind a critical alert must not count a change that was
 * rolled back. Outside a transaction (unit tests) it counts at once.
 */
final class CommittedCounters {

    private CommittedCounters() {
    }

    static void incrementAfterCommit(Counter counter) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            counter.increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }
}
