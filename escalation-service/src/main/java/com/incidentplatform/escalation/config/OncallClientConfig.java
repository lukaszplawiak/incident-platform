package com.incidentplatform.escalation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Connect and read timeouts for the {@code RestClient} that calls
 * oncall-service.
 *
 * <h2>Fixed (backlog #0-11): the client had no timeouts</h2>
 * {@code OncallServiceClient} was built from the bare auto-configured
 * {@code RestClient.Builder}, with no connect or read timeout. While every
 * call was a fast 401 that did not matter; once service tokens work, the
 * calls are real, and {@code EscalationScheduler} makes them one after
 * another for up to {@code scheduler-batch-size} tasks. A hung oncall-service
 * would then block the scheduler thread with no upper bound, the circuit
 * breaker would never see a finished call to count, and after the ShedLock's
 * {@code lockAtMostFor} another replica would start a second run that blocks
 * the same way — escalations stop for every tenant.
 *
 * <p>Same approach as notification-service's {@code NotificationClientConfig},
 * as a {@link RestClientCustomizer} so it applies to the builder the client
 * is created from. Defaults are 2s to connect and 5s to read.
 */
@Configuration
public class OncallClientConfig {

    @Bean
    public RestClientCustomizer oncallClientTimeouts(
            @Value("${oncall.service.connect-timeout:2s}") Duration connectTimeout,
            @Value("${oncall.service.read-timeout:5s}") Duration readTimeout) {
        return builder -> {
            final HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .build();
            final JdkClientHttpRequestFactory factory =
                    new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(readTimeout);
            builder.requestFactory(factory);
        };
    }
}
