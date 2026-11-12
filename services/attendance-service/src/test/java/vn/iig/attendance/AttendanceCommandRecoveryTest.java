package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceCommandRecoveryTest {
    final AttendanceCommandInbox inbox=mock(AttendanceCommandInbox.class);
    final AttendanceCommittedReceiptClient core=mock(AttendanceCommittedReceiptClient.class);
    final AttendanceCommandRecovery recovery=new AttendanceCommandRecovery(inbox,core);
    final AttendanceCommandInbox.Pending item=new AttendanceCommandInbox.Pending(
        "cs001","class-1","teach-1","request-1","a".repeat(64));

    @Test void absentCoreCommitLeavesOriginalAttendanceInboxPending() {
        when(core.confirmed(item)).thenReturn(null);
        assertFalse(recovery.recover(item));
        verify(inbox,never()).complete(anyString(),anyString(),anyString(),
            anyString(),anyString(),any());
    }

    @Test void exactImmutableCoreReceiptClosesLocalPendingWithoutReplayingBusinessPut()
            throws Exception {
        var response=new ObjectMapper().readTree(
            "{\"teachingScheduleId\":\"teach-1\",\"status\":\"PRESENT\"}");
        when(core.confirmed(item)).thenReturn(response);
        assertTrue(recovery.recover(item));
        verify(inbox).complete("cs001","class-1","teach-1","request-1",
            "a".repeat(64),response);
    }

    @Test void CoreOutageDoesNotGenerateFalseLocalCommit() {
        when(core.confirmed(item)).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Core offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->recovery.recover(item)).getStatusCode());
        verify(inbox,never()).complete(anyString(),anyString(),anyString(),
            anyString(),anyString(),any());
    }

    @Test void exhaustedRecoveryContinuesWithOtherPendingReceipts() throws Exception {
        var other=new AttendanceCommandInbox.Pending(
            "cs002","class-2","teach-2","request-2","b".repeat(64));
        var evidence=new ObjectMapper().readTree(
            "{\"teachingScheduleId\":\"teach-2\",\"status\":\"LATE\"}");
        when(inbox.pending(25)).thenReturn(List.of(item,other));
        when(core.confirmed(item)).thenThrow(
            new ResponseStatusException(HttpStatus.CONFLICT,"Tampered receipt"));
        when(core.confirmed(other)).thenReturn(evidence);
        recovery.tick();
        verify(inbox).complete("cs002","class-2","teach-2","request-2",
            "b".repeat(64),evidence);
        verify(inbox,never()).complete(eq("cs001"),anyString(),anyString(),
            anyString(),anyString(),any());
    }

    @Test void recoveryMustBeExplicitlyEnabled() {
        var flag=AttendanceCommandRecovery.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(flag);
        assertArrayEquals(new String[]{"iig.attendance.command-recovery-enabled"},flag.name());
        assertEquals("true",flag.havingValue());
        assertFalse(flag.matchIfMissing());
    }
}
