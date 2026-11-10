package com.example.demo.controller;

import com.example.demo.service.TeacherSessionAttendanceService;
import com.example.demo.service.TeacherSessionAttendanceService.MarkRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeacherAttendancePrivateCommandControllerTest {
    private final TeacherSessionAttendanceService attendance=mock(TeacherSessionAttendanceService.class);
    private final TeacherAttendancePrivateCommandController bridge=
        new TeacherAttendancePrivateCommandController(attendance,"secret-key");

    @Test void wrongOrMissingServiceKeyFailsBeforeMutation() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark("wrong","Bearer valid","class-1","teach-1",new MarkRequest("PRESENT"))).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark(null,"Bearer valid","class-1","teach-1",new MarkRequest("PRESENT"))).getStatusCode());
        verifyNoInteractions(attendance);
    }

    @Test void serviceKeyWithoutBearerCannotImpersonateCs() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark("secret-key",null,"class-1","teach-1",new MarkRequest("PRESENT"))).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark("secret-key","Bearer   ","class-1","teach-1",new MarkRequest("PRESENT"))).getStatusCode());
        verifyNoInteractions(attendance);
    }

    @Test void verifiedIngressDelegatesToOriginalTransactionalMark() {
        MarkRequest request=new MarkRequest("PRESENT");
        when(attendance.mark("class-1","teach-1",request)).thenReturn(Map.of("status","PRESENT"));
        assertEquals("PRESENT",bridge.mark("secret-key","Bearer valid","class-1","teach-1",request).get("status"));
        verify(attendance).mark("class-1","teach-1",request);
    }
}
