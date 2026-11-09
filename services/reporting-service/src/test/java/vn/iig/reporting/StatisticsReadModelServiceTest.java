package vn.iig.reporting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StatisticsReadModelServiceTest {
    private final SnapshotRepository repository = mock(SnapshotRepository.class);
    private final ReportingCoreClient core = mock(ReportingCoreClient.class);
    private final ReportingPayrollClient payroll = mock(ReportingPayrollClient.class);
    private final StatisticsReadModelService service = new StatisticsReadModelService(
        repository, core, payroll, new ObjectMapper(), 30);
    private final StatisticsQuery q = StatisticsQuery.of("YEAR", 2026, null, null);

    @Test void readsFreshCacheButStillChecksCurrentAdminPermission() {
        var snapshot = new SnapshotRepository.Snapshot(
            "{\"period\":\"YEAR\",\"overview\":{}}", Instant.now());
        when(repository.get(q.key())).thenReturn(snapshot);
        var response = service.load(q, "Bearer valid");
        assertEquals("YEAR", response.payload().get("period").asText());
        verify(core).requireAdmin("Bearer valid");
        verify(core, never()).currentStatistics(any());
    }

    @Test void neverReturnsCacheToUnauthorizedUser() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(core)
            .requireAdmin("Bearer invalid");
        assertThrows(ResponseStatusException.class, () -> service.load(q,"Bearer invalid"));
        verifyNoInteractions(repository);
    }

    @Test void fetchesAndPersistsExpiredSnapshot() {
        var stale = new SnapshotRepository.Snapshot(
            "{\"period\":\"YEAR\",\"old\":true}", Instant.now().minusSeconds(900));
        when(repository.get(q.key())).thenReturn(stale);
        when(core.currentStatistics(q)).thenReturn("{\"period\":\"YEAR\",\"new\":true}");
        when(repository.put(q.key(), "{\"period\":\"YEAR\",\"new\":true}"))
            .thenReturn(new SnapshotRepository.Snapshot(
                "{\"period\":\"YEAR\",\"new\":true}", Instant.now()));
        var result = service.load(q,"Bearer valid");
        assertTrue(result.payload().get("new").asBoolean());
        verify(repository).put(q.key(), "{\"period\":\"YEAR\",\"new\":true}");
    }

    @Test void replacesStaleCoreFinancialDataWithPayrollLedger() {
        var month=StatisticsQuery.of("MONTH",2026,10,null);
        when(core.currentStatistics(month)).thenReturn(
            "{\"period\":\"MONTH\",\"financial\":{\"revenue\":null,\"revenueAvailable\":false,"
            + "\"salaryCost\":1,\"payrollRecords\":1},"
            + "\"monthly\":[{\"month\":10,\"label\":\"T10\",\"salaryCost\":1,\"profit\":null}]}");
        when(payroll.monthly("2026-01","2026-12")).thenReturn(java.util.List.of(
            new ReportingPayrollClient.MonthCost("2026-10",
                new java.math.BigDecimal("9500000.00"),4)));
        when(repository.put(eq(month.key()),anyString())).thenAnswer(invocation ->
            new SnapshotRepository.Snapshot(invocation.getArgument(1),Instant.now()));

        var result=service.load(month,"Bearer admin");
        assertEquals(0,new java.math.BigDecimal("9500000.00").compareTo(
            result.payload().get("financial").get("salaryCost").decimalValue()));
        assertEquals(4,result.payload().get("financial").get("payrollRecords").asInt());
        assertEquals(0,new java.math.BigDecimal("9500000.00").compareTo(
            result.payload().get("monthly").get(0).get("salaryCost").decimalValue()));
        assertTrue(result.payload().get("monthly").get(0).get("profit").isNull());
        verify(payroll).monthly("2026-01","2026-12");
    }

    @Test void failsClosedWhenPayrollFinancialSourceIsUnavailable() {
        var month=StatisticsQuery.of("YEAR",2026,null,null);
        when(core.currentStatistics(month)).thenReturn(
            "{\"period\":\"YEAR\",\"financial\":{\"salaryCost\":100},\"monthly\":[]}");
        when(payroll.monthly("2026-01","2026-12")).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(ResponseStatusException.class,()->service.load(month,"Bearer admin"));
        verify(repository,never()).put(anyString(),anyString());
    }

    @Test void refusesToInventStatisticsOnSourceOutage() {
        when(core.currentStatistics(q)).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(ResponseStatusException.class, () -> service.load(q,"Bearer valid"));
        verify(repository,never()).put(anyString(),anyString());
    }
}
