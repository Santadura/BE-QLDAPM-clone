package com.example.demo.controller;

import com.example.demo.service.TeacherSessionAttendanceService;
import com.example.demo.service.TeacherSessionAttendanceService.MarkRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeacherSessionAttendanceControllerTest {
    @Test void microservicesCorePublicPutIsForbiddenWithoutMutation() {
        var service=mock(TeacherSessionAttendanceService.class);
        var controller=new TeacherSessionAttendanceController(service);
        ReflectionTestUtils.setField(controller,"publicWriteEnabled",false);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->controller.mark("class-1","teach-1",new MarkRequest("PRESENT"))).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test void legacyCorePublicPutPreservesItsOriginalContract() {
        var service=mock(TeacherSessionAttendanceService.class);
        var controller=new TeacherSessionAttendanceController(service);
        MarkRequest request=new MarkRequest("PRESENT");
        when(service.mark("class-1","teach-1",request)).thenReturn(Map.of("status","PRESENT"));
        assertEquals("PRESENT",controller.mark("class-1","teach-1",request).get("status"));
    }
}
