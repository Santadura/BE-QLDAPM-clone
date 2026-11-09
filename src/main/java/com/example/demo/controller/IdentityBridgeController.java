package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transitional Identity boundary. Exposes only one account at a time to the
 * trusted authentication service; never route /internal/** via Gateway.
 * Core remains profile/role source until the identity write model migrates.
 */
@RestController
@RequestMapping("/internal/identity")
@ConditionalOnProperty(name="iig.identity.bridge-enabled",havingValue="true")
public class IdentityBridgeController {
    public record CredentialSnapshot(String username, String email, String passwordHash,
                                     String role, String status) {}
    private final UserRepository users;
    private final byte[] sharedKey;

    public IdentityBridgeController(UserRepository users,
                @Value("${iig.identity.internal-key}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_INTERNAL_API_KEY must be configured");
        }
        this.users=users;
        this.sharedKey=key.getBytes(StandardCharsets.UTF_8);
    }

    private void requireServiceKey(String supplied) {
        byte[] key = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(sharedKey, key)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid identity service key");
        }
    }

    @GetMapping("/users/{username}")
    public CredentialSnapshot user(@RequestHeader(value="X-Internal-Key", required=false) String key,
                                   @PathVariable String username) {
        requireServiceKey(key);
        User u = users.findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        return new CredentialSnapshot(u.getUsername(), u.getEmail(), u.getPasswordHash(),
            u.getRoleId(), u.getStatus());
    }
}
