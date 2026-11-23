package com.chzzk.config;

import java.io.IOException;
import java.net.InetAddress;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Small cluster-related beans: the demo instance header, and a Redis health indicator that is quiet in local mode. */
@Configuration
public class ClusterConfig {

    /**
     * Local mode never touches Redis, but the Redis starter would still add a health indicator that reports DOWN
     * (nothing listens on localhost:6379) and turn /actuator/health red. A bean with this name replaces it; in
     * cluster mode the real indicator stays.
     */
    @Bean(name = "redisHealthIndicator")
    @ConditionalOnProperty(name = "app.cluster.mode", havingValue = "local", matchIfMissing = true)
    HealthIndicator localModeRedisHealth() {
        return () -> Health.unknown().withDetail("redis", "not used in local cluster mode").build();
    }

    /** {@code X-Instance: <pod hostname>} on every response, to show load balancing in demos. Off unless APP_INSTANCE_HEADER=true. */
    @Component
    @ConditionalOnProperty(name = "app.instance-header.enabled", havingValue = "true")
    @Order(Ordered.HIGHEST_PRECEDENCE) // before Spring Security, so 401/403 answers carry the header too
    static class InstanceHeaderFilter extends OncePerRequestFilter {

        private final String instance;

        InstanceHeaderFilter(@Value("${HOSTNAME:}") String hostname) {
            this.instance = hostname.isBlank() ? resolveHostName() : hostname;
        }

        private static String resolveHostName() {
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                return "unknown";
            }
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("X-Instance", instance);
            chain.doFilter(request, response);
        }
    }
}
