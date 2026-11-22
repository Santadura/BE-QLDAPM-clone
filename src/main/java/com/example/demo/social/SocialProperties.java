package com.example.demo.social;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "social")
public class SocialProperties {
    private String frontendUrl = "http://localhost:5173";
    private String googleClientId = "";
    private String googleClientSecret = "";
    private String facebookClientId = "";
    private String facebookClientSecret = "";
    private String facebookApiVersion = "v26.0";

    public String frontend() {
        var uri = java.net.URI.create(frontendUrl);
        boolean localhost = uri.getHost() != null && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || (localhost && "http".equals(uri.getScheme())))
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalStateException("SOCIAL_FRONTEND_URL must be an HTTPS origin (HTTP allowed on localhost).");
        }
        return frontendUrl.replaceAll("/+$", "");
    }
}
