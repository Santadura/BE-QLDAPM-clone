package com.example.demo.controller;

import com.example.demo.service.TeacherAttendanceCommandReceiptService;
import com.example.demo.service.TeacherSessionAttendanceService.MarkRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeacherAttendancePrivateCommandControllerTest {
    private final TeacherAttendanceCommandReceiptService receipts=mock(TeacherAttendanceCommandReceiptService.class);
    private final TeacherAttendancePrivateCommandController bridge=
        new TeacherAttendancePrivateCommandController(receipts,"secret-key");

    @Test void wrongOrMissingKeyRejectsBeforeMutation() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark("wrong","Bearer cs","request-1","class-1","teach-1",
                new MarkRequest("PRESENT"))).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark(null,"Bearer cs","request-1","class-1","teach-1",
                new MarkRequest("PRESENT"))).getStatusCode());
        verifyNoInteractions(receipts);
    }

    @Test void serviceKeyAloneNeverImpersonatesCs() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark("secret-key",null,"request-1","class-1","teach-1",
                new MarkRequest("PRESENT"))).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark("secret-key","Bearer   ","request-1","class-1","teach-1",
                new MarkRequest("PRESENT"))).getStatusCode());
        verifyNoInteractions(receipts);
    }

    @Test void authorizedIngressForwardsStableKeyToTransactionalCoreReceipt() {
        MarkRequest request=new MarkRequest("PRESENT");
        when(receipts.mark("class-1","teach-1","request-1",request))
            .thenReturn(Map.of("status","PRESENT"));
        assertEquals("PRESENT",bridge.mark("secret-key","Bearer cs","request-1",
            "class-1","teach-1",request).get("status"));
        verify(receipts).mark("class-1","teach-1","request-1",request);
    }
}
