package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.Mockito;

class CatalogInternalControllerTest {
    JdbcTemplate db = Mockito.mock(JdbcTemplate.class);
    CatalogInternalController subject = new CatalogInternalController(db, "internal-key-test");

    @Test void deniesUnknownService() {
        assertThrows(ResponseStatusException.class, () ->
            subject.project("wrong-key",
                new CatalogInternalController.CourseReference("course-ielts", "IELTS", "ACTIVE")));
        verifyNoInteractions(db);
    }

    @Test void rejectsInvalidCourseStatus() {
        assertThrows(ResponseStatusException.class, () ->
            subject.project("internal-key-test",
                new CatalogInternalController.CourseReference("course-1", "Test", "DELETED")));
        verifyNoInteractions(db);
    }

    @Test void acceptsValidIdempotentProjection() {
        subject.project("internal-key-test",
            new CatalogInternalController.CourseReference("course-ielts", "IELTS", "ACTIVE"));
        verify(db).update(
            Mockito.contains("ON CONFLICT (course_id) DO UPDATE"),
            Mockito.eq("course-ielts"), Mockito.eq("IELTS"), Mockito.eq("ACTIVE"));
    }
}
