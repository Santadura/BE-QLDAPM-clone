package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.sql.ResultSet;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademicSessionCandidateRepositoryTest {
    JdbcTemplate db=mock(JdbcTemplate.class);
    AcademicSessionCandidateRepository repo=
        new AcademicSessionCandidateRepository(db,new ObjectMapper());
    AcademicSessionCandidateRepository.Plan plan() {
        return new AcademicSessionCandidateRepository.Plan(
            "admin","key","a".repeat(64),"class-1","lesson-1",5,3,
            "RESCHEDULE",LocalDate.parse("2027-02-12"),
            LocalTime.parse("09:00"),LocalTime.parse("11:00"),"PLANNED",4,5,6);
    }
    @Test void staleSessionCannotCreateAnyCandidateEvent() {
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.stage(plan())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void unpromotedRehearsalsIncludeReleasedClaimsUntilExplicitDisposition() {
        when(db.queryForObject(
            "SELECT COUNT(*) FROM academic_session_candidate_commands",
            Long.class)).thenReturn(3L);
        assertEquals(3,repo.unpromotedRehearsalCount());
    }

    @Test void missingRehearsalLedgerNeverCountsAsEmpty() {
        when(db.queryForObject(
            "SELECT COUNT(*) FROM academic_session_candidate_commands",
            Long.class)).thenReturn(null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,repo::unpromotedRehearsalCount)
            .getStatusCode());
    }

    private static final String COMMAND=
        "b381ee38-d42f-48e7-bbf8-24198e329503";

    private String canonical() {
        var event=new ObjectMapper().createObjectNode();
        event.put("commandId",COMMAND);
        event.put("operator","admin");
        event.put("requestKey","key");
        event.put("classId","class-1");
        event.put("sessionId","lesson-1");
        event.put("sourceEventId",5L);
        event.put("sourceRevision",3L);
        event.put("action","RESCHEDULE");
        event.put("date","2027-02-12");
        event.put("startTime","09:00");
        event.put("endTime","11:00");
        event.put("status","PLANNED");
        event.put("academicGeneration",4L);
        event.put("attendanceGeneration",5L);
        event.put("schedulingGeneration",6L);
        return event.toString();
    }

    private String requestDigest() {
        String canonical="academic-candidate-v1\nclass-1\nlesson-1\n5\n3\n"+
            "RESCHEDULE\n2027-02-12\n09:00\n11:00\nPLANNED";
        return AcademicSessionSourceRepository.sha256(canonical);
    }

    private void storedCandidate(String canonical,String copiedJson,
            String hash) throws Exception {
        ResultSet rs=mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("admin");
        when(rs.getString(2)).thenReturn("key");
        when(rs.getString(3)).thenReturn(requestDigest());
        when(rs.getString(4)).thenReturn("class-1");
        when(rs.getString(5)).thenReturn("lesson-1");
        when(rs.getLong(6)).thenReturn(5L);
        when(rs.getLong(7)).thenReturn(3L);
        when(rs.getLong(8)).thenReturn(4L);
        when(rs.getLong(9)).thenReturn(5L);
        when(rs.getLong(10)).thenReturn(6L);
        when(rs.getString(11)).thenReturn(COMMAND);
        when(rs.getString(12)).thenReturn(hash);
        when(rs.getString(13)).thenReturn(canonical);
        when(rs.getString(14)).thenReturn(copiedJson);
        when(rs.getString(15)).thenReturn("RESCHEDULE");
        when(rs.getString(16)).thenReturn("2027-02-12");
        when(rs.getString(17)).thenReturn("09:00:00");
        when(rs.getString(18)).thenReturn("11:00:00");
        when(rs.getString(19)).thenReturn("PLANNED");
        when(db.query(anyString(),any(RowMapper.class),eq("admin"),
                eq("key"),eq(COMMAND))).thenAnswer(call->{
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper=call.getArgument(1);
            return List.of(mapper.mapRow(rs,0));
        });
    }

    @Test void exactCanonicalOutboxAndTypedSlotPermitVerifiedHandoff()
            throws Exception {
        String body=canonical();
        storedCandidate(body,body,AcademicSessionSourceRepository.sha256(body));
        var proof=repo.committedEvidence("admin","key",COMMAND);
        assertEquals(COMMAND,proof.commandId());
        assertEquals(5L,proof.eventId());
    }

    @Test void canonicalBytesMissingAreNotRecreatedFromJsonb()
            throws Exception {
        String body=canonical();
        storedCandidate(null,body,AcademicSessionSourceRepository.sha256(body));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.committedEvidence("admin","key",COMMAND))
            .getStatusCode());
    }

    @Test void tamperedCanonicalBodyOrJsonbIsNeverReleaseEvidence()
            throws Exception {
        String body=canonical();
        String hash=AcademicSessionSourceRepository.sha256(body);
        storedCandidate(body.replace("PLANNED","CANCELLED"),body,hash);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.committedEvidence("admin","key",COMMAND)).getStatusCode());

        reset(db);
        storedCandidate(body,body.replace("PLANNED","CANCELLED"),hash);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.committedEvidence("admin","key",COMMAND)).getStatusCode());
    }

}
