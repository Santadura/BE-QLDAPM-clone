package vn.iig.scheduling;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Independent Scheduling-owned READ copy of assignments. All writes still
 * originate in Core until class lifecycle, CS and attendance invariants move.
 * Versioned tombstones prevent delayed retries resurrecting removed records.
 */
@Repository
public class AssignmentProjectionRepository {
    public record AssignmentSnapshot(String sourceType,String id,String employeeId,String employeeName,
        String staffRole,String classId,String classCode,String className,String workplaceId,
        LocalDate date,LocalTime startTime,LocalTime endTime,String workType,
        String status,String assignedBy,long version,boolean deleted) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public AssignmentProjectionRepository(JdbcTemplate db,ObjectMapper json) {
        this.db=db;
        this.json=json;
    }

    public boolean initialized() {
        Integer count=db.queryForObject(
            "SELECT count(*) FROM scheduling_metadata WHERE key='assignments-imported'",
            Integer.class);
        return count!=null && count>0;
    }

    public void requireReady() {
        if(!initialized()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Assignment snapshot bootstrap not complete");
    }

    @Transactional
    public void importInitial(List<AssignmentSnapshot> source) {
        if(initialized()) return;
        if(source==null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Missing assignment source data");
        for(var item:source) put(item);
        db.update("INSERT INTO scheduling_metadata(key,value) VALUES('assignments-imported','yes') " +
            "ON CONFLICT(key) DO NOTHING");
    }

    @Transactional
    public void project(AssignmentSnapshot item) {
        requireReady();
        put(item);
    }

    /** Commit the full incoming change batch before acknowledging Core. */
    @Transactional
    public void applyBatch(List<AssignmentSnapshot> entries) {
        requireReady();
        if(entries==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Assignment changes missing");
        for(var entry:entries) put(entry);
    }

    private void put(AssignmentSnapshot item) {
        if(item==null || !List.of("TEACHING","STAFF").contains(item.sourceType()) ||
            item.id()==null || item.id().isBlank() || item.id().length()>50 || item.version()<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid assignment projection");
        if(!item.deleted() && (item.employeeId()==null || item.date()==null
                || item.startTime()==null || item.endTime()==null
                || item.status()==null || !item.endTime().isAfter(item.startTime())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid assignment data");
        String document=encodedPayload(item);
        db.update("INSERT INTO scheduling_assignment_read_model " +
            "(source_type,schedule_id,version,deleted,employee_id,staff_role,work_date,status,payload) " +
            "VALUES(?,?,?,?,?,?,?, ?,CAST(? AS jsonb)) " +
            "ON CONFLICT(source_type,schedule_id) DO UPDATE SET " +
            "version=EXCLUDED.version,deleted=EXCLUDED.deleted,employee_id=EXCLUDED.employee_id," +
            "staff_role=EXCLUDED.staff_role,work_date=EXCLUDED.work_date," +
            "status=EXCLUDED.status,payload=EXCLUDED.payload,updated_at=NOW() " +
            "WHERE scheduling_assignment_read_model.version<EXCLUDED.version",
            item.sourceType(),item.id(),item.version(),item.deleted(),item.employeeId(),
            item.staffRole(),item.date(),item.status(),document);
        // The same local transaction maintains the independent normalized
        // write-shadow and FE JSON projection. Until cutover, Core events
        // remain the exclusive source of these revisions.
        db.update("INSERT INTO scheduling_assignment_write_shadow " +
            "(source_type,schedule_id,version,deleted,employee_id,staff_role," +
            "class_id,workplace_id,work_date,start_time,end_time,work_type,status,assigned_by) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(source_type,schedule_id) DO UPDATE SET " +
            "version=EXCLUDED.version,deleted=EXCLUDED.deleted," +
            "employee_id=EXCLUDED.employee_id,staff_role=EXCLUDED.staff_role," +
            "class_id=EXCLUDED.class_id,workplace_id=EXCLUDED.workplace_id," +
            "work_date=EXCLUDED.work_date,start_time=EXCLUDED.start_time," +
            "end_time=EXCLUDED.end_time,work_type=EXCLUDED.work_type," +
            "status=EXCLUDED.status,assigned_by=EXCLUDED.assigned_by,updated_at=NOW() " +
            "WHERE scheduling_assignment_write_shadow.version<EXCLUDED.version",
            item.sourceType(),item.id(),item.version(),item.deleted(),item.employeeId(),
            item.staffRole(),item.classId(),item.workplaceId(),item.date(),
            item.startTime(),item.endTime(),item.workType(),item.status(),item.assignedBy());
    }


    /**
     * Use the identical FE-compatible JSON encoding for both projection
     * writes and migration reconciliation. JSONB key order is irrelevant.
     */
    private String encodedPayload(AssignmentSnapshot item) {
        String document="{}";
        if(!item.deleted()) {
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",item.id());
            if("TEACHING".equals(item.sourceType())) {
                row.put("teacherId",item.employeeId());
                row.put("teacherName",item.employeeName());
                row.put("classId",item.classId());
                row.put("classCode",item.classCode());
                row.put("className",item.className());
            } else {
                row.put("userId",item.employeeId());
                row.put("employeeId",item.employeeId());
                row.put("employeeName",item.employeeName());
                row.put("staffRole",item.staffRole());
                row.put("workplaceId",item.workplaceId());
                row.put("classId",item.classId());
                row.put("workType",item.workType());
            }
            row.put("date",item.date());
            row.put("startTime",item.startTime());
            row.put("endTime",item.endTime());
            row.put("status",item.status());
            row.put("assignedBy",item.assignedBy());
            try {
                document=json.writeValueAsString(row);
            } catch(JsonProcessingException e) {
                throw new IllegalStateException("Failed to encode assignment data",e);
            }
        }
        return document;
    }

    private record Stored(long version,boolean deleted,String employeeId,
                          String staffRole,LocalDate date,String status,String payload) {}

    /** Every Core-backed assignment must also exist in the candidate writer store. */
    private record Shadow(long version,boolean deleted,String employeeId,String staffRole,
                          String classId,String workplaceId,LocalDate date,
                          LocalTime start,LocalTime end,String workType,
                          String status,String assignedBy) {}

    /**
     * Staged/compensating commands are unresolved distributed work.
     * A cutover check must never silently ignore them even when the current
     * Core-backed projection looks byte-for-byte consistent.
     */
    public long pendingAssignmentCommands() {
        requireReady();
        Long count=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_command_inbox " +
            "WHERE state IN ('STAGED','PENDING_REFERENCE','COMPENSATING')",Long.class);
        return count==null?0:count;
    }

    public long countShadowAssignments() {
        requireReady();
        Long count=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_write_shadow",Long.class);
        return count==null?0:count;
    }

    public boolean matchesWriteShadow(AssignmentSnapshot expected) {
        requireReady();
        List<Shadow> result=db.query(
            "SELECT version,deleted,employee_id,staff_role,class_id,workplace_id," +
            "work_date,start_time,end_time,work_type,status,assigned_by " +
            "FROM scheduling_assignment_write_shadow " +
            "WHERE source_type=? AND schedule_id=?",
            (rs,i)->new Shadow(rs.getLong(1),rs.getBoolean(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),
                rs.getObject(7,LocalDate.class),rs.getObject(8,LocalTime.class),
                rs.getObject(9,LocalTime.class),rs.getString(10),
                rs.getString(11),rs.getString(12)),
            expected.sourceType(),expected.id());
        if(result.size()!=1) return false;
        Shadow x=result.get(0);
        return x.version()==expected.version() && x.deleted()==expected.deleted() &&
            Objects.equals(x.employeeId(),expected.employeeId()) &&
            Objects.equals(x.staffRole(),expected.staffRole()) &&
            Objects.equals(x.classId(),expected.classId()) &&
            Objects.equals(x.workplaceId(),expected.workplaceId()) &&
            Objects.equals(x.date(),expected.date()) &&
            Objects.equals(x.start(),expected.startTime()) &&
            Objects.equals(x.end(),expected.endTime()) &&
            Objects.equals(x.workType(),expected.workType()) &&
            Objects.equals(x.status(),expected.status()) &&
            Objects.equals(x.assignedBy(),expected.assignedBy());
    }

    public long countAllAssignments() {
        requireReady();
        Long count=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_read_model",Long.class);
        return count==null?0:count;
    }

    /**
     * Strict equality is a cutover gate, not an eventually-consistent hint:
     * compare tombstones, IDs, versions, indexed fields AND actual FE JSON.
     * Never "repair" a same-version divergence by overwriting data here.
     */
    public boolean matchesManifest(AssignmentSnapshot expected) {
        requireReady();
        if(expected==null || expected.sourceType()==null || expected.id()==null)
            return false;
        List<Stored> rows=db.query(
            "SELECT version,deleted,employee_id,staff_role,work_date,status,payload::text " +
            "FROM scheduling_assignment_read_model WHERE source_type=? AND schedule_id=?",
            (rs,i)->new Stored(rs.getLong(1),rs.getBoolean(2),rs.getString(3),
                rs.getString(4),rs.getObject(5,LocalDate.class),
                rs.getString(6),rs.getString(7)),
            expected.sourceType(),expected.id());
        if(rows.size()!=1) return false;
        Stored actual=rows.get(0);
        if(actual.version()!=expected.version() || actual.deleted()!=expected.deleted()
                || !Objects.equals(actual.employeeId(),expected.employeeId())
                || !Objects.equals(actual.staffRole(),expected.staffRole())
                || !Objects.equals(actual.date(),expected.date())
                || !Objects.equals(actual.status(),expected.status())) return false;
        try {
            return json.readTree(encodedPayload(expected))
                .equals(json.readTree(actual.payload())) && matchesWriteShadow(expected);
        } catch(JsonProcessingException ex) {
            return false;
        }
    }

    public List<Map<String,Object>> list(String source,String employeeId,String role,
                                         LocalDate monday,boolean activeOnly) {
        requireReady();
        return db.query("SELECT payload::text FROM scheduling_assignment_read_model " +
            "WHERE source_type=? AND deleted=FALSE AND work_date BETWEEN ? AND ? " +
            "AND (CAST(? AS varchar) IS NULL OR employee_id=?) " +
            "AND (CAST(? AS varchar) IS NULL OR staff_role=?) " +
            "AND (?=FALSE OR status='ASSIGNED') " +
            "ORDER BY work_date,(payload->>'startTime'),schedule_id",
            (rs,i)->decode(rs.getString(1)),source,monday,monday.plusDays(6),
            employeeId,employeeId,role,role,activeOnly);
    }

    private Map<String,Object> decode(String payload) throws SQLException {
        try {
            return json.readValue(payload,new TypeReference<Map<String,Object>>(){});
        } catch(JsonProcessingException e) {
            throw new SQLException("Corrupted assignment projection JSON",e);
        }
    }
}
