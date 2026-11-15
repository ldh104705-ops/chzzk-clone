package com.chzzk.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the live-signaling endpoint. Allowed origins come from the same setting as the REST CORS config. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    public static final String SIGNALING_PATH = "/ws/live/*";

    private final LiveSignalingHandler handler;
    private final String[] allowedOrigins;

    public WebSocketConfig(LiveSignalingHandler handler, @Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, SIGNALING_PATH).setAllowedOrigins(allowedOrigins);
    }
}
