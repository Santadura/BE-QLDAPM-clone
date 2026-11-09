package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchedulingAvailabilityMatchingTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SchedulingAccess access = new SchedulingAccess(jdbc, mock(SchedulingReservationClient.class));

    private static Map<String,Object> recurring(String status, String day, String campus,
                                                 String from, String to) {
        Map<String,Object> slot = new HashMap<>();
        slot.put("available_date", null);
        slot.put("day_of_week", day);
        slot.put("availability_type", "RECURRING");
        slot.put("start_time", LocalTime.parse(from));
        slot.put("end_time", LocalTime.parse(to));
        slot.put("status", status);
        slot.put("campus", campus);
        return slot;
    }

    @Test
    void recurringTuesdayAlsoAppliesToEarlierTuesday() {
        LocalDate octoberSixth = LocalDate.of(2026,10,6);
        assertEquals("tue", access.day(octoberSixth));
        assertEquals("thu", access.day(LocalDate.of(2026,10,22)));
        when(jdbc.queryForList(anyString(), eq("teacher-003"),
                eq(octoberSixth), eq("tue"))).thenReturn(
            List.of(recurring("APPROVED", "tue", "Cơ sở chính","18:00","21:00")));
        assertTrue(access.availabilityMismatchReason(
            "teacher-003", octoberSixth, LocalTime.of(18,0),
            LocalTime.of(20,0), "Cơ sở chính").contains("Có lịch rảnh phù hợp"));
    }

    @Test
    void clearlyIdentifiesCampusMismatch() {
        LocalDate lesson = LocalDate.of(2026,10,6);
        when(jdbc.queryForList(anyString(), eq("teacher-003"),
                eq(lesson), eq("tue"))).thenReturn(
            List.of(recurring("APPROVED", "tue", "Cơ sở Quận 1","18:00","21:00")));
        String reason = access.availabilityMismatchReason(
            "teacher-003", lesson, LocalTime.of(18,0),
            LocalTime.of(20,0), "Cơ sở chính");
        assertTrue(reason.contains("khác cơ sở"));
    }

    @Test
    void distinguishesUnapprovedFromIncorrectDayOrHours() {
        LocalDate lesson = LocalDate.of(2026,10,6);
        when(jdbc.queryForList(anyString(), eq("teacher-003"),
                eq(lesson), eq("tue"))).thenReturn(
            List.of(recurring("PENDING", "tue", "Cơ sở chính","18:00","21:00")));
        assertTrue(access.availabilityMismatchReason(
            "teacher-003", lesson, LocalTime.of(18,0),
            LocalTime.of(20,0), "Cơ sở chính").contains("chưa được duyệt"));
        when(jdbc.queryForList(anyString(), eq("teacher-003"),
                eq(lesson), eq("tue"))).thenReturn(
            List.of(recurring("APPROVED", "tue", "Cơ sở chính","18:30","20:30")));
        assertTrue(access.availabilityMismatchReason(
            "teacher-003", lesson, LocalTime.of(18,0),
            LocalTime.of(20,0), "Cơ sở chính").contains("không bao phủ"));
        when(jdbc.queryForList(anyString(), eq("teacher-003"),
                eq(lesson), eq("tue"))).thenReturn(
            List.of(recurring("APPROVED", "thu", "Cơ sở chính","18:00","21:00")));
        assertTrue(access.availabilityMismatchReason(
            "teacher-003", lesson, LocalTime.of(18,0),
            LocalTime.of(20,0), "Cơ sở chính").contains("Không có lịch rảnh áp dụng"));
    }
}
