package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicNativeSessionControllerTest {
    private final AcademicNativeSessionService service=
        mock(AcademicNativeSessionService.class);
    private final AcademicNativeSessionController controller=
        new AcademicNativeSessionController(service,"private-key");
    private final ObjectMapper json=new ObjectMapper();

    @Test void nativeMutationIsNeverEnabledByDefault() {
        var condition=AcademicNativeSessionController.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertArrayEquals(new String[]{"iig.academic.native-rehearsal-enabled"},
            condition.name());
        assertEquals("true",condition.havingValue());
        assertFalse(condition.matchIfMissing());
    }
    @Test void missingOrWrongPrivateKeyCannotTouchNativeDatabase() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.plan(null,"Bearer admin","key-1","class-1",
                json.createArrayNode())).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.activate("bad","Bearer admin","class-1")).getStatusCode());
        verifyNoInteractions(service);
    }
    @Test void nativePlanAndRescheduleNeverClaimBusinessSuccess() {
        var plan=json.createArrayNode();
        var reschedule=json.createObjectNode();
        when(service.execute("PLAN","class-1",plan,"Bearer admin","key-1"))
            .thenReturn(plan);
        when(service.execute("RESCHEDULE","class-1",reschedule,"Bearer admin","key-2"))
            .thenReturn(plan);
        var first=controller.plan("private-key","Bearer admin","key-1",
            "class-1",plan);
        var second=controller.reschedule("private-key","Bearer admin","key-2",
            "class-1",reschedule);
        assertEquals(HttpStatus.ACCEPTED,first.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED,second.getStatusCode());
        assertEquals("REHEARSAL_ONLY",
            first.getHeaders().getFirst("X-Academic-Native-State"));
        assertEquals("REHEARSAL_ONLY",
            second.getHeaders().getFirst("X-Academic-Native-State"));
        verify(service).execute("PLAN","class-1",plan,"Bearer admin","key-1");
        verify(service).execute("RESCHEDULE","class-1",reschedule,"Bearer admin","key-2");
    }
    @Test void activationDoesNotClaimOwnerTransfer() {
        when(service.activate("class-1","Bearer admin")).thenReturn(3L);
        var result=controller.activate("private-key","Bearer admin","class-1");
        assertEquals(HttpStatus.ACCEPTED,result.getStatusCode());
        assertEquals("REHEARSAL_ONLY",result.getBody().state());
        assertEquals(3,result.getBody().importedRows());
    }
}
