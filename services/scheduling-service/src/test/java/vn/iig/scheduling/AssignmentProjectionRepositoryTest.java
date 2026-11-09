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

    @Test void rejectsInvalidProjectionBeforeDatabaseWrite() {
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(1);
        var invalid=new AssignmentProjectionRepository.AssignmentSnapshot(
            "INVENTED","t-1",null,null,null,null,null,null,
            null,null,null,null,null,null,null,1,false);
        assertThrows(ResponseStatusException.class,()->repo.project(invalid));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
