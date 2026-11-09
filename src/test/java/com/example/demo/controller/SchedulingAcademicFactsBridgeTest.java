package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class SchedulingAcademicFactsBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingAcademicFactsBridge bridge=
        new SchedulingAcademicFactsBridge(db,"trusted-scheduling-key");
    private final LocalDate date=LocalDate.of(2027,2,11);
    private final LocalTime start=LocalTime.of(9,0),end=LocalTime.of(11,0);

    @Test void privateKeyRequiredBeforeAnyAcademicDatabaseAccess() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->bridge.session("wrong","c1",date,start,end)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->bridge.session(null,"c1",date,start,end)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void missingAcademicSessionFailsClosedInsteadOfInventingPlannedStatus() {
        when(db.query(contains("FROM classes c JOIN class_sessions"),
            any(RowMapper.class),eq("c1"),eq(date),eq(start),eq(end)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->bridge.session("trusted-scheduling-key","c1",date,start,end)).getStatusCode());
    }

    @Test void rejectsInvalidTimeWithoutReadingClassTables() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->bridge.session("trusted-scheduling-key","c1",date,end,start)).getStatusCode());
        verifyNoInteractions(db);
    }
}
