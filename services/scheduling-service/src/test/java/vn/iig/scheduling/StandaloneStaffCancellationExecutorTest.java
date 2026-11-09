package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class StandaloneStaffCancellationExecutorTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AssignmentProjectionRepository projection=mock(AssignmentProjectionRepository.class);
    private final AssignmentDomainOutboxRepository outbox=mock(AssignmentDomainOutboxRepository.class);
    private final AssignmentCommandLifecycleService lifecycle=mock(AssignmentCommandLifecycleService.class);
    private final StandaloneStaffCancellationExecutor service=
        new StandaloneStaffCancellationExecutor(db,new ObjectMapper(),core,
            projection,outbox,lifecycle);

    private final String persisted=("{\"id\":\"shift-1\"," +
        "\"employeeId\":\"cs-001\",\"employeeName\":\"CS One\"," +
        "\"staffRole\":\"CS\",\"status\":\"ASSIGNED\"," +
        "\"startTime\":\"09:00\",\"endTime\":\"11:00\"}");

    @SuppressWarnings({"rawtypes","unchecked"})
    private void staged(String state) throws Exception {
        when(db.query(contains("SELECT state,payload::text"),
                any(RowMapper.class),eq("cm001"),eq("cancel-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(state);
                when(rs.getString(2)).thenReturn(
                    "{\"operation\":\"CANCEL_STAFF\",\"targetId\":\"shift-1\",\"payload\":null}");
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void existingShift(String classId,String status) throws Exception {
        when(db.query(contains("FROM scheduling_assignment_write_shadow s"),
                any(RowMapper.class),eq("shift-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getLong(1)).thenReturn(1L);
                when(rs.getBoolean(2)).thenReturn(false);
                when(rs.getString(3)).thenReturn("cs-001");
                when(rs.getString(4)).thenReturn("CS");
                when(rs.getString(5)).thenReturn(classId);
                when(rs.getString(6)).thenReturn(null);
                when(rs.getObject(7,LocalDate.class))
                    .thenReturn(LocalDate.of(2027,2,11));
                when(rs.getObject(8,LocalTime.class))
                    .thenReturn(LocalTime.of(9,0));
                when(rs.getObject(9,LocalTime.class))
                    .thenReturn(LocalTime.of(11,0));
                when(rs.getString(10)).thenReturn("FRONT_DESK");
                when(rs.getString(11)).thenReturn(status);
                when(rs.getString(12)).thenReturn(persisted);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void cancelledStandaloneShiftHasNextVersionAndAnImmutableCoreEvent()
            throws Exception {
        staged("STAGED");
        existingShift(null,"ASSIGNED");
        var result=service.cancel("cm001","cm-1","CM","cancel-1");
        assertEquals("shift-1",result.scheduleId());
        assertEquals(2,result.revision());
        assertEquals("PENDING_REFERENCE",result.state());
        var snapshot=ArgumentCaptor.forClass(AssignmentProjectionRepository.AssignmentSnapshot.class);
        verify(projection).project(snapshot.capture());
        assertEquals("CANCELLED",snapshot.getValue().status());
        assertEquals("CS One",snapshot.getValue().employeeName());
        assertEquals(2,snapshot.getValue().version());
        assertFalse(snapshot.getValue().deleted());
        var event=ArgumentCaptor.forClass(JsonNode.class);
        verify(outbox).append(eq("cm001"),eq("CANCEL_STAFF"),eq("cancel-1"),
            eq("STAFF"),eq("shift-1"),eq(2L),eq("UPSERT"),event.capture());
        assertEquals("CANCELLED",event.getValue().path("status").asText());
        assertEquals("cm-1",event.getValue().path("assignedBy").asText());
        var order=inOrder(projection,outbox,lifecycle);
        order.verify(projection).project(any());
        order.verify(outbox).append(anyString(),anyString(),anyString(),anyString(),
            anyString(),anyLong(),anyString(),any());
        order.verify(lifecycle).markLocallyApplied("cm001","CANCEL_STAFF","cancel-1");
    }

    @Test void repeatedCancelDoesNotWriteAnotherRevision() throws Exception {
        staged("COMMITTED");
        when(db.query(startsWith("SELECT schedule_id,revision"),
            any(RowMapper.class),eq("cm001"),eq("cancel-1")))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("shift-1");
                when(rs.getLong(2)).thenReturn(2L);
                return List.of(mapper.mapRow(rs,0));
            });
        var result=service.cancel("cm001","cm-1","CM","cancel-1");
        assertEquals("shift-1",result.scheduleId());
        assertEquals("COMMITTED",result.state());
        verifyNoInteractions(outbox,lifecycle);
        verify(projection,never()).project(any());
    }

    @Test void cannotCancelClassSupportShift() throws Exception {
        staged("STAGED");
        existingShift("class-1","ASSIGNED");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.cancel("cm001","cm-1","CM","cancel-1")).getStatusCode());
        verifyNoInteractions(outbox,lifecycle);
    }

    @Test void cannotCancelPreviouslyCancelledShiftAsNewCommand() throws Exception {
        staged("STAGED");
        existingShift(null,"CANCELLED");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.cancel("cm001","cm-1","CM","cancel-1")).getStatusCode());
        verifyNoInteractions(outbox,lifecycle);
    }

    @Test void unauthenticatedOrWrongRoleNeverReadsSchedulingDb() {
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->service.cancel("tc001","tc-1","TC","cancel-1")).getStatusCode());
        verifyNoInteractions(core,db,projection,outbox,lifecycle);
    }
}
