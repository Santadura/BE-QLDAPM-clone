package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AssignmentProjectionRepositoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AssignmentProjectionRepository repo=new AssignmentProjectionRepository(
        db,new ObjectMapper().findAndRegisterModules());

    private AssignmentProjectionRepository.AssignmentSnapshot teacher(long v,boolean deleted) {
        return new AssignmentProjectionRepository.AssignmentSnapshot("TEACHING","t-1",
            "teacher-001","Teacher A","TEACHER","class-1","C001","Class 1",
            null,LocalDate.parse("2026-11-09"),LocalTime.of(18,0),LocalTime.of(20,0),
            null,"ASSIGNED","tc-001",v,deleted);
    }

    @Test void doesNotServeUnimportedReadModel() {
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        assertThrows(ResponseStatusException.class,()->repo.list(
            "TEACHING",null,null,LocalDate.parse("2026-11-09"),false));
    }

    @Test void idempotentlyProjectsOnlyNewerAssignmentRevisions() {
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(1);
        repo.project(teacher(7,false));
        verify(db).update(contains("scheduling_assignment_read_model.version<EXCLUDED.version"),
            eq("TEACHING"),eq("t-1"),eq(7L),eq(false),
            eq("teacher-001"),eq("TEACHER"),any(),eq("ASSIGNED"),contains("teacherId"));
    }

    @Test void deletionTombstonesCannotBeOverwrittenByOldEvents() {
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(1);
        repo.project(teacher(9,true));
        verify(db).update(contains("scheduling_assignment_read_model.version<EXCLUDED.version"),
            eq("TEACHING"),eq("t-1"),eq(9L),eq(true),
            any(),any(),any(),any(),eq("{}"));
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void existingProjection(String payload,long version) throws Exception {
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(1);
        when(db.query(startsWith("SELECT version,deleted,employee_id"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq("TEACHING"),eq("t-1")))
            .thenAnswer(invocation -> {
                org.springframework.jdbc.core.RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getLong(1)).thenReturn(version);
                when(rs.getBoolean(2)).thenReturn(false);
                when(rs.getString(3)).thenReturn("teacher-001");
                when(rs.getString(4)).thenReturn("TEACHER");
                when(rs.getObject(5,LocalDate.class))
                    .thenReturn(LocalDate.parse("2026-11-09"));
                when(rs.getString(6)).thenReturn("ASSIGNED");
                when(rs.getString(7)).thenReturn(payload);
                return java.util.List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void manifestVerificationRejectsCorruptPayloadDespiteEqualVersion()
            throws Exception {
        existingProjection("{}",7);
        assertFalse(repo.matchesManifest(teacher(7,false)));
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void manifestVerificationRejectsStaleVersionWithoutRepairingIt()
            throws Exception {
        existingProjection("{}",6);
        assertFalse(repo.matchesManifest(teacher(7,false)));
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void rejectsInvalidProjectionBeforeDatabaseWrite() {
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(1);
        var invalid=new AssignmentProjectionRepository.AssignmentSnapshot(
            "INVENTED","t-1",null,null,null,null,null,null,
            null,null,null,null,null,null,null,1,false);
        assertThrows(ResponseStatusException.class,()->repo.project(invalid));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
