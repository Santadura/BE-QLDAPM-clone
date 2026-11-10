package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceWriteControllerTest {
    private final AttendanceCoreClient core=mock(AttendanceCoreClient.class);
    private final AttendanceCommandService writer=mock(AttendanceCommandService.class);
    private final AttendanceWriteController controller=new AttendanceWriteController(core,writer);
    private final AttendanceWriteClient.MarkRequest request=
        new AttendanceWriteClient.MarkRequest("PRESENT");

    @Test void rejectsInvalidIdBeforeAuthentication() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->controller.mark("","teach-1","Bearer x",request,null)).getStatusCode());
        verifyNoInteractions(core,writer);
    }

    @Test void requiresLiveCsRoleRatherThanJustAnyValidJwt() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AttendanceCoreClient.Principal("admin","ROLE_ADMIN"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->controller.mark("class-1","teach-1","Bearer admin",request,"k1")).getStatusCode());
        verifyNoInteractions(writer);
    }

    @Test void propagatesCoreSessionFailureWithoutCallingWriter() {
        when(core.currentPrincipal(null)).thenThrow(
            new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Missing JWT"));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->controller.mark("class-1","teach-1",null,request,null)).getStatusCode());
        verifyNoInteractions(writer);
    }

    @Test void forwardsOnlyAuthorizedCsUsingOriginalTokenAndSameKey() throws Exception {
        when(core.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        var body=new ObjectMapper().readTree(
            "{\"id\":\"attendance-1\",\"teachingScheduleId\":\"teach-1\",\"status\":\"PRESENT\"}");
        when(writer.mark("cs001","class-1","teach-1","Bearer cs","PRESENT","request-1"))
            .thenReturn(body);
        var result=controller.mark("class-1","teach-1","Bearer cs",request,"request-1");
        assertEquals(HttpStatus.OK,result.getStatusCode());
        assertEquals(body,result.getBody());
        verify(writer).mark("cs001","class-1","teach-1","Bearer cs","PRESENT","request-1");
    }

    @Test void coreWriteOutageFailsClosed() {
        when(core.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(writer.mark("cs001","class-1","teach-1","Bearer cs","PRESENT","k1"))
            .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Core offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,
            ()->controller.mark("class-1","teach-1","Bearer cs",request,"k1")).getStatusCode());
    }
}
