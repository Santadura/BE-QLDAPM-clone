
package com.example.demo.controller;
import com.example.demo.service.EmployeeManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PayrollEmployeeAccessBridgeTest {
    private final EmployeeManagementService employees=mock(EmployeeManagementService.class);
    private final PayrollEmployeeAccessBridge bridge=new PayrollEmployeeAccessBridge(employees,"test-key");
    private final UsernamePasswordAuthenticationToken actor=
        new UsernamePasswordAuthenticationToken("admin","session");
    @Test void rejectsForgedPrivateKeyWithoutReadingEmployee() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.verify("wrong","teacher-001",actor)).getStatusCode());
        verifyNoInteractions(employees);
    }
    @Test void validPrivateKeyStillEnforcesCurrentCoreManagementScope() {
        assertEquals(HttpStatus.NO_CONTENT,
            bridge.verify("test-key","teacher-001",actor).getStatusCode());
        verify(employees).detail("teacher-001",actor);
    }
    @Test void rejectedEmployeeRoleCannotBypassLegacyAcl() {
        when(employees.detail("teacher-001",actor)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN,"Outside manager scope"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->bridge.verify("test-key","teacher-001",actor)).getStatusCode());
    }
}
