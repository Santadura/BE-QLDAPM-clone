package vn.iig.hr;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import vn.iig.hr.SalaryRankModels.RankState;

class HrSelfSalaryControllerTest {
    private final HrCoreClient core=mock(HrCoreClient.class);
    private final SalaryRankRepository ranks=mock(SalaryRankRepository.class);
    private final HrSelfSalaryController endpoint=new HrSelfSalaryController(core,ranks);
    @Test void sameCurrentEmployeeRankIdButDefinitionComesFromHR() {
        var day=LocalDate.of(2026,10,1);
        when(core.currentSalaryAssignment("Bearer worker"))
            .thenReturn(new HrCoreClient.SelfSalaryAssignment("rank-1",day));
        when(ranks.imported()).thenReturn(true);
        when(ranks.get("rank-1")).thenReturn(new RankState("rank-1","TEACHER",
            "Level 1",new BigDecimal("175000"),"ACTIVE",Instant.now(),2));
        var out=endpoint.current("Bearer worker");
        assertEquals("rank-1",out.salaryRankId());
        assertEquals("Level 1",out.salaryRank());
        assertEquals(new BigDecimal("175000"),out.amount());
        assertEquals(day,out.reviewedDate());
    }
    @Test void missingHRImportNeverSilentlyReturnsCoreRate() {
        when(core.currentSalaryAssignment("Bearer worker"))
            .thenReturn(new HrCoreClient.SelfSalaryAssignment("rank-1",null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->endpoint.current("Bearer worker"))
            .getStatusCode());
        verify(ranks,never()).get(anyString());
    }
    @Test void accountWithoutSalaryRankKeepsLegacyNullFields() {
        when(core.currentSalaryAssignment("Bearer admin"))
            .thenReturn(new HrCoreClient.SelfSalaryAssignment(null,null));
        when(ranks.imported()).thenReturn(true);
        var out=endpoint.current("Bearer admin");
        assertNull(out.salaryRankId());assertNull(out.salaryRank());
        assertNull(out.amount());assertNull(out.reviewedDate());
        verify(ranks,never()).get(anyString());
    }
}
