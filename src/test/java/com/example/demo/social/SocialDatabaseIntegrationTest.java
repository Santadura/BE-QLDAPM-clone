package com.example.demo.social;

import com.example.demo.dto.AuthDtos.RegisterRequest;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

// Run explicitly against a migrated local PostgreSQL database. Every change rolls back.
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="GK_SOCIAL_DB_TEST", matches="true")
class SocialDatabaseIntegrationTest {
    @Autowired SocialAccountService social;
    @Autowired SocialRepository identities;
    @Autowired UserRepository users;
    @Autowired AuthService auth;

    @Test void firstAndRepeatedProviderLoginPersistOneAccountAndRedeemOnlyOnce() {
        String id="test-"+UUID.randomUUID();
        String verifier="a".repeat(43);
        var flow=new SocialFlow("flow","facebook",SocialCrypto.challenge(verifier),null,null,null,Instant.now().plusSeconds(60).getEpochSecond());
        var principal=new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),Map.of("id",id,"email",id+"@example.invalid","name","Integration Test"),"id");
        String code=social.complete("facebook",principal,flow);
        String username=identities.identity("facebook",id).orElseThrow().username();
        var account=users.findByUsername(username).orElseThrow();
        assertFalse(account.isPasswordLoginEnabled());assertEquals("USER",account.getRoleId());
        assertEquals(username,social.exchange(code,verifier).username());
        String next=social.complete("facebook",principal,flow);
        assertEquals(username,social.exchange(next,verifier).username());
        assertEquals(1,identities.connections(username).size());
        assertThrows(SocialException.class,()->social.exchange(code,verifier));
    }

    @Test void existingEmailIsNotSilentlyAttachedToProviderIdentity() {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,16);
        String email="test-"+suffix+"@example.invalid";
        var local=auth.register(new RegisterRequest("test_"+suffix,email,"TestOnly123!"));
        var principal=new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),Map.of("id",suffix,"email",email),"id");
        var flow=new SocialFlow("flow","facebook",SocialCrypto.challenge("a".repeat(43)),null,null,null,Instant.now().plusSeconds(60).getEpochSecond());
        var failure=assertThrows(SocialException.class,()->social.complete("facebook",principal,flow));
        assertEquals("EMAIL_EXISTS",failure.code());assertTrue(identities.connections(local.username()).isEmpty());
    }
}
