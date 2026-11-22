package com.example.demo.social;

import com.example.demo.entity.User;
import com.example.demo.repository.AuditLogRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SocialAccountServiceTest {
    SocialRepository repo=mock(SocialRepository.class);
    UserRepository users=mock(UserRepository.class);
    AuditLogRepository audit=mock(AuditLogRepository.class);
    PasswordEncoder encoder=mock(PasswordEncoder.class);
    JwtUtil jwt=mock(JwtUtil.class);
    SocialAccountService service=new SocialAccountService(repo,users,audit,encoder,jwt);
    String verifier="a".repeat(43);
    SocialFlow login=new SocialFlow("flow","facebook",SocialCrypto.challenge(verifier),null,null,null,Instant.now().plusSeconds(600).getEpochSecond());
    User student=User.builder().username("student").email("student@example.com").passwordHash("hash").build();
    @BeforeEach void setup() {
        when(repo.identity(anyString(),anyString())).thenReturn(Optional.empty());
        when(users.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(encoder.encode(anyString())).thenReturn("encoded-random");
    }
    OAuth2User facebook(String subject,String email) {
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),Map.of("id",subject,"email",email,"name","GK Student"),"id");
    }
    SocialException fail(Runnable action,String code) { var ex=assertThrows(SocialException.class,action::run);assertEquals(code,ex.code());return ex; }

    @Test void firstLoginCreatesOnlyCustomerAndStoresStableProviderSubject() {
        String code=service.complete("facebook",facebook("fb-123","new@example.com"),login);
        assertEquals(43,code.length());
        var account=ArgumentCaptor.forClass(User.class);verify(users).saveAndFlush(account.capture());
        assertEquals("USER",account.getValue().getRoleId());assertFalse(account.getValue().isPasswordLoginEnabled());
        assertEquals("GK Student",account.getValue().getDisplayName());
        verify(repo).link(eq("facebook"),eq("fb-123"),eq(account.getValue().getUsername()),eq("new@example.com"));
        verify(repo).issue(eq(SocialCrypto.hash(code)),eq(account.getValue().getUsername()),eq(login.challenge()),any());
        verify(jwt,never()).generateToken(any(),any(),any());
    }
    @Test void nextLoginReusesIdentityEvenIfProviderEmailChanges() {
        when(repo.identity("facebook","fb-123")).thenReturn(Optional.of(new SocialRepository.Identity("facebook","fb-123","student","old@example.com")));
        when(users.findByUsername("student")).thenReturn(Optional.of(student));
        service.complete("facebook",facebook("fb-123","changed@example.com"),login);
        verify(users,never()).saveAndFlush(any());verify(repo,never()).link(any(),any(),any(),any());
        verify(repo).issue(anyString(),eq("student"),eq(login.challenge()),any());
    }
    @Test void emailCollisionDoesNotAutomaticallyLinkExistingAccount() {
        when(users.existsByEmailIgnoreCase("student@example.com")).thenReturn(true);
        fail(()->service.complete("facebook",facebook("fb-new","student@example.com"),login),"EMAIL_EXISTS");
        verify(repo,never()).link(any(),any(),any(),any());verify(users,never()).saveAndFlush(any());
    }
    @Test void linkedStaffOrLockedAccountCannotReceiveCustomerLoginTicket() {
        when(repo.identity("facebook","fb-123")).thenReturn(Optional.of(new SocialRepository.Identity("facebook","fb-123","student",null)));
        when(users.findByUsername("student")).thenReturn(Optional.of(student));
        student.setRoleId("ADMIN");fail(()->service.complete("facebook",facebook("fb-123","a@b.com"),login),"ACCOUNT_UNAVAILABLE");
        student.setRoleId("USER");student.setStatus("LOCKED");fail(()->service.complete("facebook",facebook("fb-123","a@b.com"),login),"ACCOUNT_UNAVAILABLE");
        verify(repo,never()).issue(any(),any(),any(),any());
    }
    @Test void googleRequiresOidcPrincipalAndVerifiedEmailForNewAccount() {
        var flow=new SocialFlow("flow","google",login.challenge(),null,null,null,login.expiresAt());
        fail(()->service.complete("google",facebook("fake","a@b.com"),flow),"PROVIDER_ERROR");
        var token=new OidcIdToken("id-token",Instant.now(),Instant.now().plusSeconds(60),Map.of("sub","google-123","email","a@b.com","email_verified",false));
        var principal=new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),token);
        fail(()->service.complete("google",principal,flow),"EMAIL_REQUIRED");
    }
    @Test void explicitLinkRequiresSameIdentityAndCurrentUnrevokedSession() {
        when(users.findByUsername("student")).thenReturn(Optional.of(student));
        var flow=new SocialFlow("flow","facebook",login.challenge(),"student",SocialCrypto.hash("hash"),"session-id",login.expiresAt());
        when(repo.connections("student")).thenReturn(List.of());
        service.complete("facebook",facebook("fb-123","other@example.com"),flow);
        verify(repo).link("facebook","fb-123","student","other@example.com");
        when(audit.existsByEntityTypeAndEntityId("JWT_SESSION","session-id")).thenReturn(true);
        fail(()->service.complete("facebook",facebook("fb-123","other@example.com"),flow),"SESSION_CHANGED");
    }
    @Test void linkingIdentityOwnedByAnotherUserIsRejected() {
        when(users.findByUsername("student")).thenReturn(Optional.of(student));
        when(repo.identity("facebook","fb-123")).thenReturn(Optional.of(new SocialRepository.Identity("facebook","fb-123","someone_else",null)));
        var flow=new SocialFlow("flow","facebook",login.challenge(),"student",SocialCrypto.hash("hash"),"session-id",login.expiresAt());
        fail(()->service.complete("facebook",facebook("fb-123","other@example.com"),flow),"IDENTITY_IN_USE");
    }
    @Test void validTicketExchangesForGkJwtAfterCheckingBrowserProof() {
        when(repo.ticketForUpdate(SocialCrypto.hash("code"))).thenReturn(Optional.of(new SocialRepository.Ticket("student",login.challenge(),Instant.now().plusSeconds(60),null)));
        when(users.findByUsername("student")).thenReturn(Optional.of(student));when(repo.consume(anyString())).thenReturn(1);
        when(jwt.generateToken("student","USER","hash")).thenReturn("jwt");
        assertEquals("jwt",service.exchange("code",verifier).token());
    }
    @Test void invalidProofExpiredAndConsumedTicketsAreRejected() {
        String hash=SocialCrypto.hash("code");
        for (var ticket : List.of(new SocialRepository.Ticket("student",login.challenge(),Instant.now().minusSeconds(1),null),
                new SocialRepository.Ticket("student",login.challenge(),Instant.now().plusSeconds(60),Instant.now()),
                new SocialRepository.Ticket("student",SocialCrypto.challenge("b".repeat(43)),Instant.now().plusSeconds(60),null))) {
            when(repo.ticketForUpdate(hash)).thenReturn(Optional.of(ticket));
            fail(()->service.exchange("code",verifier),"INVALID_TICKET");
        }
        verify(repo,never()).consume(any());verify(jwt,never()).generateToken(any(),any(),any());
    }
}
