package com.chzzk.auth;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** signup / login are public; logout and me need a valid bearer token (see SecurityConfig). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SignupRequest(String username, String password, String nickname) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, UserResponse user) {
    }

    private final AuthService auth;
    private final TokenService tokens;

    public AuthController(AuthService auth, TokenService tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@RequestBody SignupRequest request) {
        User created = auth.signup(request.username(), request.password(), request.nickname());
        return ResponseEntity.created(URI.create("/api/auth/me")).body(UserResponse.from(created));
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AuthService.LoginResult result = auth.login(request.username(), request.password());
        return new LoginResponse(result.token(), UserResponse.from(result.user()));
    }

    /** The client discards its token; the server also blacklists this token id until it would have expired. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        tokens.revoke(jwt);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(auth.get(CurrentUser.id(jwt)));
    }
}
