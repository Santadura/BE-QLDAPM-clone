package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttendanceCommandServiceTest {
    private final AttendanceWriteClient writer=mock(AttendanceWriteClient.class);
    private final AttendanceCommandInbox inbox=mock(AttendanceCommandInbox.class);
    private final AttendanceCommandService service=new AttendanceCommandService(writer,inbox);

    @Test void normalizesStatusAndSavesIndependentReceiptAfterCoreAck() throws Exception {
        var response=new ObjectMapper().readTree("{\"status\":\"PRESENT\"}");
        when(writer.mark("class-1","teach-1","Bearer cs","PRESENT","retry-key")).thenReturn(response);
        var result=service.mark("cs001","class-1","teach-1","Bearer cs"," present ","retry-key");
        assertEquals(response,result);
        var order=inOrder(inbox,writer);
        order.verify(inbox).stage(eq("cs001"),eq("class-1"),eq("teach-1"),
            eq("retry-key"),anyString());
        order.verify(writer).mark("class-1","teach-1","Bearer cs","PRESENT","retry-key");
        order.verify(inbox).complete(eq("cs001"),eq("class-1"),eq("teach-1"),
            eq("retry-key"),anyString(),eq(response));
    }

    @Test void ambiguousCoreTimeoutRetainsPendingAndNeverMarksCommitted() {
        when(writer.mark("class-1","teach-1","Bearer cs","PRESENT","retry-key"))
            .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"timeout"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,
            ()->service.mark("cs001","class-1","teach-1","Bearer cs","PRESENT","retry-key"))
            .getStatusCode());
        verify(inbox).stage(eq("cs001"),eq("class-1"),eq("teach-1"),eq("retry-key"),anyString());
        verify(inbox,never()).complete(anyString(),anyString(),anyString(),anyString(),
            anyString(),any());
    }

    @Test void malformedOrConflictingKeyFailsWithoutSendingCoreWrite() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.mark("cs001","class-1","teach-1","Bearer cs","PRESENT","bad key"))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.mark("cs001","class-1","teach-1","Bearer cs","WRONG","goodkey"))
            .getStatusCode());
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"Key reused"))
            .when(inbox).stage(eq("cs001"),eq("class-1"),eq("teach-1"),eq("same-key"),anyString());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.mark("cs001","class-1","teach-1","Bearer cs","PRESENT","same-key"))
            .getStatusCode());
        verifyNoInteractions(writer);
    }

    @Test void oldFrontendWithoutKeyMaintainsOriginalContract() throws Exception {
        var body=new ObjectMapper().readTree("{\"status\":\"PRESENT\"}");
        when(writer.mark("class-1","teach-1","Bearer cs","PRESENT",null)).thenReturn(body);
        assertEquals(body,service.mark("cs001","class-1","teach-1","Bearer cs","PRESENT",null));
        verifyNoInteractions(inbox);
    }
}
