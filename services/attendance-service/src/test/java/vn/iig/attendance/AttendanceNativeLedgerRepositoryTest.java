package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class AttendanceNativeLedgerRepositoryTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final ObjectMapper json=new ObjectMapper();
    final AttendanceNativeLedgerRepository ledger=
        new AttendanceNativeLedgerRepository(db,json);
    static final String KEY="native-key";
    static final String ID="00000000-0000-0000-0000-000000000001";
    static final String TOKEN="a".repeat(64);

    AttendanceNativeLedgerRepository.Admission admitted(String status) {
        return new AttendanceNativeLedgerRepository.Admission(
            "cs001","employee-1","class-1","teach-1",KEY,
            AttendanceCommandService.fingerprint("class-1","teach-1",status),
            status,TOKEN,2,"claim-1","attendance-1","ABSENT","ATTENDANCE");
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    void originalRow() {
        when(db.queryForObject(
            startsWith("SELECT COUNT(*) FROM attendance_native_activations"),
            eq(Long.class),eq(2L))).thenReturn(1L);
        when(db.query(startsWith("SELECT attendance_id,version,status"),
            any(RowMapper.class),eq("teach-1"))).thenAnswer(call->{
            RowMapper mapper=call.getArgument(1);
            ResultSet row=mock(ResultSet.class);
            when(row.getString(1)).thenReturn("attendance-1");
            when(row.getLong(2)).thenReturn(3L);
            when(row.getString(3)).thenReturn("ABSENT");
            return List.of(mapper.mapRow(row,0));
        });
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    void originalCommand(String status,String state) {
        when(db.query(startsWith("SELECT event_id::text,request_hash,facts_token"),
            any(RowMapper.class),eq("cs001"),eq("class-1"),
            eq("teach-1"),eq(KEY))).thenAnswer(call->{
            RowMapper mapper=call.getArgument(1);
            ResultSet row=mock(ResultSet.class);
            when(row.getString(1)).thenReturn(ID);
            when(row.getString(2)).thenReturn(admitted(status).requestHash());
            when(row.getString(3)).thenReturn(TOKEN);
            when(row.getLong(4)).thenReturn(2L);
            when(row.getString(5)).thenReturn(state);
            return List.of(mapper.mapRow(row,0));
        });
    }

    @Test void exclusiveOwnerRequiredBeforeAnyNativeDatabaseMutation() {
        var a=admitted("PRESENT");
        var wrong=new AttendanceNativeLedgerRepository.Admission(
            a.actorUsername(),a.actorEmployeeId(),a.classId(),
            a.teachingScheduleId(),a.requestKey(),a.requestHash(),
            a.status(),a.factsToken(),a.writerGeneration(),
            a.coreClaimId(),a.previousAttendanceId(),a.previousStatus(),"CORE");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->ledger.stage(wrong))
            .getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void unchangedSourceMustBeBootstrappedBeforeNativeWrite() {
        when(db.queryForObject(
            startsWith("SELECT COUNT(*) FROM attendance_native_activations"),
            eq(Long.class),eq(2L))).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->ledger.stage(admitted("PRESENT"))).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void nativeWriteAtomicallyAppendsEventAndRevisionsDomainRow() {
        originalRow();
        originalCommand("PRESENT","PENDING_REFERENCE");
        when(db.update(startsWith("INSERT INTO attendance_native_commands"),
            any(Object[].class))).thenReturn(1);
        when(db.update(startsWith("UPDATE attendance_native_teacher_rows"),
            any(Object[].class))).thenReturn(1);
        when(db.update(startsWith("INSERT INTO attendance_native_outbox"),
            any(Object[].class))).thenReturn(1);
        var outcome=ledger.stage(admitted("PRESENT"));
        assertEquals(ID,outcome.eventId());
        assertEquals(4,outcome.version());
        assertEquals("PRESENT",outcome.status());
        assertFalse(outcome.referenceConfirmed());
        verify(db).update(startsWith("UPDATE attendance_native_teacher_rows"),
            eq(4L),eq("PRESENT"),eq("employee-1"),
            any(java.sql.Timestamp.class),any(java.sql.Timestamp.class),
            eq("teach-1"),eq(3L));
        verify(db).update(startsWith("INSERT INTO attendance_native_outbox"),
            eq(ID),eq("cs001"),eq("class-1"),eq("teach-1"),
            eq(KEY),eq(4L),anyString(),anyString(),matches("[a-f0-9]{64}"));
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void sameNativeKeyDoesNotGenerateAnotherDomainVersionOrEvent() {
        originalRow();
        originalCommand("LATE","PENDING_REFERENCE");
        when(db.update(startsWith("INSERT INTO attendance_native_commands"),
            any(Object[].class))).thenReturn(0);
        when(db.query(startsWith("SELECT event_id::text,actor_username"),
            any(RowMapper.class),eq(ID))).thenAnswer(call->{
            RowMapper mapper=call.getArgument(1);
            ResultSet row=mock(ResultSet.class);
            when(row.getString(1)).thenReturn(ID);
            when(row.getString(2)).thenReturn("cs001");
            when(row.getString(3)).thenReturn("class-1");
            when(row.getString(4)).thenReturn("teach-1");
            when(row.getString(5)).thenReturn(KEY);
            when(row.getLong(6)).thenReturn(4L);
            when(row.getString(7)).thenReturn("{}");
            when(row.getString(8)).thenReturn("b".repeat(64));
            return List.of(mapper.mapRow(row,0));
        });
        var again=ledger.stage(admitted("LATE"));
        assertEquals(4,again.version());
        assertFalse(again.referenceConfirmed());
        verify(db,never()).update(startsWith("INSERT INTO attendance_native_outbox"),
            any(Object[].class));
        verify(db,never()).update(startsWith("UPDATE attendance_native_teacher_rows"),
            any(Object[].class));
    }

    @Test void hashChangeUnderSameKeyIsRejectedWithoutOverwritingAnEvent() {
        originalRow();
        originalCommand("PRESENT","PENDING_REFERENCE");
        when(db.update(startsWith("INSERT INTO attendance_native_commands"),
            any(Object[].class))).thenReturn(0);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->ledger.stage(admitted("LATE"))).getStatusCode());
        verify(db,never()).update(startsWith("UPDATE attendance_native_teacher_rows"),
            any(Object[].class));
    }

    @Test void CoreReceiptMustExactlyMatchImmutableEventDigestAndRevision() {
        var event=new AttendanceNativeLedgerRepository.Pending(
            ID,"cs001","class-1","teach-1",KEY,4,
            "{\"eventId\":\""+ID+"\"}","a".repeat(64));
        var receipt=json.createObjectNode()
            .put("eventId",ID).put("teachingScheduleId","teach-1")
            .put("version",4).put("payloadHash","a".repeat(64));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->ledger.confirm(event,receipt)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void unboundedPendingQueueCannotReadAnyEvents() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->ledger.pending(51)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void nativeWriterActivationRefusesMissingHistoricalEvents() {
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_native_activations"),
            eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_native_commands"),
            eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_native_teacher_rows"),
            eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_teacher_ledger"),
            eq(Long.class))).thenReturn(1L);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_teacher_event_history"),
            eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(startsWith("SELECT last_event_id"),
            eq(Long.class))).thenReturn(1L);
        var proof=new AttendanceNativeLedgerRepository.Activation(
            "admin",2,1,1,1,"a".repeat(64));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->ledger.activate(proof)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void exactFrozenSourceIsSeededTogetherWithImmutableGenerationReceipt() {
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_native_activations"),
            eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_native_commands"),
            eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_native_teacher_rows"),
            eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_teacher_ledger"),
            eq(Long.class))).thenReturn(2L);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_teacher_event_history"),
            eq(Long.class))).thenReturn(3L);
        when(db.queryForObject(startsWith("SELECT last_event_id"),
            eq(Long.class))).thenReturn(5L);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM attendance_teacher_ledger WHERE event_kind"),
            eq(Long.class))).thenReturn(1L);
        when(db.update(startsWith("INSERT INTO attendance_native_teacher_rows")))
            .thenReturn(1);
        when(db.update(startsWith("INSERT INTO attendance_native_activations"),
            any(Object[].class))).thenReturn(1);
        var proof=new AttendanceNativeLedgerRepository.Activation(
            "admin",4,2,3,5,"c".repeat(64));
        assertEquals(1,ledger.activate(proof));
        verify(db).update(startsWith("INSERT INTO attendance_native_activations"),
            eq(4L),eq(2L),eq(3L),eq(5L),eq("c".repeat(64)),
            eq("admin"),eq(1L));
    }

    @Test void noNativeGenerationMayWriteBeforeItsActivationWasProven() {
        when(db.queryForObject(
            startsWith("SELECT COUNT(*) FROM attendance_native_activations"),
            eq(Long.class),eq(2L))).thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->ledger.stage(admitted("PRESENT"))).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

}
