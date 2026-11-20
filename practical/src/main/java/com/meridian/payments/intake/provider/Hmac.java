package com.meridian.payments.intake.provider;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 over {@code <prefix>.<raw body>}, and constant-time comparison. */
public final class Hmac {

    private static final String ALGORITHM = "HmacSHA256";

    private Hmac() {
    }

    /** HMAC-SHA256 of the ASCII {@code prefix}, a '.', and the raw body bytes (not re-encoded). */
    public static byte[] sha256(byte[] secret, String prefix, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(prefix.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(rawBody);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time comparison: the time taken does not reveal how many leading bytes match. */
    public static boolean matches(byte[] expected, byte[] provided) {
        return provided != null && MessageDigest.isEqual(expected, provided);
    }
}
