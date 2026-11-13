package vn.iig.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityPrincipalControllerTest {
    private final IdentityCoreClient core=mock(IdentityCoreClient.class);
    private final IdentityPrincipalController endpoint=
        new IdentityPrincipalController(core,"private-key");

    @Test void forgedInternalKeyCannotReadLiveUser() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->endpoint.current("wrong","Bearer live")).getStatusCode());
        verifyNoInteractions(core);
    }
    @Test void returnsOnlyCurrentlyValidatedRole() {
        var live=new IdentityCoreClient.Principal("admin","ROLE_ADMIN");
        when(core.currentPrincipal("Bearer live")).thenReturn(live);
        assertEquals(live,endpoint.current("private-key","Bearer live"));
        verify(core).currentPrincipal("Bearer live");
    }
    @Test void revokedRoleMustFailEvenWhenIdentityHasCachedCredentials() {
        when(core.currentPrincipal("Bearer revoked")).thenThrow(
            new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->endpoint.current("private-key","Bearer revoked")).getStatusCode());
    }
}
