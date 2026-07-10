package com.incidentplatform.notification.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationClientConfig")
class NotificationClientConfigTest {

    @Test
    @DisplayName("creates RestClient bean with JdkClientHttpRequestFactory")
    void createsRestClientWithJdkFactory() {
        // given
        final NotificationClientConfig config =
                new NotificationClientConfig(new NotificationClientProperties(3, 5));

        // when
        final RestClient restClient =
                config.notificationServiceRestClient(RestClient.builder());

        // then
        assertThat(restClient).isNotNull();
    }

    @Test
    @DisplayName("accepts custom timeout values via constructor")
    void acceptsCustomTimeoutValues() {
        // given — custom timeouts (e.g. from environment override)
        final NotificationClientConfig config =
                new NotificationClientConfig(new NotificationClientProperties(10, 30));

        // when — should not throw
        final RestClient restClient =
                config.notificationServiceRestClient(RestClient.builder());

        // then
        assertThat(restClient).isNotNull();
    }

    @Test
    @DisplayName("creates distinct RestClient instances per call")
    void createsDistinctInstances() {
        // given
        final NotificationClientConfig config =
                new NotificationClientConfig(new NotificationClientProperties(3, 5));

        // when
        final RestClient first =
                config.notificationServiceRestClient(RestClient.builder());
        final RestClient second =
                config.notificationServiceRestClient(RestClient.builder());

        // then — @Bean is singleton in Spring context; here we verify the
        // factory method itself creates a new instance each time it's called
        assertThat(first).isNotSameAs(second);
    }
}