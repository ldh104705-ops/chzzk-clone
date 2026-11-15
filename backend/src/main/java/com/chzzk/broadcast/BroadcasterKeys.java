package com.chzzk.broadcast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The broadcaster key is a random 256-bit secret handed out once when a live broadcast is created.
 * It is NOT user authentication (that is step 3); it only stops a stranger from hijacking someone's
 * stream. Only its SHA-256 hash is stored: the key is high-entropy, so a fast hash is sufficient.
 */
public final class BroadcasterKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private BroadcasterKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison of the presented key against the stored hash. */
    public static boolean matches(String presentedKey, String storedHash) {
        if (presentedKey == null || presentedKey.isBlank() || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(presentedKey).getBytes(StandardCharsets.UTF_8), storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
