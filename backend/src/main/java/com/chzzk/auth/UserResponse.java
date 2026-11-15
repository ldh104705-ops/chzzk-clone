package com.chzzk.auth;

/** Public view of a user: never contains the password hash. */
public record UserResponse(Long id, String username, String nickname) {

    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getNickname());
    }
}
