package com.example.demo.social;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

public class SocialAuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private final HttpSessionOAuth2AuthorizationRequestRepository delegate=new HttpSessionOAuth2AuthorizationRequestRepository();
    @Override public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) { return delegate.loadAuthorizationRequest(request); }
    @Override public void saveAuthorizationRequest(OAuth2AuthorizationRequest auth,HttpServletRequest request,HttpServletResponse response) { delegate.saveAuthorizationRequest(auth,request,response); }
    @Override public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,HttpServletResponse response) {
        var auth=delegate.removeAuthorizationRequest(request,response);
        if (auth!=null) request.setAttribute(SocialFlow.ATTRIBUTE,auth.getAttribute(SocialFlow.ATTRIBUTE));
        return auth;
    }
}
