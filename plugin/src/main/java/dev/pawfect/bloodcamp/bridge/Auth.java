package dev.pawfect.bloodcamp.bridge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Auth {
    private static final long MAX_SKEW_MS = 30_000;
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] key;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public Auth(String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public static String newSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    public boolean verify(String method, String path, String time, String nonce, String signature, byte[] body) {
        if (time == null || nonce == null || signature == null) return false;
        if (nonce.length() < 16 || nonce.length() > 64) return false;

        long sentAt;
        try {
            sentAt = Long.parseLong(time);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - sentAt) > MAX_SKEW_MS) return false;

        byte[] expected = sign(time, nonce, method, path, body);
        byte[] given;
        try {
            given = HEX.parseHex(signature.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!MessageDigest.isEqual(expected, given)) return false;

        seenNonces.values().removeIf(at -> now - at > MAX_SKEW_MS * 2);
        return seenNonces.putIfAbsent(nonce, now) == null;
    }

    byte[] sign(String time, String nonce, String method, String path, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((time + "\n" + nonce + "\n" + method + "\n" + path + "\n").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
