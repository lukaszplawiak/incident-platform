package com.incidentplatform.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskDecorator;

// when @Async:
// @Bean
// public Executor taskExecutor() {
//     final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
//     executor.setTaskDecorator(new TenantAwareTaskDecorator());
//     executor.initialize();
//     return executor;
// }
public class TenantAwareTaskDecorator implements TaskDecorator {

    private static final Logger log =
            LoggerFactory.getLogger(TenantAwareTaskDecorator.class);

    @Override
    public Runnable decorate(Runnable runnable) {
        final String tenantId = TenantContext.getOrNull();

        return () -> {
            try {
                if (tenantId != null) {
                    TenantContext.set(tenantId);
                    log.debug("TenantContext propagated to async thread: {}",
                            tenantId);
                }
                runnable.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}