package com.chzzk.auth;

import com.chzzk.common.ApiExceptionHandler.ErrorBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless bearer-token security. Coarse rules live here (who must be logged in); "is this YOUR broadcast /
 * comment" is decided in the services, which answer 403. Public: listing/watching/comment reading, signup, login,
 * the signaling WebSocket handshake (the socket authenticates broadcasters itself in its first message).
 */
@Configuration
public class SecurityConfig {

    private static final String BASE = "/api/broadcasts";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            writeError(mapper, response, HttpStatus.UNAUTHORIZED, "로그인이 필요하거나 인증 토큰이 올바르지 않습니다.");
        };
        AccessDeniedHandler forbidden = (request, response, ex) ->
                writeError(mapper, response, HttpStatus.FORBIDDEN, "이 작업을 수행할 권한이 없습니다.");

        RequestMatcher publicRead = publicReadMatcher();
        BearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();

        http
                .csrf(AbstractHttpConfigurer::disable) // no cookies/sessions, so there is nothing for CSRF to ride on
                .cors(Customizer.withDefaults())       // uses the CorsConfigurationSource bean (CorsConfig)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
                        .requestMatchers(publicRead).permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        // Public reads ignore the Authorization header, so a stale/expired token in the browser
                        // can never break browsing the list or watching a broadcast.
                        .bearerTokenResolver(request -> publicRead.matches(request) ? null : defaultResolver.resolve(request))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
        return http.build();
    }

    /** GET requests anyone may make: broadcast list/detail/video, comment list, ICE servers. */
    private static RequestMatcher publicReadMatcher() {
        return new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, BASE),
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, BASE + "/*"),
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, BASE + "/*/video"),
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, BASE + "/*/comments"),
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/live/ice-servers"));
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(
                new ErrorBody(status.value(), status.getReasonPhrase(), message)));
    }
}
