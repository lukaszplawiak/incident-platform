package com.incidentplatform.incident.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketProperties properties;
    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    public WebSocketConfig(WebSocketProperties properties,
                           StompAuthChannelInterceptor stompAuthChannelInterceptor) {
        this.properties = properties;
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(
                        properties.allowedOrigins().toArray(String[]::new));
    }

    /**
     * Fixed: this method was never overridden, so no {@code ChannelInterceptor}
     * of any kind ran for STOMP frames — see {@link StompAuthChannelInterceptor}'s
     * own Javadoc for the full account of the authentication gap this left,
     * and of why authentication has to happen here (at the STOMP frame level)
     * rather than at the HTTP handshake ({@code registerStompEndpoints} above),
     * since a browser's native WebSocket handshake cannot carry a custom
     * {@code Authorization} header.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}