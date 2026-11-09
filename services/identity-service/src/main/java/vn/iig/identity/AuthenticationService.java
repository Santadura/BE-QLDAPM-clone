package vn.iig.identity;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Performs password validation and JWT issuance outside the legacy process.
 * Core validates current user state for each login during the transition,
 * then continues to validate the issued JWT for other domain requests.
 */
@Service
public class AuthenticationService {
    public record LoginResponse(String token, String tokenType, String username, String role) {}
    private final IdentityCoreClient core;
    private final IdentityRepository store;
    private final IdentityJwtService tokens;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthenticationService(IdentityCoreClient core, IdentityRepository store,
                                 IdentityJwtService tokens) {
        this.core = core;
        this.store = store;
        this.tokens = tokens;
    }

    public LoginResponse login(String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password required");
        }
        IdentityCoreClient.Snapshot current = core.currentAccount(username);
        if (current == null || !"ACTIVE".equalsIgnoreCase(current.status())
                || current.passwordHash() == null
                || !encoder.matches(password, current.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        store.sync(current);
        return new LoginResponse(
            tokens.issue(current.username(), current.role(), current.passwordHash()),
            "Bearer", current.username(), current.role());
    }

    public void logout(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer token required");
        }
        // Core must revoke the token before we report success. If it is
        // unreachable, the operation is rejected and may be retried.
        core.revokeInCore(authorization);
        try {
            String jti = tokens.tokenId(authorization.substring(7));
            if (jti != null && !jti.isBlank() && jti.length() <= 50) {
                store.markRevoked(jti);
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Session revocation projection could not be saved");
        }
    }
}
