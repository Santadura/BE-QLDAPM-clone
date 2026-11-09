package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AcademicPlanningSnapshotBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AcademicPlanningSnapshotBridge bridge=
        new AcademicPlanningSnapshotBridge(db,"private-academic-key");

    @Test void privateKeyBlocksAnyAcademicSQL() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->bridge.version("wrong")).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void versionRequiresValidMonotonicSourceClock() {
        when(db.queryForObject(startsWith("SELECT revision"),eq(Long.class)))
            .thenReturn(0L);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,
                ()->bridge.version("private-academic-key")).getStatusCode());
    }

    @Test void privateSnapshotKeepsExactFEFieldNamesAndNoStudents() {
        when(db.queryForObject(startsWith("SELECT revision"),eq(Long.class)))
            .thenReturn(7L);
        when(db.queryForList(startsWith("SELECT c.class_id AS")))
            .thenReturn(List.of(Map.of("classId","class-1","classCode","AC-1",
                "startDate",LocalDate.of(2027,2,11),"courseName","IELTS")));
        var result=bridge.snapshot("private-academic-key");
        assertEquals(7,result.revision());
        assertEquals("class-1",result.rows().get(0).get("classId"));
        assertFalse(result.rows().get(0).containsKey("students"));
        verify(db).queryForList(contains("ORDER BY c.start_date,c.class_code"));
    }
}
