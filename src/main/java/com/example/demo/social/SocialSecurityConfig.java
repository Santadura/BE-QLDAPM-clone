package com.example.demo.social;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(SocialProperties.class)
public class SocialSecurityConfig {
    @Bean public SocialClients socialClients(SocialProperties config) { return new SocialClients(config); }
    @Bean public OAuth2AuthorizedClientService socialAuthorizedClients(SocialClients clients) { return new InMemoryOAuth2AuthorizedClientService(clients); }

    @Bean @Order(1)
    public SecurityFilterChain socialSecurity(HttpSecurity http,SocialClients clients,SocialProperties config,
                                               SocialAccountService accounts,OAuth2AuthorizedClientService authorizedClients) throws Exception {
        String callback=config.frontend()+"/user/social/callback";
        http.securityMatcher("/api/oauth2/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .clientRegistrationRepository(clients).authorizedClientService(authorizedClients)
                        .loginPage(config.frontend()+"/user/login")
                        .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/oauth2/authorization")
                                .authorizationRequestResolver(new SocialFlowResolver(clients))
                                .authorizationRequestRepository(new SocialAuthorizationRequests()))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/oauth2/callback/*"))
                        .successHandler((request,response,authentication) -> {
                            var actor=(OAuth2AuthenticationToken)authentication;
                            String fragment;
                            try {
                                String ticket=accounts.complete(actor.getAuthorizedClientRegistrationId(),actor.getPrincipal(),
                                        (SocialFlow)request.getAttribute(SocialFlow.ATTRIBUTE));
                                fragment="#code="+ticket;
                            } catch (SocialException ex) { fragment="#error="+ex.code(); }
                            catch (Exception ex) { fragment="#error=PROVIDER_ERROR"; }
                            authorizedClients.removeAuthorizedClient(actor.getAuthorizedClientRegistrationId(),actor.getName());
                            clear(request);
                            redirect(response,callback+fragment);
                        })
                        .failureHandler((request,response,exception) -> {
                            String code=exception instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oauthError
                                    && "access_denied".equals(oauthError.getError().getErrorCode())?"CANCELLED":"PROVIDER_ERROR";
                            clear(request);redirect(response,callback+"#error="+code);
                        }));
        return http.build();
    }
    private static void clear(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session=request.getSession(false);
        if (session!=null) session.invalidate();
    }
    private static void redirect(HttpServletResponse response,String location) throws java.io.IOException {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Referrer-Policy","no-referrer");
        response.sendRedirect(location);
    }
}
