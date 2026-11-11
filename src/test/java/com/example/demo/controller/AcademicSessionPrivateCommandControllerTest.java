package com.example.demo.controller;
import com.example.demo.service.ClassSessionService;
import com.example.demo.service.SchedulingCommandReceiptService;
import com.example.demo.service.ClassSessionService.RescheduleRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademicSessionPrivateCommandControllerTest {
  ClassSessionService sessions=mock(ClassSessionService.class);
  SchedulingCommandReceiptService receipts=mock(SchedulingCommandReceiptService.class);
  Authentication auth=mock(Authentication.class);
  AcademicSessionPrivateCommandController bridge=
      new AcademicSessionPrivateCommandController(sessions,receipts,"secret");
  @Test void rejectsBadServiceKeyAndMissingJwtBeforeAnyWrite() {
    assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
        ()->bridge.replace("bad","Bearer admin","k","class-1",List.of(),auth)).getStatusCode());
    assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
        ()->bridge.replace("secret",null,"k","class-1",List.of(),auth)).getStatusCode());
    verifyNoInteractions(sessions,receipts);
  }
  @Test void revokedManagerCannotReplayCachedCoreReceipt() {
    when(sessions.requireSessionManager("class-1",auth)).thenThrow(
        new ResponseStatusException(HttpStatus.FORBIDDEN));
    assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
        ()->bridge.replace("secret","Bearer admin","old-key","class-1",List.of(),auth)).getStatusCode());
    verifyNoInteractions(receipts);
  }
  @Test void forwardsDistinctCommandScopesAndCurrentActor() {
    when(sessions.requireSessionManager("class-1",auth)).thenReturn("admin");
    bridge.replace("secret","Bearer admin","same-key","class-1",List.of(),auth);
    bridge.reschedule("secret","Bearer admin","same-key","class-1",
        new RescheduleRequest("lesson-1",null,null,null,"Cancel lesson"),auth);
    verify(receipts).execute(eq("same-key"),eq("admin"),eq("ACADEMIC:PLAN:class-1"),
        any(),any());
    verify(receipts).execute(eq("same-key"),eq("admin"),eq("ACADEMIC:RESCHEDULE:class-1"),
        any(),any());
  }
}
