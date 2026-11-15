package com.chzzk.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/** Extracts the logged-in user's id from a verified JWT (the subject claim). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
