package com.chzzk.auth;

import com.chzzk.common.ConflictException;
import com.chzzk.common.InvalidRequestException;
import com.chzzk.common.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,30}$");
    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_BYTES = 72; // BCrypt silently ignores bytes past 72, so reject instead
    static final int MAX_NICKNAME_LENGTH = 20;

    public record LoginResult(String token, User user) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    /** Compared against when the username does not exist, so "unknown user" and "wrong password" take equally long. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    public User signup(String username, String password, String nickname) {
        String id = validateUsername(username);
        validatePassword(password);
        String nick = validateNickname(nickname);
        if (users.existsByUsername(id)) {
            throw new ConflictException("이미 사용 중인 아이디입니다.");
        }
        try {
            return users.saveAndFlush(new User(id, passwordEncoder.encode(password), nick));
        } catch (DataIntegrityViolationException e) { // two simultaneous signups: the unique index decides
            throw new ConflictException("이미 사용 중인 아이디입니다.");
        }
    }

    public LoginResult login(String username, String password) {
        String id = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        User user = users.findByUsername(id).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = password != null && passwordEncoder.matches(password, hash);
        if (user == null || !matches) {
            throw new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        return new LoginResult(tokens.issue(user), user);
    }

    public User get(long userId) {
        return users.findById(userId).orElseThrow(() -> new UnauthorizedException("존재하지 않는 사용자입니다."));
    }

    static String validateUsername(String username) {
        String id = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(id).matches()) {
            throw new InvalidRequestException("아이디는 영문 소문자·숫자·밑줄(_) 3~30자여야 합니다.");
        }
        return id;
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidRequestException("비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRequestException("비밀번호가 너무 깁니다(최대 " + MAX_PASSWORD_BYTES + "바이트).");
        }
    }

    static String validateNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new InvalidRequestException("닉네임은 필수입니다.");
        }
        String nick = nickname.trim();
        if (nick.codePointCount(0, nick.length()) > MAX_NICKNAME_LENGTH) {
            throw new InvalidRequestException("닉네임은 " + MAX_NICKNAME_LENGTH + "자 이하여야 합니다.");
        }
        return nick;
    }
}
