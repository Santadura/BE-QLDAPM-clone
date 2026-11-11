package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicEventSessionReadServiceTest {
    AcademicCoreClient core=mock(AcademicCoreClient.class);
    AcademicSessionSourceClient source=mock(AcademicSessionSourceClient.class);
    AcademicSessionSourceRepository local=mock(AcademicSessionSourceRepository.class);
    AcademicSessionSourceRelay relay=mock(AcademicSessionSourceRelay.class);
    AcademicEventSessionReadService service=
        new AcademicEventSessionReadService(core,source,local,relay,new ObjectMapper());
    static final String BEARER="Bearer fixture";
    static final AcademicSessionSourceClient.Summary CURRENT=
        new AcademicSessionSourceClient.Summary(3,7,0,7);
    void ready() {
        when(core.currentPrincipal(BEARER)).thenReturn(
            new AcademicCoreClient.Principal("cs001","ROLE_CS"));
        when(core.sessionRevision("c1",BEARER)).thenReturn(
            new AcademicCoreClient.SessionRevision(3));
        when(source.summary()).thenReturn(CURRENT);
        when(local.historyCursor()).thenReturn(7L);
        when(local.receiptCount()).thenReturn(7L);
        when(local.eventBackedClassSessions("c1")).thenReturn("[{\"id\":\"s1\"}]");
    }
    @Test void readsIndependentTypedStoreWithoutCoreSnapshot() {
        ready();
        assertEquals("s1",service.sessions("c1",BEARER)
            .payload().get(0).path("id").asText());
        verify(core,never()).sessionExport(anyString(),anyString());
        verify(core,times(2)).sessionRevision("c1",BEARER);
    }
    @Test void temporarilyLaggingSourceIsReconciledBeforeReading() {
        ready();
        when(source.summary()).thenReturn(
            new AcademicSessionSourceClient.Summary(3,7,1,7),
            CURRENT,CURRENT);
        assertEquals("s1",service.sessions("c1",BEARER)
            .payload().get(0).path("id").asText());
        verify(relay).drain(3);
        verify(relay).backfill(3);
        verify(core,never()).sessionExport(anyString(),anyString());
    }
    @Test void refusesUnacknowledgedSourceMutation() {
        ready();
        when(source.summary()).thenReturn(
            new AcademicSessionSourceClient.Summary(3,7,1,7));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->service.sessions("c1",BEARER)).getStatusCode());
        verify(local,never()).eventBackedClassSessions(anyString());
    }
    @Test void refusesIncompleteHistoricalReplay() {
        ready();
        when(local.historyCursor()).thenReturn(6L);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->service.sessions("c1",BEARER)).getStatusCode());
    }
    @Test void revokedClassPermissionNeverReadsLocalArray() {
        ready();
        when(core.sessionRevision("c1",BEARER)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN,"Revoked"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,()->service.sessions("c1",BEARER)).getStatusCode());
        verify(local,never()).eventBackedClassSessions(anyString());
    }
    @Test void changingRevisionMustBeRechecked() {
        ready();
        when(core.sessionRevision("c1",BEARER)).thenReturn(
            new AcademicCoreClient.SessionRevision(3),
            new AcademicCoreClient.SessionRevision(4),
            new AcademicCoreClient.SessionRevision(4),
            new AcademicCoreClient.SessionRevision(4));
        assertEquals("s1",service.sessions("c1",BEARER)
            .payload().get(0).path("id").asText());
        verify(core,atLeast(4)).sessionRevision("c1",BEARER);
    }
}
