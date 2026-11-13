package vn.iig.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Private current-session contract, not a public Gateway endpoint. */
@RestController
@RequestMapping("/internal/identity/principal")
public class IdentityPrincipalController {
    private final IdentityCoreClient core;
    private final byte[] key;
    public IdentityPrincipalController(IdentityCoreClient core,
            @Value("${iig.identity.principal-internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Private Identity principal key required");
        this.core=core;
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping
    public IdentityCoreClient.Principal current(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer) {
        if(!MessageDigest.isEqual(key,
            (supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Identity key required");
        return core.currentPrincipal(bearer);
    }
}
