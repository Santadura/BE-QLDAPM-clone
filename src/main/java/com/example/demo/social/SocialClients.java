package com.example.demo.social;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import java.util.*;

// An empty configuration is valid: password login keeps working before OAuth setup.
public class SocialClients implements ClientRegistrationRepository, Iterable<ClientRegistration> {
    private final Map<String, ClientRegistration> clients = new LinkedHashMap<>();

    public SocialClients(SocialProperties config) {
        String frontend = config.frontend();
        if (!config.getGoogleClientId().isBlank() && !config.getGoogleClientSecret().isBlank()) {
            clients.put("google", ClientRegistration.withRegistrationId("google")
                    .clientName("Google").clientId(config.getGoogleClientId()).clientSecret(config.getGoogleClientSecret())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(frontend + "/api/oauth2/callback/google").scope("openid", "profile", "email")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://oauth2.googleapis.com/token")
                    .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo").userNameAttributeName("sub")
                    .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs").issuerUri("https://accounts.google.com").build());
        }
        if (!config.getFacebookClientId().isBlank() && !config.getFacebookClientSecret().isBlank()) {
            String version = config.getFacebookApiVersion();
            if (!version.matches("v[0-9]+\\.0")) throw new IllegalStateException("Invalid Facebook Graph API version.");
            clients.put("facebook", ClientRegistration.withRegistrationId("facebook")
                    .clientName("Facebook").clientId(config.getFacebookClientId()).clientSecret(config.getFacebookClientSecret())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(frontend + "/api/oauth2/callback/facebook").scope("public_profile", "email")
                    .authorizationUri("https://www.facebook.com/" + version + "/dialog/oauth")
                    .tokenUri("https://graph.facebook.com/" + version + "/oauth/access_token")
                    .userInfoUri("https://graph.facebook.com/" + version + "/me?fields=id,name,email")
                    .userNameAttributeName("id").build());
        }
    }
    public boolean enabled(String provider) { return clients.containsKey(provider); }
    @Override public ClientRegistration findByRegistrationId(String id) { return clients.get(id); }
    @Override public Iterator<ClientRegistration> iterator() { return clients.values().iterator(); }
}
