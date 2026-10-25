package com.example.demo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms}")
    private long expirationMs;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, String role, String passwordHash) {
        return Jwts.builder()
                .subject(username)
                .id(UUID.randomUUID().toString())
                .claim("role", role)
                .claim("credentialStamp", credentialStamp(passwordHash))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    public String extractTokenId(String token) {
        return extractAllClaims(token).getId();
    }

    public boolean isTokenValid(String token, String username, String currentPasswordHash) {
        // Parsing verifies signature and expiration before reading these claims.
        Claims claims = extractAllClaims(token);
        String tokenId = claims.getId();
        String stamp = claims.get("credentialStamp", String.class);
        if (!username.equals(claims.getSubject()) || tokenId == null || tokenId.isBlank()
                || tokenId.length() > 50 || stamp == null || claims.getExpiration() == null) return false;
        return MessageDigest.isEqual(stamp.getBytes(StandardCharsets.UTF_8),
                credentialStamp(currentPasswordHash).getBytes(StandardCharsets.UTF_8));
    }

    private String credentialStamp(String passwordHash) {
        try {
            // Domain-separated, keyed digest. Neither the password nor its
            // BCrypt hash is included in JWT claims or persisted session logs.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("qldapm:credentials:v1:" + passwordHash).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to create session credential stamp", ex);
        }
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
    }
}
