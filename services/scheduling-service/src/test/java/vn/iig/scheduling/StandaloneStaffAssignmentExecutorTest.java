package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class StandaloneStaffAssignmentExecutorTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObjectMapper mapper=new ObjectMapper();
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AssignmentProjectionRepository projection=mock(AssignmentProjectionRepository.class);
    private final AssignmentWriteConflictGuard conflicts=mock(AssignmentWriteConflictGuard.class);
    private final AssignmentDomainOutboxRepository outbox=mock(AssignmentDomainOutboxRepository.class);
    private final AssignmentCommandLifecycleService lifecycle=mock(AssignmentCommandLifecycleService.class);
    private final StandaloneStaffAssignmentExecutor service=
        new StandaloneStaffAssignmentExecutor(db,mapper,core,projection,conflicts,
            outbox,lifecycle);

    private String request(String extra) {
        return "{\"operation\":\"ASSIGN_STAFF\",\"targetId\":null,\"payload\":{"+
            "\"employeeId\":\"cs-001\",\"staffRole\":\"CS\","+
            "\"date\":\"2027-02-11\",\"startTime\":\"09:00\","+
            "\"endTime\":\"11:00\",\"workType\":\"FRONT_DESK\","+
            "\"reservationId\":\"lease-1\""+extra+"}}";
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void staged(String state,String payload) throws Exception {
        when(db.query(contains("SELECT state,payload::text"),
                any(RowMapper.class),eq("cm001"),eq("cmd-1")))
            .thenAnswer(invocation -> {
                RowMapper rowMapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(state);
                when(rs.getString(2)).thenReturn(payload);
                return List.of(rowMapper.mapRow(rs,0));
            });
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void approvedLease() throws Exception {
        when(db.query(contains("SELECT a.full_name,a.role_id"),
                any(RowMapper.class),eq("lease-1"),eq("cs-001"),eq("cs-001"),
                eq("CS"),eq("cs-001"),eq(LocalDate.of(2027,2,11)),
                eq(LocalTime.of(9,0)),eq(LocalTime.of(11,0))))
            .thenAnswer(invocation -> {
                RowMapper rowMapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("Nguyen CS");
                when(rs.getString(2)).thenReturn("CS");
                return List.of(rowMapper.mapRow(rs,0));
            });
    }

    @Test void standaloneApprovedShiftWritesProjectionEventAndReceiptInOrder()
            throws Exception {
        staged("STAGED",request(""));
        approvedLease();
        when(db.update(startsWith("DELETE FROM scheduling_availability_reservations"),
            eq("lease-1"),eq("cs-001"))).thenReturn(1);

        var result=service.execute("cm001","cm-001","CM","cmd-1");
        assertEquals("PENDING_REFERENCE",result.state());
        assertEquals(1,result.revision());
        assertNotNull(result.scheduleId());

        var snapshot=ArgumentCaptor.forClass(AssignmentProjectionRepository.AssignmentSnapshot.class);
        verify(projection).project(snapshot.capture());
        var data=snapshot.getValue();
        assertEquals(result.scheduleId(),data.id());
        assertEquals("Nguyen CS",data.employeeName());
        assertEquals("STAFF",data.sourceType());
        assertEquals("CS",data.staffRole());
        assertNull(data.classId());
        assertEquals("cm-001",data.assignedBy());

        var event=ArgumentCaptor.forClass(JsonNode.class);
        verify(outbox).append(eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-1"),
            eq("STAFF"),eq(result.scheduleId()),eq(1L),eq("UPSERT"),event.capture());
        assertEquals("cm-001",event.getValue().path("assignedBy").asText());
        assertTrue(event.getValue().path("classId").isNull());
        assertEquals("ASSIGNED",event.getValue().path("status").asText());

        var order=inOrder(projection,outbox,lifecycle,db);
        order.verify(projection).project(any());
        order.verify(outbox).append(anyString(),anyString(),anyString(),
            anyString(),anyString(),anyLong(),anyString(),any());
        order.verify(lifecycle).markLocallyApplied("cm001","ASSIGN_STAFF","cmd-1");
        order.verify(db).update(startsWith("DELETE FROM scheduling_availability_reservations"),
            eq("lease-1"),eq("cs-001"));
        verify(core).requireCoreWriterQuiesced();
        verify(core).acquireAssignmentWriterClaim(
            "cm001","cm-001","ASSIGN_STAFF","cmd-1");
        verify(conflicts).requireUnoccupied(any());
    }

    @Test void coreMustBeQuiescedBeforeAnySchedulingSql() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"Core is active"))
            .when(core).requireCoreWriterQuiesced();
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.execute("cm001","cm-001","CM","cmd-1")).getStatusCode());
        verifyNoInteractions(db,projection,outbox,lifecycle,conflicts);
    }

    @Test void activeCmIsRequiredEvenWhenCommandKeyExists() {
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->service.execute("tc001","tc-001","TC","cmd-1")).getStatusCode());
        verifyNoInteractions(core,db,outbox,lifecycle);
    }

    @Test void classLinkedShiftIsRejectedBeforeAnyBusinessWrites() throws Exception {
        staged("STAGED",request(",\"classId\":\"class-01\""));
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.execute("cm001","cm-001","CM","cmd-1")).getStatusCode());
        verifyNoInteractions(outbox,lifecycle,conflicts);
        verify(projection,never()).project(any());
    }

    @Test void workerAvailabilityMustStillBeLiveAtExecutionTime() throws Exception {
        staged("STAGED",request(""));
        // No approved availability lease row is returned.
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.execute("cm001","cm-001","CM","cmd-1")).getStatusCode());
        verifyNoInteractions(outbox,lifecycle,conflicts);
        verify(projection,never()).project(any());
    }

    @Test void aCoreHandbackRaceCannotReachLocalAssignmentMutation()
            throws Exception {
        staged("STAGED",request(""));
        approvedLease();
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,
            "Core writer mode no longer quiesced"))
            .when(core).acquireAssignmentWriterClaim(
                "cm001","cm-001","ASSIGN_STAFF","cmd-1");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.execute("cm001","cm-001","CM","cmd-1"))
                .getStatusCode());
        verify(projection,never()).project(any());
        verifyNoInteractions(outbox,lifecycle);
    }

    @Test void expiredLeaseAtCommitRollsBackInsteadOfReturningSuccess()
            throws Exception {
        staged("STAGED",request(""));
        approvedLease();
        when(db.update(startsWith("DELETE FROM scheduling_availability_reservations"),
            eq("lease-1"),eq("cs-001"))).thenReturn(0);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.execute("cm001","cm-001","CM","cmd-1")).getStatusCode());
        // The actual @Transactional Spring proxy rolls back all preceding writes.
        verify(outbox).append(anyString(),anyString(),anyString(),anyString(),
            anyString(),anyLong(),anyString(),any());
    }

    @Test void retryOfAlreadyAppliedLocalCommandReturnsSameScheduleId()
            throws Exception {
        staged("PENDING_REFERENCE",request(""));
        when(db.query(startsWith("SELECT schedule_id,revision"),
            any(RowMapper.class),eq("cm001"),eq("cmd-1")))
            .thenAnswer(invocation -> {
                RowMapper<?> rowMapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("shift-saved");
                when(rs.getLong(2)).thenReturn(1L);
                return List.of(rowMapper.mapRow(rs,0));
            });
        var result=service.execute("cm001","cm-001","CM","cmd-1");
        assertEquals("shift-saved",result.scheduleId());
        assertEquals("PENDING_REFERENCE",result.state());
        verify(projection,never()).project(any());
        verifyNoInteractions(outbox,lifecycle,conflicts);
    }

    @Test void rejectedOrCompensatingCommandCannotExecute() throws Exception {
        staged("COMPENSATING",request(""));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.execute("cm001","cm-001","CM","cmd-1")).getStatusCode());
        verify(projection,never()).project(any());
    }
}
