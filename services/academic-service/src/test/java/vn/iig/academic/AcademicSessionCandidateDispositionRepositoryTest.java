package vn.iig.academic;

import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademicSessionCandidateDispositionRepositoryTest {
    private static final String CMD="b381ee38-d42f-48e7-bbf8-24198e329503";
    private static final String HASH="b".repeat(64);
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final AcademicSessionCandidateRepository candidates=
        mock(AcademicSessionCandidateRepository.class);
    final AcademicSessionCandidateDispositionRepository repo=
        new AcademicSessionCandidateDispositionRepository(db,candidates);

    void completeEvidence() {
        when(candidates.committedEvidence("admin","key-1",CMD)).thenReturn(
            new AcademicSessionCandidateRepository.Evidence(
                "admin","key-1","a".repeat(64),"class-1","lesson-1",
                5,3,4,5,6,CMD,HASH));
    }

    void disposeSetup(String actor,String key,String digest,String by)
            throws Exception {
        completeEvidence();
        when(db.queryForObject(anyString(),eq(Long.class),eq(CMD),
            eq("admin"),eq("key-1"),eq(HASH))).thenReturn(1L);
        when(db.update(anyString(),eq(CMD),eq("admin"),eq("key-1"),
            eq(HASH),eq("admin"))).thenReturn(1);
        ResultSet rs=mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(actor);
        when(rs.getString(2)).thenReturn(key);
        when(rs.getString(3)).thenReturn(digest);
        when(rs.getString(4)).thenReturn(by);
        when(rs.getString(5)).thenReturn("REHEARSAL_DISCARDED");
        when(db.query(anyString(),any(RowMapper.class),eq(CMD)))
            .thenAnswer(call->{
                @SuppressWarnings("unchecked")
                RowMapper<Object> mapper=call.getArgument(1);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void exactCommittedSessionAndHandoffCanBeDisposed() throws Exception {
        disposeSetup("admin","key-1",HASH,"admin");
        var result=repo.discard("admin","key-1",CMD,"admin");
        assertEquals("REHEARSAL_DISCARDED",result.disposition());
        assertEquals(CMD,result.commandId());
        verify(candidates).committedEvidence("admin","key-1",CMD);
    }

    @Test void missingCoreHandoffCannotForgeDisposition() {
        completeEvidence();
        when(db.queryForObject(anyString(),eq(Long.class),eq(CMD),
            eq("admin"),eq("key-1"),eq(HASH))).thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.discard("admin","key-1",CMD,"admin")).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void replayWithWrongActorOrHashCannotClaimExistingAudit()
            throws Exception {
        disposeSetup("another-admin","key-1",HASH,"admin");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.discard("admin","key-1",CMD,"admin")).getStatusCode());
        reset(db);
        disposeSetup("admin","key-1","c".repeat(64),"admin");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.discard("admin","key-1",CMD,"admin")).getStatusCode());
    }

    @Test void noCompleteOutboxCannotBeDisposed() {
        when(candidates.committedEvidence("admin","key-1",CMD)).thenThrow(
            new ResponseStatusException(HttpStatus.CONFLICT,"Missing canonical outbox"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.discard("admin","key-1",CMD,"admin")).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void malformedCommandNeverQueriesCoreOrAcademic() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->repo.discard("admin","key-1","wrong-id","admin")).getStatusCode());
        verifyNoInteractions(db,candidates);
    }

    @Test void divergentOrMissingDispositionSummaryFailsClosed() {
        when(db.queryForObject(anyString(),any(RowMapper.class))).thenReturn(
            new AcademicSessionCandidateDispositionRepository.Summary(1,1,0,0,1));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,repo::summary).getStatusCode());
    }
}
