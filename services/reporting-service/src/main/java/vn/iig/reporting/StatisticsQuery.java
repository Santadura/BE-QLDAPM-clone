package vn.iig.reporting;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Canonical cache keys preserve the existing DAY/MONTH/YEAR date semantics. */
public record StatisticsQuery(
    String period, Integer year, Integer month, LocalDate date, String key) {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public static StatisticsQuery of(String rawPeriod, Integer requestedYear,
                                      Integer requestedMonth, LocalDate requestedDate) {
        String period = rawPeriod == null ? "YEAR" : rawPeriod.toUpperCase(Locale.ROOT);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        int year = requestedYear == null ? today.getYear() : requestedYear;
        if (year < 1 || year > 9999) throw badRequest("Year must be between 1 and 9999");

        return switch(period) {
            case "DAY" -> {
                LocalDate date = requestedDate == null ? today : requestedDate;
                yield new StatisticsQuery("DAY", date.getYear(), null, date,
                    "DAY:" + date);
            }
            case "MONTH" -> {
                int month = requestedMonth == null ? today.getMonthValue() : requestedMonth;
                if (month < 1 || month > 12) throw badRequest("Month must be between 1 and 12");
                YearMonth selected = YearMonth.of(year, month);
                yield new StatisticsQuery("MONTH", year, month, null,
                    "MONTH:" + selected);
            }
            case "YEAR" -> new StatisticsQuery("YEAR", year, null, null, "YEAR:" + year);
            default -> throw badRequest("Period must be DAY, MONTH, or YEAR");
        };
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
