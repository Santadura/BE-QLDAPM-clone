package com.example.demo.controller;

import com.example.demo.service.TeacherAttendanceLiveFactsService;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeacherAttendanceLiveFactsControllerTest {
    private final TeacherAttendanceLiveFactsService facts=mock(TeacherAttendanceLiveFactsService.class);
    private final TeacherAttendanceLiveFactsController controller=
        new TeacherAttendanceLiveFactsController(facts,"attendance-secret");

    @Test void onlyServiceKeyAndLiveBearerCanReadExactCsLessonFacts() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->controller.current(null,"Bearer cs","class-1","teach-1")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->controller.current("wrong","Bearer cs","class-1","teach-1")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->controller.current("attendance-secret",null,"class-1","teach-1")).getStatusCode());
        verifyNoInteractions(facts);
    }

    @Test void authorizedPrivateGetForwardsClassAndTeacherContext() {
        var value=new TeacherAttendanceLiveFactsService.Facts(
            "class-1","teach-1","session-1",2,3,4,"support-1",5,"cs-001",
            LocalDate.of(2026,10,6),LocalTime.of(18,30),LocalTime.of(20,30),
            1,1,"a".repeat(64));
        when(facts.current("class-1","teach-1")).thenReturn(value);
        assertEquals(value,controller.current(
            "attendance-secret","Bearer cs","class-1","teach-1"));
        verify(facts).current("class-1","teach-1");
    }
}
