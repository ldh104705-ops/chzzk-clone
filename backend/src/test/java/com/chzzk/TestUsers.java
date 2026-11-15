package com.chzzk;

import com.chzzk.auth.AuthService;
import com.chzzk.auth.User;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Test helper: creates a fresh account through the real signup/login code and hands back its bearer token. */
@Component
public class TestUsers {

    public static final String PASSWORD = "correct-horse-1";

    public record Account(long id, String username, String token) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    private final AuthService auth;

    public TestUsers(AuthService auth) {
        this.auth = auth;
    }

    public Account register(String namePrefix) {
        String username = namePrefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = auth.signup(username, PASSWORD, namePrefix);
        return new Account(user.getId(), username, auth.login(username, PASSWORD).token());
    }
}
