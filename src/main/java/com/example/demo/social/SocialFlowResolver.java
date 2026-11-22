package com.example.demo.social;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import java.time.Instant;

public class SocialFlowResolver implements OAuth2AuthorizationRequestResolver {
    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    public SocialFlowResolver(SocialClients clients) { delegate=new DefaultOAuth2AuthorizationRequestResolver(clients,"/api/oauth2/authorization"); }
    @Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request) { return attach(request,delegate.resolve(request)); }
    @Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request,String registrationId) { return attach(request,delegate.resolve(request,registrationId)); }
    private OAuth2AuthorizationRequest attach(HttpServletRequest request,OAuth2AuthorizationRequest auth) {
        if (auth==null) return null;
        var session=request.getSession(false);
        Object value=session==null?null:session.getAttribute(SocialFlow.SESSION);
        if (!(value instanceof SocialFlow flow) || !flow.key().equals(request.getParameter("flow"))
                || flow.expiresAt()<=Instant.now().getEpochSecond()
                || !flow.provider().equals(auth.getAttribute("registration_id"))) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_request"),"Social login must be started from GK.");
        }
        session.removeAttribute(SocialFlow.SESSION);
        var builder=OAuth2AuthorizationRequest.from(auth).attributes(attributes -> attributes.put(SocialFlow.ATTRIBUTE,flow));
        if ("google".equals(flow.provider())) OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        return builder.build();
    }
}
