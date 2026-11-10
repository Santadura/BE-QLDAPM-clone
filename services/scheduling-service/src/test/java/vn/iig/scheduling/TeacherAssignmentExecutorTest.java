package vn.iig.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes","unchecked"})
class TeacherAssignmentExecutorTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final TeacherAssignmentPreflightService preflight=
        mock(TeacherAssignmentPreflightService.class);
    private final AcademicCommandLeaseService academic=
        mock(AcademicCommandLeaseService.class);
    private final AssignmentProjectionRepository projection=
        mock(AssignmentProjectionRepository.class);
    private final AssignmentWriteConflictGuard conflict=mock(AssignmentWriteConflictGuard.class);
    private final AssignmentDomainOutboxRepository outbox=mock(AssignmentDomainOutboxRepository.class);
    private final AssignmentCommandLifecycleService lifecycle=
        mock(AssignmentCommandLifecycleService.class);
    private final TeacherAssignmentExecutor executor=
        new TeacherAssignmentExecutor(db,new ObjectMapper(),core,preflight,
            academic,projection,conflict,outbox,lifecycle);

    private final LocalDate date=LocalDate.of(2027,2,11);
    private final LocalTime from=LocalTime.of(9,0);
    private final LocalTime until=LocalTime.of(11,0);
    private final Instant expires=Instant.now().plusSeconds(250);
    private final String payload="""
        {"operation":"ASSIGN_TEACHER","targetId":null,
         "payload":{"teacherId":"teacher-1","classId":"class-1",
         "sessionId":"lesson-1","classVersion":3,"sessionVersion":7,
         "date":"2027-02-11","startTime":"09:00","endTime":"11:00"}}""";

    private void staged(String state) throws Exception {
        when(db.query(startsWith("SELECT state,payload::text"),
            any(RowMapper.class),eq("tc001"),eq("cmd-1")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(state);
                when(rs.getString(2)).thenReturn(payload);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    private TeacherAssignmentPreflightService.Receipt receipt(String code,String name) {
        return new TeacherAssignmentPreflightService.Receipt(
            "PREFLIGHT_ONLY","cmd-1","teacher-1","class-1",
            "lesson-1",date,from,until,"lease-1",expires,
            "reservation-1",expires,code,name);
    }

    private void fullyPrepared() throws Exception {
        staged("STAGED");
        when(preflight.prepare("tc001","cmd-1")).thenReturn(receipt("IELTS-01","IELTS Class"));
        when(academic.renew("tc001","cmd-1")).thenReturn(
            new AcademicCommandLeaseService.Receipt(
                "cmd-1","class-1","lesson-1",3,7,"lease-1",expires));
        when(db.query(startsWith("SELECT a.full_name FROM scheduling_availability_reservations"),
            any(RowMapper.class),eq("reservation-1"),eq("teacher-1"),
            eq("teacher-1"),eq("teacher-1"),eq(date),eq(from),eq(until)))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn("A Teacher");
                return List.of(mapper.mapRow(rs,0));
            });
        when(db.update(startsWith("DELETE FROM scheduling_availability_reservations"),
            eq("reservation-1"),eq("teacher-1"))).thenReturn(1);
    }

    @Test void transactionalTeacherWriteStagesLocalProjectionAndImmutableReference() throws Exception {
        fullyPrepared();
        var outcome=executor.execute("tc001","tc-1","TC","cmd-1");
        assertEquals("PENDING_REFERENCE",outcome.state());
        assertEquals(1L,outcome.revision());
        assertNotNull(outcome.scheduleId());
        var order=inOrder(core,preflight,academic,projection,outbox,lifecycle);
        order.verify(core).requireCoreWriterQuiesced();
        order.verify(preflight).prepare("tc001","cmd-1");
        order.verify(core).acquireAssignmentWriterClaim("tc001","tc-1","ASSIGN_TEACHER","cmd-1");
        order.verify(academic).renew("tc001","cmd-1");
        order.verify(projection).project(argThat(s->"TEACHING".equals(s.sourceType()) &&
            "teacher-1".equals(s.employeeId()) && "IELTS-01".equals(s.classCode()) &&
            "IELTS Class".equals(s.className()) && s.version()==1));
        order.verify(outbox).append(eq("tc001"),eq("ASSIGN_TEACHER"),eq("cmd-1"),
            eq("TEACHING"),eq(outcome.scheduleId()),eq(1L),eq("UPSERT"),
            argThat(n->n.isObject() && "lease-1".equals(n.path("academicLeaseId").asText())
                && "A Teacher".equals(n.path("teacherName").asText())
                && n.path("classVersion").asLong()==3));
        order.verify(lifecycle).markLocallyApplied("tc001","ASSIGN_TEACHER","cmd-1");
    }

    @Test void alreadyPendingCommandReplaysExactlySameScheduleWithoutNewWriterClaim()
            throws Exception {
        staged("PENDING_REFERENCE");
        when(db.query(startsWith("SELECT schedule_id,revision FROM scheduling_assignment_domain_outbox"),
            any(RowMapper.class),eq("tc001"),eq("cmd-1")))
            .thenReturn(List.of(new TeacherAssignmentExecutor.Result(
                "existing-teach","PENDING_REFERENCE",1)));
        var original=executor.execute("tc001","tc-1","TC","cmd-1");
        assertEquals("existing-teach",original.scheduleId());
        verifyNoInteractions(preflight,academic,conflict,outbox,lifecycle);
        verify(projection).requireReady();
        verify(projection,never()).project(any(AssignmentProjectionRepository.AssignmentSnapshot.class));
        verify(core,never()).acquireAssignmentWriterClaim(
            anyString(),anyString(),anyString(),anyString());
    }

    @Test void aNonTcCannotStartAnIndependentTeacherWrite() {
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->executor.execute("cs001","cs-1","CM","cmd-1")).getStatusCode());
        verifyNoInteractions(db,core,preflight,projection);
    }

    @Test void missingAcademicClassDisplayMetadataCannotCreateFakeFeProjection()
            throws Exception {
        staged("STAGED");
        when(preflight.prepare("tc001","cmd-1")).thenReturn(receipt(null,null));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->executor.execute("tc001","tc-1","TC","cmd-1")).getStatusCode());
        verify(core,never()).acquireAssignmentWriterClaim(
            anyString(),anyString(),anyString(),anyString());
        verify(projection).requireReady();
        verify(projection,never()).project(any(AssignmentProjectionRepository.AssignmentSnapshot.class));
        verifyNoInteractions(outbox,lifecycle);
    }

    @Test void tcWriterAdmissionFailurePrecedesEveryIndependentTeachingWrite()
            throws Exception {
        staged("STAGED");
        when(preflight.prepare("tc001","cmd-1")).thenReturn(receipt("C-1","Class"));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"Core resumed"))
            .when(core).acquireAssignmentWriterClaim(
                "tc001","tc-1","ASSIGN_TEACHER","cmd-1");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->executor.execute("tc001","tc-1","TC","cmd-1")).getStatusCode());
        verify(projection).requireReady();
        verify(projection,never()).project(any(AssignmentProjectionRepository.AssignmentSnapshot.class));
        verifyNoInteractions(outbox,lifecycle);
    }

    @Test void invalidOrNearExpiredAcademicLeaseNeverProducesTeacherReference()
            throws Exception {
        staged("STAGED");
        when(preflight.prepare("tc001","cmd-1")).thenReturn(receipt("C-1","Class"));
        when(academic.renew("tc001","cmd-1")).thenReturn(
            new AcademicCommandLeaseService.Receipt("cmd-1","class-1",
                "lesson-1",3,7,"lease-1",Instant.now().plusSeconds(4)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->executor.execute("tc001","tc-1","TC","cmd-1")).getStatusCode());
        verify(projection).requireReady();
        verify(projection,never()).project(any(AssignmentProjectionRepository.AssignmentSnapshot.class));
        verifyNoInteractions(outbox,lifecycle);
    }

    @Test void revokedTeacherReservationCannotBeReportedAsLocallyApplied()
            throws Exception {
        staged("STAGED");
        when(preflight.prepare("tc001","cmd-1")).thenReturn(receipt("C-1","Class"));
        when(academic.renew("tc001","cmd-1")).thenReturn(
            new AcademicCommandLeaseService.Receipt(
                "cmd-1","class-1","lesson-1",3,7,"lease-1",expires));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->executor.execute("tc001","tc-1","TC","cmd-1")).getStatusCode());
        verify(projection).requireReady();
        verify(projection,never()).project(any(AssignmentProjectionRepository.AssignmentSnapshot.class));
        verifyNoInteractions(outbox,lifecycle);
    }

    @Test void failedWorkerReleaseAbortsTheEntireTeachingSourceTransaction()
            throws Exception {
        fullyPrepared();
        when(db.update(startsWith("DELETE FROM scheduling_availability_reservations"),
            eq("reservation-1"),eq("teacher-1"))).thenReturn(0);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->executor.execute("tc001","tc-1","TC","cmd-1")).getStatusCode());
        verify(projection).project(any(AssignmentProjectionRepository.AssignmentSnapshot.class));
        verify(outbox).append(anyString(),anyString(),anyString(),anyString(),
            anyString(),anyLong(),anyString(),any(JsonNode.class));
    }
}
