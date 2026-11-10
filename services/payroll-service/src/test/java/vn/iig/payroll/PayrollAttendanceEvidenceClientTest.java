package vn.iig.payroll;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class PayrollAttendanceEvidenceClientTest {
    private final PayrollAttendanceEvidenceClient client=
        new PayrollAttendanceEvidenceClient("http://localhost:1","private-payroll-key");

    @Test void invalidOrDuplicateIdsAreRejectedBeforeAnyNetworkCall() {
        assertEquals(HttpStatus.BAD_GATEWAY,assertThrows(
            ResponseStatusException.class,
            ()->client.snapshot(List.of())).getStatusCode());
        assertEquals(HttpStatus.BAD_GATEWAY,assertThrows(
            ResponseStatusException.class,
            ()->client.snapshot(List.of("teach-1","teach-1"))).getStatusCode());
        assertEquals(HttpStatus.BAD_GATEWAY,assertThrows(
            ResponseStatusException.class,
            ()->client.snapshot(List.of("teach-1;DROP"))).getStatusCode());
    }

    @Test void serviceKeyMustBeProvidedToEnablePrivatePayrollProof() {
        assertThrows(IllegalArgumentException.class,
            ()->new PayrollAttendanceEvidenceClient("http://localhost:8100",""));
    }
}
