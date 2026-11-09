package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

class StandaloneStaffEditExecutorTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObjectMapper json=new ObjectMapper();
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AssignmentProjectionRepository projection=mock(AssignmentProjectionRepository.class);
    private final AssignmentWriteConflictGuard conflicts=mock(AssignmentWriteConflictGuard.class);
    private final AssignmentDomainOutboxRepository outbox=mock(AssignmentDomainOutboxRepository.class);
    private final AssignmentCommandLifecycleService lifecycle=mock(AssignmentCommandLifecycleService.class);
    private final StandaloneStaffEditExecutor executor=new StandaloneStaffEditExecutor(
        db,json,core,projection,conflicts,outbox,lifecycle);

    private final String request="""
        {"operation":"EDIT_STAFF","targetId":"shift-1","payload":{
          "reservationId":"lease-2","date":"2027-02-12",
          "startTime":"13:00","endTime":"15:00",
          "workType":"FRONT_DESK","workplaceId":"center-02"}}
        """;

    @SuppressWarnings({"rawtypes","unchecked"})
    private void command(String state,String payload) throws Exception {
        when(db.query(startsWith("SELECT state,payload::text"),
            any(RowMapper.class),eq("cm001"),eq("edit-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(state);
                when(rs.getString(2)).thenReturn(payload);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void shift(String classId,String status) throws Exception {
        when(db.query(startsWith("SELECT s.version,s.deleted,s.employee_id"),
            any(RowMapper.class),eq("shift-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getLong(1)).thenReturn(1L);
                when(rs.getBoolean(2)).thenReturn(false);
                when(rs.getString(3)).thenReturn("cs-001");
                when(rs.getString(4)).thenReturn("CS");
                when(rs.getString(5)).thenReturn(classId);
                when(rs.getString(6)).thenReturn(status);
                when(rs.getString(7)).thenReturn("{\"id\":\"shift-1\",\"employeeName\":\"Old Name\"}");
                return List.of(mapper.mapRow(rs,0));
            });
    }

    private void ownedAndConfirmed() {
        when(db.queryForObject(contains("FROM scheduling_assignment_domain_outbox o"),
            eq(Long.class),eq("shift-1"))).thenReturn(1L);
        when(db.queryForObject(contains("AND revision=?"),
            eq(Long.class),eq("shift-1"),eq(1L))).thenReturn(1L);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void approvedLease() throws Exception {
        when(db.query(startsWith("SELECT a.full_name FROM scheduling_availability_reservations"),
            any(RowMapper.class),eq("lease-2"),eq("cs-001"),
            eq("cs-001"),eq("CS"),eq("cs-001"),
            eq(LocalDate.of(2027,2,12)),
            eq(LocalTime.of(13,0)),eq(LocalTime.of(15,0))))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("Current Employee Name");
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void editedSlotIsCommittedWithNewVersionAndReferenceEvent()
            throws Exception {
        command("STAGED",request);
        shift(null,"ASSIGNED");
        ownedAndConfirmed();
        approvedLease();
        when(db.update(startsWith("DELETE FROM scheduling_availability_reservations"),
            eq("lease-2"),eq("cs-001"))).thenReturn(1);

        var result=executor.edit("cm001","cm-1","CM","edit-1");
        assertEquals("shift-1",result.scheduleId());
        assertEquals(2,result.revision());
        assertEquals("PENDING_REFERENCE",result.state());

        var saved=ArgumentCaptor.forClass(AssignmentProjectionRepository.AssignmentSnapshot.class);
        verify(projection).project(saved.capture());
        assertEquals("cs-001",saved.getValue().employeeId());
        assertEquals("Current Employee Name",saved.getValue().employeeName());
        assertEquals(LocalTime.of(13,0),saved.getValue().startTime());
        assertEquals("center-02",saved.getValue().workplaceId());

        var event=ArgumentCaptor.forClass(JsonNode.class);
        verify(outbox).append(eq("cm001"),eq("EDIT_STAFF"),eq("edit-1"),
            eq("STAFF"),eq("shift-1"),eq(2L),eq("UPSERT"),event.capture());
        assertEquals("ASSIGNED",event.getValue().path("status").asText());
        assertEquals("cm-1",event.getValue().path("assignedBy").asText());
        verify(conflicts).requireUnoccupied(any());
        verify(lifecycle).markLocallyApplied("cm001","EDIT_STAFF","edit-1");
    }

    @Test void cannotEditLegacyCoreShiftEvenIfItsReadModelLooksIdentical()
            throws Exception {
        command("STAGED",request);
        shift(null,"ASSIGNED");
        when(db.queryForObject(contains("FROM scheduling_assignment_domain_outbox o"),
            eq(Long.class),eq("shift-1"))).thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->executor.edit("cm001","cm-1","CM","edit-1")).getStatusCode());
        verify(projection,never()).project(any());
        verifyNoInteractions(outbox,lifecycle);
    }

    @Test void cannotEditClassCoverageOrReassignAnotherEmployee() throws Exception {
        command("STAGED",request);
        shift("class-01","ASSIGNED");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->executor.edit("cm001","cm-1","CM","edit-1")).getStatusCode());

        String reassignment=request.replace(
            "\"reservationId\":\"lease-2\"",
            "\"employeeId\":\"different\",\"reservationId\":\"lease-2\"");
        reset(db);
        command("STAGED",reassignment);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->executor.edit("cm001","cm-1","CM","edit-1")).getStatusCode());
        verify(projection,never()).project(any());
    }

    @Test void expiredLeaseBlocksEditBeforeAnyDomainWrite() throws Exception {
        command("STAGED",request);
        shift(null,"ASSIGNED");
        ownedAndConfirmed();
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->executor.edit("cm001","cm-1","CM","edit-1")).getStatusCode());
        verify(projection,never()).project(any());
    }

    @Test void networkRetryReturnsPreviouslyCommittedLocalEditWithoutApplyingTwice()
            throws Exception {
        command("PENDING_REFERENCE",request);
        when(db.query(startsWith("SELECT schedule_id,revision"),
            any(RowMapper.class),eq("cm001"),eq("edit-1")))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("shift-1");
                when(rs.getLong(2)).thenReturn(2L);
                return List.of(mapper.mapRow(rs,0));
            });
        var result=executor.edit("cm001","cm-1","CM","edit-1");
        assertEquals("PENDING_REFERENCE",result.state());
        assertEquals("shift-1",result.scheduleId());
        verify(projection,never()).project(any());
        verifyNoInteractions(outbox,lifecycle,conflicts);
    }

    @Test void nonManagerCannotEvenInspectSchedulingCommands() {
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->executor.edit("tc001","tc-1","TC","edit-1")).getStatusCode());
        verifyNoInteractions(core,db,projection,outbox,lifecycle);
    }
}
