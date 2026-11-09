package vn.iig.reporting;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;

class StatisticsQueryTest {
    @Test void canonicalizesMonthAndDay() {
        assertEquals("MONTH:2026-11", StatisticsQuery.of("month",2026,11,null).key());
        assertEquals("DAY:2026-11-09", StatisticsQuery.of(
            "DAY", 2020, null, LocalDate.of(2026,11,9)).key());
        assertEquals("YEAR:2026", StatisticsQuery.of("year",2026,null,null).key());
    }

    @Test void rejectsInvalidDatesAndPeriods() {
        assertThrows(ResponseStatusException.class,
            () -> StatisticsQuery.of("MONTH", 2026, 13, null));
        assertThrows(ResponseStatusException.class,
            () -> StatisticsQuery.of("YEAR", 0, null, null));
        assertThrows(ResponseStatusException.class,
            () -> StatisticsQuery.of("WEEK", 2026, null, null));
    }
}
