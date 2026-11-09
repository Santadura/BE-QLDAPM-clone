package vn.iig.scheduling;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private void put(AssignmentSnapshot item) {
        if(item==null || !List.of("TEACHING","STAFF").contains(item.sourceType()) ||
            item.id()==null || item.id().isBlank() || item.id().length()>50 || item.version()<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid assignment projection");
        if(!item.deleted() && (item.employeeId()==null || item.date()==null
                || item.startTime()==null || item.endTime()==null
                || item.status()==null || !item.endTime().isAfter(item.startTime())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid assignment data");
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
