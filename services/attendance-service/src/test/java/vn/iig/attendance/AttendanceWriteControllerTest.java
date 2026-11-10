package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceWriteControllerTest {
    private final AttendanceCoreClient core=mock(AttendanceCoreClient.class);
    private final AttendanceWriteClient writer=mock(AttendanceWriteClient.class);
    private final AttendanceWriteController controller=new AttendanceWriteController(core,writer);
    private final AttendanceWriteClient.MarkRequest request=
        new AttendanceWriteClient.MarkRequest("PRESENT");

    @Test void rejectsInvalidIdBeforeAuthentication() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->controller.mark("","teach-1","Bearer x",request)).getStatusCode());
        verifyNoInteractions(core,writer);
    }

    @Test void requiresLiveCsRoleRatherThanJustAnyValidJwt() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AttendanceCoreClient.Principal("admin","ROLE_ADMIN"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->controller.mark("class-1","teach-1","Bearer admin",request)).getStatusCode());
        verifyNoInteractions(writer);
    }

    @Test void propagatesCoreSessionFailureWithoutCallingWriter() {
        when(core.currentPrincipal(null)).thenThrow(
            new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Missing JWT"));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->controller.mark("class-1","teach-1",null,request)).getStatusCode());
        verifyNoInteractions(writer);
    }

    @Test void forwardsOnlyAuthorizedCsUsingOriginalTokenAndResponseShape() throws Exception {
        when(core.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        var body=new ObjectMapper().readTree(
            "{\"id\":\"attendance-1\",\"teachingScheduleId\":\"teach-1\",\"status\":\"PRESENT\"}");
        when(writer.mark("class-1","teach-1","Bearer cs","PRESENT")).thenReturn(body);
        var result=controller.mark("class-1","teach-1","Bearer cs",request);
        assertEquals(HttpStatus.OK,result.getStatusCode());
        assertEquals(body,result.getBody());
        verify(writer).mark("class-1","teach-1","Bearer cs","PRESENT");
    }

    @Test void coreWriteOutageFailsClosed() {
        when(core.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(writer.mark("class-1","teach-1","Bearer cs","PRESENT")).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Core offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,
            ()->controller.mark("class-1","teach-1","Bearer cs",request)).getStatusCode());
    }
}
