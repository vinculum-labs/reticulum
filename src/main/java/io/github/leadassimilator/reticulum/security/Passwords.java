package io.github.leadassimilator.reticulum.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Salted PBKDF2 hashes, stored as "iterations:salt:hash" in Base64. Passwords are never stored in plain text. */
public final class Passwords {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 65_536;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** The least time between one player's password guesses. */
    private static final long GUESS_INTERVAL_MS = 1000;
    private static final java.util.Map<java.util.UUID, Long> LAST_GUESS = new java.util.concurrent.ConcurrentHashMap<>();

    private Passwords() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return ITERATIONS + ":" + b64.encodeToString(salt) + ":" + b64.encodeToString(hash);
    }

    /**
     * Checks a player's guess, at most once a second per player: a guess sooner than that is refused without being
     * checked.
     */
    public static boolean guess(java.util.UUID player, String password, String stored) {
        long now = System.currentTimeMillis();
        Long last = LAST_GUESS.put(player, now);
        if (last != null && now - last < GUESS_INTERVAL_MS) {
            return false;
        }
        return matches(password, stored);
    }

    public static boolean matches(String password, String stored) {
        String[] parts = stored.split(":");
        if (parts.length != 3) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
