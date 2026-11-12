package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A matching outbox digest alone cannot prove a Core handback was
 * acknowledged by the original actor/key. Cross-DB diagnosis must refuse
 * a wrong-identity receipt even when the command UUID and hash match.
 */
class AcademicRosterCandidateInspectionTest {
    private static final String COMMAND=
        "b381ee38-d42f-48e7-bbf8-24198e329503";
    private static final String HASH="b".repeat(64);

    private AcademicRosterCandidateRepository.LocalInspection inspect(
            String handoffActor,String handoffKey,
            String dispositionActor,String dispositionKey) throws Exception {
        JdbcTemplate db=mock(JdbcTemplate.class);
        AcademicRosterCandidateRepository repository=spy(
            new AcademicRosterCandidateRepository(db,new ObjectMapper()));
        var proof=new AcademicRosterCandidateRepository.Evidence(
            "admin","request-1","a".repeat(64),
            "TARGET","target-1",8,9,2,"UPSERT",COMMAND,HASH);
        doReturn(proof).when(repository)
            .committedEvidence("admin","request-1",COMMAND);

        ResultSet rs=mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(COMMAND);
        when(rs.getBoolean(2)).thenReturn(true);
        when(rs.getBoolean(3)).thenReturn(true);
        when(rs.getString(4)).thenReturn(HASH);
        when(rs.getString(5)).thenReturn(handoffActor);
        when(rs.getString(6)).thenReturn(handoffKey);
        when(rs.getBoolean(7)).thenReturn(true);
        when(rs.getString(8)).thenReturn(HASH);
        when(rs.getString(9)).thenReturn(dispositionActor);
        when(rs.getString(10)).thenReturn(dispositionKey);

        when(db.query(anyString(),any(RowMapper.class),
                eq("admin"),eq("request-1"))).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper=call.getArgument(1);
            return List.of(mapper.mapRow(rs,0));
        });
        return repository.inspectCandidate("admin","request-1");
    }

    @Test void exactActorAndKeyOnBothImmutableReceiptsPass() throws Exception {
        var x=inspect("admin","request-1","admin","request-1");
        assertNotNull(x);
        assertTrue(x.handoffMatches());
        assertTrue(x.dispositionMatches());
    }

    @Test void wrongActorCannotForgeLocalReceiptForSameCommandAndDigest()
            throws Exception {
        var x=inspect("other-admin","request-1","admin","request-1");
        assertTrue(x.hasHandoffReceipt());
        assertFalse(x.handoffMatches());
        assertTrue(x.dispositionMatches());
    }

    @Test void wrongDispositionKeyCannotBeCountedAsAuditedClosure()
            throws Exception {
        var x=inspect("admin","request-1","admin","another-key");
        assertTrue(x.handoffMatches());
        assertTrue(x.hasDisposition());
        assertFalse(x.dispositionMatches());
    }

    @Test void wrongHandoffKeyAndDispositionActorBothFailClosed()
            throws Exception {
        var x=inspect("admin","wrong-key","other-admin","request-1");
        assertFalse(x.handoffMatches());
        assertFalse(x.dispositionMatches());
    }
}
