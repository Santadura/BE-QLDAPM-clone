package com.example.demo.social;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class SocialFlowTest {
    SocialClients clients() {
        var config=new SocialProperties();config.setGoogleClientId("test-client");config.setGoogleClientSecret("test-secret");
        return new SocialClients(config);
    }
    @Test void noCredentialsDoesNotBreakPasswordOnlyStartup() {
        var clients=new SocialClients(new SocialProperties());assertFalse(clients.enabled("google"));assertFalse(clients.enabled("facebook"));
    }
    @Test void googleRequestUsesStateNonceAndPkceAndRequiresGkStart() {
        var request=new MockHttpServletRequest("GET","/api/oauth2/authorization/google");request.setServletPath("/api/oauth2/authorization/google");
        var resolver=new SocialFlowResolver(clients());
        assertThrows(OAuth2AuthenticationException.class,()->resolver.resolve(request));
        var flow=new SocialFlow("flow-key","google",SocialCrypto.challenge("a".repeat(43)),null,null,null,Instant.now().plusSeconds(60).getEpochSecond());
        request.getSession().setAttribute(SocialFlow.SESSION,flow);request.setParameter("flow","flow-key");
        var auth=resolver.resolve(request);assertNotNull(auth.getState());assertNotNull(auth.getAdditionalParameters().get("nonce"));
        assertEquals("S256",auth.getAdditionalParameters().get("code_challenge_method"));
        assertNotNull(auth.getAdditionalParameters().get("code_challenge"));assertNull(request.getSession().getAttribute(SocialFlow.SESSION));
        var repository=new SocialAuthorizationRequests();var response=new MockHttpServletResponse();repository.saveAuthorizationRequest(auth,request,response);
        var callback=new MockHttpServletRequest();callback.setSession(request.getSession());callback.setParameter("state","forged");
        assertNull(repository.removeAuthorizationRequest(callback,response));
        callback.setParameter("state",auth.getState());assertNotNull(repository.removeAuthorizationRequest(callback,response));
        assertEquals(flow,callback.getAttribute(SocialFlow.ATTRIBUTE));assertNull(repository.removeAuthorizationRequest(callback,response));
    }
    @Test void proofUsesRfc7636S256AndConstantTimeMatch() {
        String verifier="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",SocialCrypto.challenge(verifier));
        assertTrue(SocialCrypto.matches(verifier,SocialCrypto.challenge(verifier)));
        assertFalse(SocialCrypto.matches("bad",SocialCrypto.challenge(verifier)));
    }
}
