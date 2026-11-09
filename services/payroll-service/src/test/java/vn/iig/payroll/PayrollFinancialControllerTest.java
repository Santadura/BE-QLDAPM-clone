package vn.iig.payroll;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PayrollFinancialControllerTest {
    private final PayrollRepository ledger=mock(PayrollRepository.class);
    private final PayrollFinancialController subject=
        new PayrollFinancialController(ledger,"internal-reporting-key");

    @Test void internalMonthlyCostsAreNeverPublicWithoutKey() {
        assertThrows(ResponseStatusException.class,
            ()->subject.costs(null,"2026-01","2026-12"));
        verifyNoInteractions(ledger);
    }

    @Test void rejectsReversedPeriodsAndVeryLargeQueries() {
        assertThrows(ResponseStatusException.class,
            ()->subject.costs("internal-reporting-key","2026-12","2026-01"));
        assertThrows(ResponseStatusException.class,
            ()->subject.costs("internal-reporting-key","2026-01","2040-01"));
        verifyNoInteractions(ledger);
    }

    @Test void doesNotPublishAmountsUntilLegacyHistoryIsImported() {
        when(ledger.imported()).thenReturn(false);
        assertThrows(ResponseStatusException.class,
            ()->subject.costs("internal-reporting-key","2026-01","2026-12"));
        verify(ledger,never()).monthlyCosts(anyString(),anyString());
    }

    @Test void returnsOnlyAmountsFromPayrollLedger() {
        when(ledger.imported()).thenReturn(true);
        var amounts=List.of(new PayrollRepository.MonthCost(
            "2026-10",new BigDecimal("9500000.00"),4));
        when(ledger.monthlyCosts("2026-01","2026-12")).thenReturn(amounts);
        assertEquals(amounts,subject.costs("internal-reporting-key","2026-01","2026-12"));
    }
}
