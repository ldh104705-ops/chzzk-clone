package com.chzzk.live;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the browser which STUN/TURN servers to use for WebRTC, so the frontend never hardcodes them.
 * Configured through APP_WEBRTC_* environment variables (see root .env.example).
 * Note: a static TURN credential is visible to every client; step 3 can replace it with short-lived ones.
 */
@RestController
@RequestMapping("/api/live")
public class IceServersController {

    /** One entry of the browser's RTCConfiguration.iceServers (credentials only for TURN). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IceServer(List<String> urls, String username, String credential) {
    }

    public record IceServersResponse(List<IceServer> iceServers) {
    }

    private final List<String> stunUrls;
    private final List<String> turnUrls;
    private final String turnUsername;
    private final String turnCredential;

    public IceServersController(
            @Value("${app.webrtc.stun-urls:}") String stunUrls,
            @Value("${app.webrtc.turn-urls:}") String turnUrls,
            @Value("${app.webrtc.turn-username:}") String turnUsername,
            @Value("${app.webrtc.turn-credential:}") String turnCredential) {
        this.stunUrls = split(stunUrls);
        this.turnUrls = split(turnUrls);
        this.turnUsername = turnUsername;
        this.turnCredential = turnCredential;
    }

    @GetMapping("/ice-servers")
    public IceServersResponse iceServers() {
        List<IceServer> servers = new ArrayList<>();
        if (!stunUrls.isEmpty()) {
            servers.add(new IceServer(stunUrls, null, null));
        }
        if (!turnUrls.isEmpty()) {
            servers.add(new IceServer(turnUrls, turnUsername, turnCredential));
        }
        return new IceServersResponse(servers);
    }

    private static List<String> split(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
