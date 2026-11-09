package vn.iig.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class AuthenticationServiceTest {
    private final IdentityCoreClient core = mock(IdentityCoreClient.class);
    private final IdentityRepository repository = mock(IdentityRepository.class);
    private final IdentityJwtService jwt = new IdentityJwtService(
        "this-is-a-long-test-secret-that-is-signature-safe-and-256-bit", 86_400_000L);
    private final AuthenticationService auth = new AuthenticationService(core,repository,jwt);
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @Test void signsTokenCompatibleWithCoreClaims() {
        String hashed = bcrypt.encode("123456");
        var snapshot = new IdentityCoreClient.Snapshot(
            "admin","admin@example.test",hashed,"ADMIN","ACTIVE");
        when(core.currentAccount("admin")).thenReturn(snapshot);
        var response = auth.login("admin","123456");
        assertEquals("Bearer",response.tokenType());
        assertEquals("admin",response.username());
        assertEquals("ADMIN",response.role());
        assertNotNull(jwt.tokenId(response.token()));
        verify(repository).sync(snapshot);
    }

    @Test void rejectsIncorrectPasswordWithoutIssuingSession() {
        String hashed = bcrypt.encode("correct-password");
        when(core.currentAccount("teacher001")).thenReturn(
            new IdentityCoreClient.Snapshot(
                "teacher001","teacher@example.test",hashed,"TEACHER","ACTIVE"));
        assertThrows(ResponseStatusException.class,
            () -> auth.login("teacher001","wrong-password"));
        verifyNoInteractions(repository);
    }

    @Test void refusesLockedAccountEvenWithCorrectPassword() {
        String hashed = bcrypt.encode("123456");
        when(core.currentAccount("staff001")).thenReturn(
            new IdentityCoreClient.Snapshot(
                "staff001","staff@example.test",hashed,"CS","LOCKED"));
        assertThrows(ResponseStatusException.class,
            () -> auth.login("staff001","123456"));
        verifyNoInteractions(repository);
    }

    @Test void logoutDelegatesRevocationBeforeAcknowledging() {
        String token = jwt.issue("admin","ADMIN",bcrypt.encode("123456"));
        auth.logout("Bearer "+token);
        var order = inOrder(core,repository);
        order.verify(core).revokeInCore("Bearer "+token);
        order.verify(repository).markRevoked(jwt.tokenId(token));
    }

    @Test void logoutRejectsNetworkFailureWithoutPretendingSuccess() {
        String token = jwt.issue("admin","ADMIN",bcrypt.encode("123456"));
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
            .when(core).revokeInCore("Bearer "+token);
        assertThrows(ResponseStatusException.class, () -> auth.logout("Bearer "+token));
        verifyNoInteractions(repository);
    }
}
