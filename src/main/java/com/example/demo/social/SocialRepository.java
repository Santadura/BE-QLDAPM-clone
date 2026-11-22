package com.example.demo.social;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SocialRepository {
    private final JdbcTemplate jdbc;
    public record Identity(String provider, String subject, String username, String email) {}
    public record Ticket(String username, String challenge, Instant expiresAt, Instant consumedAt) {}
    public void lockAccounts() { jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(724190231)", Integer.class); }
    public Optional<Identity> identity(String provider, String subject) {
        return jdbc.query("SELECT provider, subject, username, provider_email FROM social_identities WHERE provider=? AND subject=?",
                (r, n) -> new Identity(r.getString(1),r.getString(2),r.getString(3),r.getString(4)), provider, subject).stream().findFirst();
    }
    public List<Identity> connections(String username) {
        return jdbc.query("SELECT provider, subject, username, provider_email FROM social_identities WHERE username=? ORDER BY provider",
                (r,n) -> new Identity(r.getString(1),r.getString(2),r.getString(3),r.getString(4)), username);
    }
    public void link(String provider, String subject, String username, String email) {
        jdbc.update("INSERT INTO social_identities(provider,subject,username,provider_email) VALUES (?,?,?,?)", provider, subject, username, email);
    }
    public void issue(String hash, String username, String challenge, Instant expiresAt) {
        jdbc.update("DELETE FROM social_login_tickets WHERE expires_at < ?", Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO social_login_tickets(code_hash,username,challenge,expires_at) VALUES (?,?,?,?)",hash,username,challenge,Timestamp.from(expiresAt));
    }
    public Optional<Ticket> ticketForUpdate(String hash) {
        return jdbc.query("SELECT username,challenge,expires_at,consumed_at FROM social_login_tickets WHERE code_hash=? FOR UPDATE",
                (r,n) -> new Ticket(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant(),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant()),hash).stream().findFirst();
    }
    public int consume(String hash) {
        return jdbc.update("UPDATE social_login_tickets SET consumed_at=? WHERE code_hash=? AND consumed_at IS NULL",Timestamp.from(Instant.now()),hash);
    }
}
