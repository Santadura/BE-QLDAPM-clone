package vn.iig.academic;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterReadinessTest {
    final AcademicRosterSourceClient core=mock(AcademicRosterSourceClient.class);
    final AcademicRosterSourceRepository local=mock(AcademicRosterSourceRepository.class);
    final AcademicRosterReadiness readiness=new AcademicRosterReadiness(core,local);
    static final AcademicRosterSourceClient.Summary SOURCE=
        new AcademicRosterSourceClient.Summary(2,3,1,1,2);
    static final AcademicRosterSourceClient.Integrity CLEAN=
        new AcademicRosterSourceClient.Integrity(1,1,1,1,0);
    static final AcademicRosterSourceClient.Event STUDENT=
        new AcademicRosterSourceClient.Event(1,"STUDENT","student-1","UPSERT",
            1,"{\"student_id\":\"student-1\"}","a".repeat(64));
    static final AcademicRosterSourceClient.Event MEMBER=
        new AcademicRosterSourceClient.Event(3,"MEMBERSHIP","member-1","UPSERT",
            3,"{\"class_student_id\":\"member-1\"}","b".repeat(64));
    void ready() {
        when(core.summary()).thenReturn(SOURCE);
        when(core.integrity()).thenReturn(CLEAN);
        when(local.cursor()).thenReturn(3L);
        when(local.receiptCount()).thenReturn(2L);
        when(local.studentCount()).thenReturn(1L);
        when(local.membershipCount()).thenReturn(1L);
        when(core.history(0,100)).thenReturn(List.of(STUDENT,MEMBER));
        when(local.matchesEvent(STUDENT)).thenReturn(true);
        when(local.matchesEvent(MEMBER)).thenReturn(true);
    }
    @Test void fullHistoricalProofIncludingMembershipPasses() {
        ready();
        var evidence=readiness.verify();
        assertEquals(2,evidence.sourceEvents());
        assertEquals(3,evidence.lastEventId());
        verify(local).matchesEvent(MEMBER);
        verify(core,times(2)).summary();
        verify(core,times(2)).integrity();
    }
    @Test void liveSourceDriftFailsBeforeLocalAccess() {
        ready();
        when(core.integrity()).thenReturn(
            new AcademicRosterSourceClient.Integrity(1,1,1,1,1));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,readiness::verify).getStatusCode());
        verifyNoInteractions(local);
    }
    @Test void damagedTypedStudentShadowBlocksRead() {
        ready();
        when(local.latestDivergence()).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,readiness::verify).getStatusCode());
        verify(core,never()).history(anyLong(),anyInt());
    }
    @Test void missingOrAlteredHistoricalReceiptBlocksRead() {
        ready();
        when(local.matchesEvent(MEMBER)).thenReturn(false);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,readiness::verify).getStatusCode());
    }
    @Test void newSourceEventAfterScanCannotBecomeAuthorization() {
        ready();
        when(core.summary()).thenReturn(SOURCE,
            new AcademicRosterSourceClient.Summary(3,4,1,1,2));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,readiness::verify).getStatusCode());
    }
    @Test void lostCoreAvailabilityIsFailClosed() {
        ready();
        when(core.summary()).thenThrow(new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Core offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,readiness::verify).getStatusCode());
        verifyNoInteractions(local);
    }
}
