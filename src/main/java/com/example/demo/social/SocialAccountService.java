package com.example.demo.social;

import com.example.demo.dto.AuthDtos.AuthResponse;
import com.example.demo.entity.User;
import com.example.demo.exception.ApiException;
import com.example.demo.repository.AuditLogRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SocialAccountService {
    private final SocialRepository social;
    private final UserRepository users;
    private final AuditLogRepository audit;
    private final PasswordEncoder passwords;
    private final JwtUtil jwt;

    public record Connection(String provider, String email) {}

    @Transactional(readOnly = true)
    public List<Connection> connections(Authentication actor) {
        User user = customer(actor == null ? null : actor.getName());
        return social.connections(user.getUsername()).stream().map(i -> new Connection(i.provider(),i.email())).toList();
    }
    @Transactional(readOnly = true)
    public SocialFlow newFlow(String provider, String challenge, boolean link, Authentication actor, String authorization) {
        String username=null, hash=null, tokenId=null;
        if (link) {
            User user = customer(actor == null ? null : actor.getName());
            username=user.getUsername();hash=SocialCrypto.hash(user.getPasswordHash());
            if (authorization == null || !authorization.startsWith("Bearer ")) throw new ApiException(HttpStatus.UNAUTHORIZED,"Vui lòng đăng nhập để liên kết tài khoản.");
            tokenId=jwt.extractTokenId(authorization.substring(7));
        }
        return new SocialFlow(SocialCrypto.random(),provider,challenge,username,hash,tokenId,Instant.now().plusSeconds(600).getEpochSecond());
    }

    @Transactional
    public String complete(String provider, OAuth2User principal, SocialFlow flow) {
        if (flow == null || !provider.equals(flow.provider()) || flow.expiresAt() <= Instant.now().getEpochSecond()) throw new SocialException("INVALID_FLOW");
        if ("google".equals(provider) && !(principal instanceof OidcUser)) throw new SocialException("PROVIDER_ERROR");
        String subject=principal.getAttribute("google".equals(provider)?"sub":"id");
        if (subject==null || subject.isBlank() || subject.length()>255) throw new SocialException("PROVIDER_ERROR");
        social.lockAccounts();
        var identity=social.identity(provider,subject);
        User user;
        if (flow.linkUsername()!=null) {
            user=customer(flow.linkUsername());
            if (!SocialCrypto.hash(user.getPasswordHash()).equals(flow.credentialHash()) || flow.tokenId()==null
                    || audit.existsByEntityTypeAndEntityId("JWT_SESSION",flow.tokenId())) throw new SocialException("SESSION_CHANGED");
            if (identity.isPresent() && !identity.get().username().equals(user.getUsername())) throw new SocialException("IDENTITY_IN_USE");
            if (identity.isEmpty() && social.connections(user.getUsername()).stream().anyMatch(i -> i.provider().equals(provider))) throw new SocialException("PROVIDER_ALREADY_LINKED");
        } else if (identity.isPresent()) {
            user=customer(identity.get().username());
        } else {
            String email=email(principal,provider);
            // Do not auto-merge by email, even if a provider says it was verified.
            // An existing account must prove ownership with its own current session.
            if (users.existsByEmailIgnoreCase(email) || users.existsByUsernameIgnoreCase(email)) throw new SocialException("EMAIL_EXISTS");
            String username;
            do { username="gk_"+provider+"_"+UUID.randomUUID().toString().replace("-","").substring(0,20); }
            while (users.existsByUsernameIgnoreCase(username));
            String name=principal.getAttribute("name");
            user=users.saveAndFlush(User.builder().username(username).email(email)
                    .displayName(name==null?null:name.substring(0,Math.min(100,name.length())))
                    .passwordHash(passwords.encode(SocialCrypto.random())).passwordLoginEnabled(false).roleId("USER").status("ACTIVE").build());
        }
        if (identity.isEmpty()) {
            String providerEmail=principal.getAttribute("email");
            social.link(provider,subject,user.getUsername(),providerEmail!=null&&providerEmail.length()<=100?providerEmail:null);
        }
        String ticket=SocialCrypto.random();
        social.issue(SocialCrypto.hash(ticket),user.getUsername(),flow.challenge(),Instant.now().plusSeconds(120));
        return ticket;
    }

    @Transactional
    public AuthResponse exchange(String code, String verifier) {
        String hash=SocialCrypto.hash(code);
        var ticket=social.ticketForUpdate(hash).orElseThrow(() -> new SocialException("INVALID_TICKET"));
        if (ticket.consumedAt()!=null || !ticket.expiresAt().isAfter(Instant.now()) || !SocialCrypto.matches(verifier,ticket.challenge())) throw new SocialException("INVALID_TICKET");
        User user=customer(ticket.username());
        if (social.consume(hash)!=1) throw new SocialException("INVALID_TICKET");
        return new AuthResponse(jwt.generateToken(user.getUsername(),user.getRoleId(),user.getPasswordHash()),user.getUsername(),user.getRoleId());
    }
    private User customer(String username) {
        if (username==null) throw new SocialException("LOGIN_REQUIRED");
        User user=users.findByUsername(username).orElseThrow(() -> new SocialException("ACCOUNT_UNAVAILABLE"));
        if (!"ACTIVE".equals(user.getStatus()) || !"USER".equals(user.getRoleId())) throw new SocialException("ACCOUNT_UNAVAILABLE");
        return user;
    }
    private String email(OAuth2User principal,String provider) {
        String email=principal.getAttribute("email");
        if (email==null || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length()>100) throw new SocialException("EMAIL_REQUIRED");
        if ("google".equals(provider) && !Boolean.TRUE.equals(((OidcUser)principal).getEmailVerified())) throw new SocialException("EMAIL_REQUIRED");
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
