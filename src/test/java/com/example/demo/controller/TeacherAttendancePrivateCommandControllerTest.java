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
            ()->bridge.mark("wrong","Bearer cs","request-1",null,"class-1","teach-1",
                new MarkRequest("PRESENT"))).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark(null,"Bearer cs","request-1",null,"class-1","teach-1",
                new MarkRequest("PRESENT"))).getStatusCode());
        verifyNoInteractions(receipts);
    }

    @Test void serviceKeyAloneNeverImpersonatesCs() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark("secret-key",null,"request-1",null,"class-1","teach-1",
                new MarkRequest("PRESENT"))).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.mark("secret-key","Bearer   ","request-1",null,"class-1","teach-1",
                new MarkRequest("PRESENT"))).getStatusCode());
        verifyNoInteractions(receipts);
    }

    @Test void forwardsExpectedVersionTokenToLockedCoreTransaction() {
        MarkRequest request=new MarkRequest("PRESENT");
        String token="a".repeat(64);
        when(receipts.mark("class-1","teach-1","request-2",token,request))
            .thenReturn(Map.of("status","PRESENT"));
        assertEquals("PRESENT",bridge.mark("secret-key","Bearer cs","request-2",
            token,"class-1","teach-1",request).get("status"));
        verify(receipts).mark("class-1","teach-1","request-2",token,request);
    }

    @Test void authorizedIngressForwardsStableKeyToTransactionalCoreReceipt() {
        MarkRequest request=new MarkRequest("PRESENT");
        when(receipts.mark("class-1","teach-1","request-1",null,request))
            .thenReturn(Map.of("status","PRESENT"));
        assertEquals("PRESENT",bridge.mark("secret-key","Bearer cs","request-1",null,
            "class-1","teach-1",request).get("status"));
        verify(receipts).mark("class-1","teach-1","request-1",null,request);
    }
    @Test void privateRecoveryLookupRequiresValidServiceKey() {
        var request=new TeacherAttendanceCommandReceiptService.ReceiptQuery(
            "cs001","class-1","teach-1","request-1","a".repeat(64));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->bridge.committedReceipt(null,request)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->bridge.committedReceipt("wrong-key",request)).getStatusCode());
        verifyNoInteractions(receipts);
    }

    @Test void privateRecoveryIsReadOnlyAndReturnsExactCommitProof() throws Exception {
        var request=new TeacherAttendanceCommandReceiptService.ReceiptQuery(
            "cs001","class-1","teach-1","request-1","a".repeat(64));
        var response=new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"teachingScheduleId\":\"teach-1\",\"status\":\"PRESENT\"}");
        var proof=new TeacherAttendanceCommandReceiptService.CommittedReceipt(
            "cs001","class-1","teach-1","request-1","a".repeat(64),response);
        when(receipts.committedReceipt(request)).thenReturn(proof);
        assertEquals(proof,bridge.committedReceipt("secret-key",request));
        verify(receipts).committedReceipt(request);
        verify(receipts,never()).mark(anyString(),anyString(),anyString(),any(),
            any(MarkRequest.class));
    }

}
