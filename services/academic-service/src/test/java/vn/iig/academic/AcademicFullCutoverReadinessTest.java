package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicFullCutoverReadinessTest {
    final AcademicCrossDomainCutoverReadiness academic=
        mock(AcademicCrossDomainCutoverReadiness.class);
    final AcademicDependentCutoverClient downstream=
        mock(AcademicDependentCutoverClient.class);
    final AcademicFullCutoverReadiness verifier=
        new AcademicFullCutoverReadiness(academic,downstream);
    final ObjectMapper mapper=new ObjectMapper();

    AcademicCrossDomainCutoverReadiness.Evidence academicProof(long generation) {
        return new AcademicCrossDomainCutoverReadiness.Evidence(
            generation,generation,generation,generation,10,20,11,21,2,3);
    }
    AcademicDependentCutoverClient.Proof downstreamProof(long rows) {
        var attendance=mapper.createObjectNode();
        attendance.put("ready",true);attendance.put("state","READY");
        attendance.put("sourceRows",rows);attendance.put("verifiedRows",rows);
        attendance.put("mismatchCount",0);
        var scheduling=mapper.createObjectNode();
        scheduling.put("ready",true);scheduling.put("state","READY");
        scheduling.put("sourceRows",rows);scheduling.put("localRows",rows);
        scheduling.put("writeShadowRows",rows);scheduling.put("checkedRows",rows);
        scheduling.put("mismatches",0);scheduling.put("pendingChanges",0);
        scheduling.put("unresolvedCommands",0);
        scheduling.put("liveSchedules",rows);scheduling.put("trackedLiveSchedules",rows);
        return new AcademicDependentCutoverClient.Proof(attendance,scheduling);
    }

    @Test void stableDoubleScanRequiresEachIndependentServiceTwice() {
        when(academic.verify()).thenReturn(academicProof(4));
        when(downstream.verify()).thenReturn(downstreamProof(3));
        var evidence=verifier.verify();
        assertEquals(4,evidence.academic().rosterGeneration());
        assertEquals(3,evidence.downstream().attendance().path("sourceRows").asInt());
        verify(academic,times(2)).verify();
        verify(downstream,times(2)).verify();
        var order=inOrder(academic,downstream);
        order.verify(academic).verify();
        order.verify(downstream).verify();
        order.verify(academic).verify();
        order.verify(downstream).verify();
    }

    @Test void academicWriterAbaAfterFirstReadFailsClosed() {
        when(academic.verify()).thenReturn(academicProof(4),academicProof(6));
        when(downstream.verify()).thenReturn(downstreamProof(3));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,verifier::verify).getStatusCode());
    }

    @Test void attendanceProjectionChangesDuringScanFailsClosed() {
        when(academic.verify()).thenReturn(academicProof(4));
        when(downstream.verify()).thenReturn(downstreamProof(3),downstreamProof(4));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,verifier::verify).getStatusCode());
    }

    @Test void dependencyOutageCannotBeSubstitutedByOldSuccessfulProof() {
        when(academic.verify()).thenReturn(academicProof(4));
        when(downstream.verify()).thenReturn(downstreamProof(3))
            .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance lost during second scan"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,verifier::verify).getStatusCode());
    }

    @Test void missingAcademicHistoryCannotQueryDependentService() {
        when(academic.verify()).thenThrow(
            new ResponseStatusException(HttpStatus.CONFLICT,"Missing source receipt"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,verifier::verify).getStatusCode());
        verifyNoInteractions(downstream);
    }

    @Test void noPartialProofMayBePresentedAsReady() {
        when(academic.verify()).thenReturn(academicProof(4));
        when(downstream.verify()).thenReturn(null);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,verifier::verify).getStatusCode());
    }
}
