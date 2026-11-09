package vn.iig.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Byte-for-byte compatible JWT claims with legacy core JwtUtil. */
@Component
public class IdentityJwtService {
    private final String secret;
    private final long expirationMs;

    public IdentityJwtService(@Value("${jwt.secret}") String secret,
                              @Value("${jwt.expiration-ms}") long expirationMs) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (expirationMs <= 0) {
            throw new IllegalArgumentException("JWT_EXPIRATION_MS must be positive");
        }
        this.secret = secret;
        this.expirationMs = expirationMs;
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(String username, String role, String passwordHash) {
        return Jwts.builder()
            .subject(username)
            .id(UUID.randomUUID().toString())
            .claim("role", role)
            .claim("credentialStamp", stamp(passwordHash))
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(signingKey(), SignatureAlgorithm.HS256)
            .compact();
    }

    public String tokenId(String raw) {
        Claims claims = Jwts.parser().verifyWith(signingKey()).build()
            .parseSignedClaims(raw).getPayload();
        return claims.getId();
    }

    public String stamp(String passwordHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] result = mac.doFinal(
                ("qldapm:credentials:v1:" + passwordHash).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("JWT stamp generation failed", ex);
        }
    }
}
