package vn.iig.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns an isolated credential projection and identity session revocations. */
@Repository
public class IdentityRepository {
    private final JdbcTemplate db;

    public IdentityRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void sync(IdentityCoreClient.Snapshot user) {
        db.update("INSERT INTO identity_users(username,email,password_hash,role_id,status) " +
                "VALUES(?,?,?,?,?) ON CONFLICT(username) DO UPDATE " +
                "SET email=EXCLUDED.email,password_hash=EXCLUDED.password_hash," +
                "role_id=EXCLUDED.role_id,status=EXCLUDED.status,synchronized_at=NOW()",
            user.username(), user.email(), user.passwordHash(), user.role(), user.status());
    }

    public void markRevoked(String tokenId) {
        db.update("INSERT INTO identity_revocations(token_id) VALUES(?) " +
            "ON CONFLICT(token_id) DO NOTHING", tokenId);
    }
}
