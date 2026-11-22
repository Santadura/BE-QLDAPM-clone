package com.example.demo.social;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class SocialCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private SocialCrypto() {}
    public static String random() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static String hash(String value) { return HexFormat.of().formatHex(sha256(value)); }
    public static String challenge(String verifier) { return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier)); }
    public static boolean matches(String verifier, String challenge) {
        return verifier != null && verifier.matches("[A-Za-z0-9._~-]{43,128}") && MessageDigest.isEqual(
                challenge(verifier).getBytes(StandardCharsets.US_ASCII), challenge.getBytes(StandardCharsets.US_ASCII));
    }
}
