package com.incidentplatform.shared.pause;

import java.time.Instant;
import java.util.Collection;

/**
 * The background work of a service that holds back a suspended tenant's work
 * (backlog #0-82, step 2b): one bean per service that sets
 * {@code tenant-pause.table}. {@link PausedTenantsSync} asks it which tenants
 * have work waiting, so it looks up only those, and tells it when a tenant's
 * pause ends.
 */
public interface PausableWork {

    /**
     * The tenants with work waiting for this service's schedulers (pending
     * rows), whether paused or not: a query over the work tables, distinct
     * tenant ids. A tenant without waiting work needs no pause; its next row
     * makes it a candidate within one sync.
     */
    Collection<String> tenantsWithPendingWork();

    /**
     * The paused table this service's scheduler queries name in their {@code NOT
     * EXISTS} (a literal in each native query). Checked at startup against
     * {@code tenant-pause.table}, the table the sync writes (found in review: a
     * property changed alone would have the sync pause tenants in one table and
     * the schedulers read another, so nobody was paused).
     */
    String pausedTable();

    /**
     * Called in the transaction that ends the tenant's pause, before its row is
     * deleted, with the row locked: whatever must not have run on while the
     * work was held, such as a timer, is moved on by the pause's length here.
     * A failure rolls the resumption back; it is tried again on the next sync.
     *
     * @param pausedAt when the tenant was suspended, as auth-service recorded it
     *                 ({@code suspended_at}), or when this service saw the
     *                 suspension if auth-service did not say; the end of the
     *                 pause is now, when the sync saw the resumption, at most a
     *                 sync interval and the status cache's TTL late, which only
     *                 ever makes a stopped timer longer
     */
    default void onResume(String tenantId, Instant pausedAt) {
    }
}
