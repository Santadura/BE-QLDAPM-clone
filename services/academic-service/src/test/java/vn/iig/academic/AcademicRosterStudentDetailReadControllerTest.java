package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterStudentDetailReadControllerTest {
    final AcademicRosterStudentDetailReadService service=
        mock(AcademicRosterStudentDetailReadService.class);

    @Test void noStoreForSensitiveStudentDetailWithExistingGetPath() {
        var data=new ObjectMapper().createObjectNode().put("studentId","student-1");
        when(service.detail("student-1","Bearer admin")).thenReturn(data);
        var response=new AcademicRosterStudentDetailReadController(service)
            .detail("student-1","Bearer admin");
        assertEquals(data,response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).detail("student-1","Bearer admin");
    }
}
