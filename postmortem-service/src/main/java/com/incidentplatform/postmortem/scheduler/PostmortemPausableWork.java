package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.config.PostmortemProperties;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.shared.pause.PausableWork;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * postmortem-service's work for the pause of suspended tenants (backlog #0-82,
 * step 2b): the tenants with a postmortem {@link PostmortemRetryScheduler}
 * would generate. Nothing to do on resumption: a postmortem has no deadline,
 * and none of its retries was spent while it was held, so the default
 * {@link #onResume} is enough. The pause itself is {@code shared}'s
 * {@code PausedTenantsSync}; the scheduler's queries leave a paused tenant's
 * postmortems out.
 */
@Component
public class PostmortemPausableWork implements PausableWork {

    private final PostmortemRepository postmortemRepository;
    private final int maxRetryAttempts;

    public PostmortemPausableWork(PostmortemRepository postmortemRepository, PostmortemProperties properties) {
        this.postmortemRepository = postmortemRepository;
        this.maxRetryAttempts = properties.maxRetryAttempts();
    }

    @Override
    public List<String> tenantsWithPendingWork() {
        return postmortemRepository.findTenantsWithPendingGeneration(maxRetryAttempts);
    }

    @Override
    public String pausedTable() {
        return PostmortemRepository.PAUSED_TABLE;
    }
}
