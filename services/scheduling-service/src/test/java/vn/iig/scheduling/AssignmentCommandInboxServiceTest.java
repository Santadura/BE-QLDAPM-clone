package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class AssignmentCommandInboxServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObjectMapper mapper=new ObjectMapper();
    private final AssignmentCommandInboxService service=
        new AssignmentCommandInboxService(db,mapper);

    private AssignmentCommandInboxService.Request teacher(String json,String key)
            throws Exception {
        return new AssignmentCommandInboxService.Request("ASSIGN_TEACHER",
            null,key,mapper.readTree(json));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void stored(String actor,String operation,String key,String state,String hash)
            throws Exception {
        when(db.query(startsWith("SELECT state,request_hash"),
                any(RowMapper.class),eq(actor),eq(operation),eq(key)))
            .thenAnswer(invocation -> {
                RowMapper rowMapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(state);
                when(rs.getString(2)).thenReturn(hash);
                return List.of(rowMapper.mapRow(rs,0));
            });
    }

    @Test void stageIsIdempotentAndCanonicalAcrossJsonPropertyOrder() throws Exception {
        var first=teacher("{\"classId\":\"c1\",\"teacherId\":\"t1\"}","cmd-1");
        var reordered=teacher("{\"teacherId\":\"t1\",\"classId\":\"c1\"}","cmd-1");
        when(db.update(startsWith("INSERT INTO scheduling_assignment_command_inbox"),
            eq("tc001"),eq("ASSIGN_TEACHER"),eq("cmd-1"),anyString(),anyString()))
            .thenReturn(1,0);
        var staged=service.stage("tc001","TC",first);
        stored("tc001","ASSIGN_TEACHER","cmd-1","STAGED",staged.requestHash());
        var replay=service.stage("tc001","TC",reordered);
        assertEquals(staged,replay);
        assertEquals("STAGED",replay.state());
    }

    @Test void reusedKeyWithChangedBodyReturnsConflictAndNoNewCommand() throws Exception {
        var first=teacher("{\"teacherId\":\"t1\"}","cmd-2");
        var changed=teacher("{\"teacherId\":\"t2\"}","cmd-2");
        when(db.update(startsWith("INSERT INTO scheduling_assignment_command_inbox"),
            eq("tc001"),eq("ASSIGN_TEACHER"),eq("cmd-2"),anyString(),anyString()))
            .thenReturn(1,0);
        var receipt=service.stage("tc001","TC",first);
        stored("tc001","ASSIGN_TEACHER","cmd-2","STAGED",receipt.requestHash());
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.stage("tc001","TC",changed)).getStatusCode());
    }

    @Test void wrongRoleAndMalformedKeyNeverHitDatabase() throws Exception {
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->service.stage("cm001","CM",
                    teacher("{\"teacherId\":\"t1\"}","cmd-3"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.stage("tc001","TC",
                    teacher("{\"teacherId\":\"t1\"}","unsafe key"))).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void cancelRequiresTargetAndDoesNotExecuteAnyBusinessWrite() {
        var bad=new AssignmentCommandInboxService.Request("CANCEL_STAFF",
            null,"cancel-1",null);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.stage("cm001","CM",bad)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void stagingIsNeverConfusedWithCommittedAssignment() throws Exception {
        when(db.update(startsWith("INSERT INTO scheduling_assignment_command_inbox"),
            eq("tc001"),eq("ASSIGN_TEACHER"),eq("safe-key"),anyString(),anyString()))
            .thenReturn(1);
        var receipt=service.stage("tc001","TC",teacher("{\"teacherId\":\"t1\"}","safe-key"));
        assertEquals("STAGED",receipt.state());
        verify(db,never()).update(contains("scheduling_assignment_write_shadow"),
            any(Object[].class));
    }
}
