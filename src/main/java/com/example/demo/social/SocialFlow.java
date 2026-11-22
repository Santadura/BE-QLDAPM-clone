package com.example.demo.social;

import java.io.Serializable;

public record SocialFlow(String key, String provider, String challenge, String linkUsername,
                         String credentialHash, String tokenId, long expiresAt) implements Serializable {
    public static final String SESSION = "gk.social.pending";
    public static final String ATTRIBUTE = "gk.social.verified-flow";
}
