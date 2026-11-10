package vn.iig.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.scheduling.SchedulingModels.ReservationRequest;
import vn.iig.scheduling.SchedulingModels.Lease;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes","unchecked"})
class TeacherAssignmentPreflightServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AssignmentCutoverVerificationService readiness=
        mock(AssignmentCutoverVerificationService.class);
    private final AcademicCommandLeaseService academic=mock(AcademicCommandLeaseService.class);
    private final AvailabilityRepository availability=mock(AvailabilityRepository.class);
    private final AssignmentWriteConflictGuard conflicts=mock(AssignmentWriteConflictGuard.class);
    private final TeacherAssignmentPreflightService service=
        new TeacherAssignmentPreflightService(db,new ObjectMapper(),core,
            readiness,academic,availability,conflicts);
    private final Instant later=Instant.now().plusSeconds(250);
    private final LocalDate date=LocalDate.of(2027,2,11);
    private final LocalTime start=LocalTime.of(9,0);
    private final LocalTime end=LocalTime.of(11,0);
    private static final String VALID="""
        {"operation":"ASSIGN_TEACHER","targetId":null,
         "payload":{"teacherId":"teacher-1","classId":"class-1",
         "sessionId":"lesson-1","classVersion":3,"sessionVersion":7,
         "date":"2027-02-11","startTime":"09:00","endTime":"11:00"}}""";

    private void staged(String state,String value) throws Exception {
        when(db.query(startsWith("SELECT state,payload::text"),
            any(RowMapper.class),eq("tc001"),eq("command-1"))).thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(state);
                when(rs.getString(2)).thenReturn(value);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    private void academicReady() {
        when(readiness.verifyStaged("tc001","ASSIGN_TEACHER","command-1"))
            .thenReturn(new AssignmentCutoverVerificationService.Report(true,"READY",
                0,0,0,0,0,0,0,0,0,0,List.of()));
        when(core.academicSession("class-1",date,start,end)).thenReturn(
            new SchedulingCoreClient.AcademicSessionFacts(
                "class-1",3,"READY",LocalDate.of(2027,2,1),
                LocalDate.of(2027,2,28),"lesson-1",7,"PLANNED",date,start,end));
        when(academic.acquire("tc001","command-1")).thenReturn(
            new AcademicCommandLeaseService.Receipt(
                "command-1","class-1","lesson-1",3,7,"lease-academic",later));
    }

    private void workerRole(String role) throws Exception {
        when(db.query(startsWith("SELECT a.role_id,r.expires_at"),
            any(RowMapper.class),eq("reservation-1"),eq("teacher-1")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(role);
                when(rs.getTimestamp(2)).thenReturn(Timestamp.from(later));
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void successfulPreflightHoldsBothLeasesWithoutAnyTeachingWrite()
            throws Exception {
        staged("STAGED",VALID);academicReady();workerRole("TEACHER");
        when(availability.reserve(new ReservationRequest("teacher-1",date,
            start,end,null))).thenReturn(new Lease(true,"reservation-1"));
        when(db.update(startsWith("INSERT INTO scheduling_teacher_command_preflights"),
            any(Object[].class))).thenReturn(1);

        var receipt=service.prepare("tc001","command-1");
        assertEquals("PREFLIGHT_ONLY",receipt.state());
        assertEquals("lease-academic",receipt.academicLeaseId());
        assertEquals("reservation-1",receipt.workerReservationId());
        verify(conflicts).requireUnoccupied(
            new AssignmentWriteConflictGuard.Candidate(
                "TEACHING","teacher-1","class-1",null,date,start,end,"reservation-1"));
        verify(core).requireCoreWriterQuiesced();
        verify(readiness).verifyStaged("tc001","ASSIGN_TEACHER","command-1");
        verify(academic).acquire("tc001","command-1");
    }

    @Test void noUnreconciledSourceMayApproveTeacherIntent() throws Exception {
        staged("STAGED",VALID);
        when(readiness.verifyStaged("tc001","ASSIGN_TEACHER","command-1"))
            .thenReturn(new AssignmentCutoverVerificationService.Report(
                false,"PROJECTION_DRIFT",1,1,1,1,1,0,0,0,0,1,List.of("TEACHING:x")));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.prepare("tc001","command-1")).getStatusCode());
        verifyNoInteractions(academic,availability,conflicts);
    }

    @Test void mismatchedExactAcademicLessonCannotReserveWorker() throws Exception {
        staged("STAGED",VALID);academicReady();
        when(core.academicSession("class-1",date,start,end)).thenReturn(
            new SchedulingCoreClient.AcademicSessionFacts(
                "class-1",3,"READY",LocalDate.of(2027,2,1),
                LocalDate.of(2027,2,28),"lesson-2",7,"PLANNED",date,start,end));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.prepare("tc001","command-1")).getStatusCode());
        verifyNoInteractions(academic,availability,conflicts);
    }

    @Test void wrongWorkerRoleFailsClosedAndCannotMarkAReadyAssignment()
            throws Exception {
        staged("STAGED",VALID);academicReady();workerRole("CS");
        when(availability.reserve(any(ReservationRequest.class)))
            .thenReturn(new Lease(true,"reservation-1"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.prepare("tc001","command-1")).getStatusCode());
        verifyNoInteractions(conflicts);
        verify(db,never()).update(startsWith(
            "INSERT INTO scheduling_teacher_command_preflights"),any(Object[].class));
    }

    @Test void noApprovedWorkerAvailabilityDoesNotCreateFakeReceipt()
            throws Exception {
        staged("STAGED",VALID);academicReady();
        when(availability.reserve(any(ReservationRequest.class)))
            .thenReturn(new Lease(false,null));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.prepare("tc001","command-1")).getStatusCode());
        verify(db,never()).update(startsWith(
            "INSERT INTO scheduling_teacher_command_preflights"),any(Object[].class));
    }

    @Test void nonStagedCommandCannotPreflightOrCallAnyExternalService()
            throws Exception {
        staged("PENDING_REFERENCE",VALID);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.prepare("tc001","command-1")).getStatusCode());
        verifyNoInteractions(academic,availability,core,readiness);
    }

    @Test void malformedTeacherSlotRejectsBeforeAnyLeaseIsAcquired()
            throws Exception {
        staged("STAGED",VALID.replace("11:00","08:00"));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.prepare("tc001","command-1")).getStatusCode());
        verifyNoInteractions(core,academic,availability);
    }
}
