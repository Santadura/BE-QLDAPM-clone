package vn.iig.hr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.hr.SalaryRankModels.*;

class SalaryRankServiceTest {
    private final SalaryRankRepository repository=mock(SalaryRankRepository.class);
    private final HrCoreClient core=mock(HrCoreClient.class);
    private final SalaryRankService service=new SalaryRankService(repository,core);
    private final RankState teacherRank=new RankState("rank-1","TEACHER","Grade 1",
        new BigDecimal("200000"),"ACTIVE",Instant.parse("2026-11-09T00:00:00Z"),1);
    private final RankState csRank=new RankState("rank-2","CS","Grade 1",
        new BigDecimal("150000"),"ACTIVE",Instant.parse("2026-11-09T00:00:00Z"),1);

    @Test void teacherCannotReadPayrollRanks() {
        when(core.currentRole("teacher")).thenReturn("TEACHER");
        when(repository.imported()).thenReturn(true);
        assertThrows(ResponseStatusException.class,()->service.list("teacher",null));
        verify(repository,never()).all();
    }

    @Test void coordinatorCanOnlyReadTeacherRanks() {
        when(core.currentRole("tc")).thenReturn("TC");
        when(repository.imported()).thenReturn(true);
        when(repository.all()).thenReturn(List.of(teacherRank,csRank));
        assertEquals(List.of(teacherRank.publicView()),service.list("tc",null));
        assertThrows(ResponseStatusException.class,()->service.list("tc","CS"));
    }

    @Test void nonAdminCannotCreateRanks() {
        when(core.currentRole("tc")).thenReturn("TC");
        assertThrows(ResponseStatusException.class,()->service.create(
            new RankRequest("TEACHER","Grade 2",BigDecimal.ONE,"ACTIVE"),"tc"));
        verifyNoInteractions(repository);
    }

    @Test void initializesFromLegacyAndAcknowledgesVersion() {
        when(repository.imported()).thenReturn(false,true);
        when(core.exportLegacySalaryRanks()).thenReturn(List.of(teacherRank));
        when(repository.pending(100)).thenReturn(
            List.of(new SalaryRankRepository.Pending(teacherRank,1)));
        service.synchronize();
        verify(repository).importInitial(List.of(teacherRank));
        verify(core).projectSalaryRank(teacherRank);
        verify(repository).acknowledge("rank-1",1L);
    }

    @Test void failedCoreProjectionMustRemainPending() {
        when(repository.imported()).thenReturn(true);
        when(repository.pending(100)).thenReturn(
            List.of(new SalaryRankRepository.Pending(teacherRank,2)));
        doThrow(new IllegalStateException("outage")).when(core).projectSalaryRank(teacherRank);
        service.synchronize();
        verify(repository,never()).acknowledge(anyString(),anyLong());
    }
}
