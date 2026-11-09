package com.example.demo.service;

import com.example.demo.dto.SchedulingDtos.StaffRequest;
import com.example.demo.dto.SchedulingDtos.TeachingRequest;
import com.example.demo.dto.SchedulingDtos.TeachingBatchRequest;
import com.example.demo.dto.SchedulingDtos.SupportBatchRequest;
import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssignmentService {
    private final JdbcTemplate db;
    private final SchedulingAccess access;

    private static final String TEACHING =
        "SELECT t.teaching_schedule_id AS id,t.teacher_id AS \"teacherId\", " +
        "e.full_name AS \"teacherName\",t.class_id AS \"classId\",c.class_code AS \"classCode\", " +
        "c.name AS \"className\",t.date,t.start_time AS \"startTime\",t.end_time AS \"endTime\", " +
        "t.status,t.assigned_by AS \"assignedBy\" FROM teaching_schedules t " +
        "JOIN employees e ON t.teacher_id=e.employee_id JOIN classes c ON c.class_id=t.class_id ";
    private static final String STAFF =
        "SELECT s.staff_schedule_id AS id,s.employee_id AS \"userId\",s.employee_id AS \"employeeId\", " +
        "e.full_name AS \"employeeName\",u.role_id AS \"staffRole\",s.workplace_id AS \"workplaceId\", " +
        "s.class_id AS \"classId\",s.date,s.start_time AS \"startTime\",s.end_time AS \"endTime\", " +
        "s.work_type AS \"workType\",s.status,s.assigned_by AS \"assignedBy\" " +
        "FROM staff_schedules s JOIN employees e ON e.employee_id=s.employee_id " +
        "JOIN users u ON u.username=e.username ";

    public List<Map<String,Object>> employees(String role) {
        var actor=access.requireActiveRole("TC","CM","ADMIN");
        String r=role.toUpperCase(Locale.ROOT);
        if (!List.of("TEACHER","CS","SALE").contains(r)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"Unknown employee role");
        }
        if (actor.role().equals("TC") && !r.equals("TEACHER") ||
            actor.role().equals("CM") && r.equals("TEACHER")) {
            throw new ApiException(HttpStatus.FORBIDDEN,"Cannot browse this role");
        }
        return db.queryForList("SELECT e.employee_id AS id,e.employee_id AS \"employeeId\", " +
            "e.employee_code AS \"employeeCode\",e.full_name AS \"fullName\",u.role_id AS role " +
            "FROM employees e JOIN users u ON e.username=u.username " +
            "WHERE u.role_id=? AND e.status='ACTIVE' AND u.status='ACTIVE' ORDER BY e.full_name",r);
    }

    public List<Map<String,Object>> teaching(LocalDate week) {
        access.requireActiveRole("TC","ADMIN");
        LocalDate start=access.monday(week);
        return db.queryForList(TEACHING+"WHERE t.date BETWEEN ? AND ? ORDER BY t.date,t.start_time",
            start,start.plusDays(6));
    }

    public List<Map<String,Object>> staff(LocalDate week,String role) {
        var actor=access.requireActiveRole("CM","ADMIN");
        String requested=role==null?null:role.toUpperCase(Locale.ROOT);
        if (requested!=null && !List.of("CS","SALE").contains(requested)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"role must be CS or SALE");
        }
        LocalDate start=access.monday(week);
        return db.queryForList(STAFF+
            "WHERE s.date BETWEEN ? AND ? AND u.role_id IN ('CS','SALE') " +
            "AND (CAST(? AS varchar) IS NULL OR u.role_id=?) ORDER BY s.date,s.start_time",
            start,start.plusDays(6),requested,requested);
    }

    public List<Map<String,Object>> mine(LocalDate week) {
        var actor=access.requireRole("TEACHER","CS","SALE");
        LocalDate start=access.monday(week);
        if (actor.role().equals("TEACHER")) {
            return db.queryForList(TEACHING+"WHERE t.teacher_id=? AND t.date BETWEEN ? AND ? " +
                "AND t.status='ASSIGNED' ORDER BY t.date,t.start_time",actor.id(),start,start.plusDays(6));
        }
        return db.queryForList(STAFF+"WHERE s.employee_id=? AND s.date BETWEEN ? AND ? " +
            "AND s.status='ASSIGNED' ORDER BY s.date,s.start_time",actor.id(),start,start.plusDays(6));
    }

    private Map<String,Object> teacherSlot(String id) {
        return db.queryForList(TEACHING+"WHERE t.teaching_schedule_id=?",id).stream().findFirst()
            .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Teaching schedule not found"));
    }

    private Map<String,Object> staffSlot(String id) {
        return db.queryForList(STAFF+"WHERE s.staff_schedule_id=?",id).stream().findFirst()
            .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Staff schedule not found"));
    }

    private void validateTeacher(TeachingRequest data,String exceptId) {
        access.validTimes(data.startTime(),data.endTime());
        access.requireEmployeeRole(data.teacherId(),"TEACHER");
        Map<String,Object> cls=db.queryForList("SELECT status,start_date,end_date FROM classes WHERE class_id=?",
            data.classId()).stream().findFirst().orElseThrow(
                ()->new ApiException(HttpStatus.BAD_REQUEST,"Class not found"));
        if (!List.of("READY","RUNNING").contains(cls.get("status"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"Class must be READY or RUNNING");
        }
        LocalDate start=((java.sql.Date)cls.get("start_date")).toLocalDate();
        LocalDate end=((java.sql.Date)cls.get("end_date")).toLocalDate();
        if (data.date().isBefore(start)||data.date().isAfter(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"Teaching date outside class period");
        }
        Integer planned=db.queryForObject(
            "SELECT count(*) FROM class_sessions WHERE class_id=? AND date=? " +
            "AND start_time=? AND end_time=? AND status='PLANNED'",
            Integer.class,data.classId(),data.date(),data.startTime(),data.endTime());
        if (planned == null || planned == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "TC must assign a teacher to an existing planned class session, without changing its time");
        }
        if (!access.hasApprovedAvailability(data.teacherId(),data.date(),
                data.startTime(),data.endTime(),data.campus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "No approved availability covering this teaching session: " +
                access.availabilityMismatchReason(data.teacherId(), data.date(),
                    data.startTime(), data.endTime(), data.campus()));
        }
        List<Map<String,Object>> personConflicts=db.queryForList(
            "SELECT c.class_code AS \"classCode\", t.start_time AS \"startTime\", " +
            "t.end_time AS \"endTime\" FROM teaching_schedules t " +
            "JOIN classes c ON c.class_id=t.class_id " +
            "WHERE t.teacher_id=? AND t.date=? AND t.status='ASSIGNED' " +
            "AND t.teaching_schedule_id<>? AND t.start_time<? AND t.end_time>? " +
            "ORDER BY t.start_time LIMIT 1",
            data.teacherId(),data.date(),exceptId,data.endTime(),data.startTime());
        if (!personConflicts.isEmpty()) {
            var other=personConflicts.get(0);
            throw new ApiException(HttpStatus.CONFLICT,
                "TEACHER_SCHEDULE_CONFLICT: Giáo viên đã được phân công lớp " +
                other.get("classCode") + " ngày " + data.date() +
                " từ " + other.get("startTime") + " đến " + other.get("endTime") +
                ". Hãy chọn giáo viên khác hoặc thay đổi lịch phân công trùng.");
        }
        Integer classConflict=db.queryForObject(
            "SELECT count(*) FROM teaching_schedules WHERE class_id=? AND date=? " +
            "AND status='ASSIGNED' AND teaching_schedule_id<>? AND start_time<? AND end_time>?",
            Integer.class,data.classId(),data.date(),exceptId,data.endTime(),data.startTime());
        if (classConflict!=null&&classConflict>0)
            throw new ApiException(HttpStatus.CONFLICT,
                "CLASS_SCHEDULE_CONFLICT: Lớp đã có giáo viên khác được phân công trùng buổi.");
    }

    private void lockClass(String id) {
        db.queryForObject("SELECT class_id FROM classes WHERE class_id=? FOR UPDATE",String.class,id);
    }

    private List<Map<String,Object>> requireBatchSessions(String classId, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty() || sessionIds.size() > 50
                || sessionIds.size() != sessionIds.stream().distinct().count()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose 1-50 different planned sessions");
        }
        List<Map<String,Object>> sessions = new java.util.ArrayList<>();
        for (String sessionId : sessionIds) {
            List<Map<String,Object>> rows = db.queryForList(
                "SELECT date,start_time,end_time FROM class_sessions " +
                "WHERE class_session_id=? AND class_id=? AND status='PLANNED'",
                sessionId, classId);
            if (rows.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND,
                    "A selected session no longer exists in this class plan");
            }
            sessions.add(rows.get(0));
        }
        return sessions;
    }

    @Transactional
    public List<Map<String,Object>> assignTeacherBatch(TeachingBatchRequest data) {
        access.requireRole("TC");
        var sessions = requireBatchSessions(data.classId(), data.sessionIds());
        List<Map<String,Object>> created = new java.util.ArrayList<>();
        for (Map<String,Object> slot : sessions) {
            created.add(assignTeacher(new TeachingRequest(
                data.teacherId(), data.classId(),
                ((java.sql.Date)slot.get("date")).toLocalDate(),
                java.time.LocalTime.parse(slot.get("start_time").toString()),
                java.time.LocalTime.parse(slot.get("end_time").toString()),
                data.campus(), null
            )));
        }
        return created;
    }

    @Transactional
    public List<Map<String,Object>> assignSupportBatch(SupportBatchRequest data) {
        access.requireRole("CM");
        var sessions = requireBatchSessions(data.classId(), data.sessionIds());
        List<Map<String,Object>> created = new java.util.ArrayList<>();
        for (Map<String,Object> slot : sessions) {
            created.add(assignStaff(new StaffRequest(
                data.employeeId(), "CS", data.workplaceId(), data.classId(),
                ((java.sql.Date)slot.get("date")).toLocalDate(),
                java.time.LocalTime.parse(slot.get("start_time").toString()),
                java.time.LocalTime.parse(slot.get("end_time").toString()),
                "CLASS_SUPPORT"
            )));
        }
        return created;
    }

    @Transactional
    public Map<String,Object> assignTeacher(TeachingRequest data) {
        var actor=access.requireRole("TC");
        access.lockEmployee(data.teacherId());
        lockClass(data.classId());
        validateTeacher(data,"");
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO teaching_schedules " +
            "(teaching_schedule_id,teacher_id,class_id,date,start_time,end_time,status,assigned_by) " +
            "VALUES (?,?,?,?,?,?,'ASSIGNED',?)",id,data.teacherId(),data.classId(),
            data.date(),data.startTime(),data.endTime(),actor.id());
        access.audit(actor,"ASSIGN_TEACHER","TEACHING_SCHEDULE",id);
        return teacherSlot(id);
    }

    @Transactional
    public Map<String,Object> editTeacher(String id,TeachingRequest data) {
        var actor=access.requireRole("TC");
        Map<String,Object> old=teacherSlot(id);
        if (!"ASSIGNED".equals(old.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"Schedule is cancelled");
        if (!old.get("teacherId").equals(data.teacherId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Use class-planning staffing replacement to change a teacher and retain attendance history");
        }
        // Lock both people deterministically to avoid races during reassignment.
        List<String> ids=java.util.stream.Stream.of((String)old.get("teacherId"),data.teacherId())
            .distinct().sorted().toList();
        ids.forEach(access::lockEmployee);
        List<String> cls=java.util.stream.Stream.of((String)old.get("classId"),data.classId())
            .distinct().sorted().toList();
        cls.forEach(this::lockClass);
        // Once Running, the timetable is fixed; changing the teacher is allowed
        // but moving a session requires a separately approved timetable change.
        Integer running=db.queryForObject(
            "SELECT count(*) FROM classes WHERE class_id=? AND status='RUNNING'",
            Integer.class,old.get("classId"));
        if (running != null && running > 0 &&
            (!old.get("classId").equals(data.classId()) ||
             !old.get("date").toString().equals(data.date().toString()) ||
             !old.get("startTime").toString().startsWith(data.startTime().toString()) ||
             !old.get("endTime").toString().startsWith(data.endTime().toString()))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Running class timetable is locked; reassign the teacher without changing the slot");
        }
        boolean changingAssignedLesson =
            !old.get("teacherId").equals(data.teacherId()) ||
            !old.get("classId").equals(data.classId()) ||
            !old.get("date").toString().equals(data.date().toString()) ||
            !old.get("startTime").toString().startsWith(data.startTime().toString()) ||
            !old.get("endTime").toString().startsWith(data.endTime().toString());
        if (changingAssignedLesson) {
            // Lock the source assignment so a CS attendance save cannot race
            // a teacher replacement and accidentally attach presence to a new teacher.
            db.queryForObject(
                "SELECT teaching_schedule_id FROM teaching_schedules WHERE teaching_schedule_id=? FOR UPDATE",
                String.class, id);
            Integer recorded = db.queryForObject(
                "SELECT count(*) FROM teacher_session_attendance WHERE teaching_schedule_id=?",
                Integer.class, id);
            if (recorded != null && recorded > 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "Attendance is already recorded for this teacher assignment; it cannot be reassigned or moved");
            }
        }
        validateTeacher(data,id);
        db.update("UPDATE teaching_schedules SET teacher_id=?,class_id=?,date=?,start_time=?,end_time=? " +
            "WHERE teaching_schedule_id=?",data.teacherId(),data.classId(),data.date(),
            data.startTime(),data.endTime(),id);
        access.audit(actor,"UPDATE_TEACHING_SCHEDULE","TEACHING_SCHEDULE",id);
        return teacherSlot(id);
    }

    @Transactional
    public void cancelTeacher(String id) {
        var actor=access.requireRole("TC");
        Map<String,Object> old=teacherSlot(id);
        access.lockEmployee((String)old.get("teacherId"));
        // Do not leave an active class without a teacher for one of its planned lessons.
        Integer running=db.queryForObject(
            "SELECT count(*) FROM classes WHERE class_id=? AND status='RUNNING'",
            Integer.class,old.get("classId"));
        if (running != null && running > 0 && "ASSIGNED".equals(old.get("status"))) {
            Integer alternatives=db.queryForObject(
                "SELECT count(*) FROM teaching_schedules WHERE class_id=? AND date=? " +
                "AND start_time=? AND end_time=? AND status='ASSIGNED' AND teaching_schedule_id<>?",
                Integer.class,old.get("classId"),old.get("date"),old.get("startTime"),old.get("endTime"),id);
            if (alternatives == null || alternatives == 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Reassign another teacher before cancelling the only teacher for a running class session");
            }
        }
        db.update("UPDATE teaching_schedules SET status='CANCELLED' WHERE teaching_schedule_id=?",id);
        access.audit(actor,"CANCEL_TEACHING_SCHEDULE","TEACHING_SCHEDULE",id);
    }

    private void validateStaff(StaffRequest data,String exceptId) {
        access.validTimes(data.startTime(),data.endTime());
        String role=data.staffRole().toUpperCase(Locale.ROOT);
        if (!List.of("CS","SALE").contains(role)) throw new ApiException(HttpStatus.BAD_REQUEST,"role must be CS or SALE");
        access.requireEmployeeRole(data.employeeId(),role);
        List<String> validWork=role.equals("CS")
            ?List.of("CLASS_SUPPORT","OPERATIONS_SUPPORT","FRONT_DESK")
            :List.of("SALES_SHIFT","LEAD_FOLLOWUP","CONSULTATION");
        if (!validWork.contains(data.workType())) throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid workType for role");
        String campus=access.campusForWorkplace(data.workplaceId());
        if (!access.hasApprovedAvailability(data.employeeId(),data.date(),
                data.startTime(),data.endTime(),campus)) {
            throw new ApiException(HttpStatus.CONFLICT,"No approved availability covering this shift");
        }
        if (role.equals("CS")&&data.workType().equals("CLASS_SUPPORT")) {
            if (data.classId()==null) throw new ApiException(HttpStatus.BAD_REQUEST,"classId required for CLASS_SUPPORT");
            List<String> statuses=db.query("SELECT status FROM classes WHERE class_id=?",
                (rs,n)->rs.getString(1),data.classId());
            if (statuses.isEmpty()||!List.of("READY","RUNNING").contains(statuses.get(0))) {
                throw new ApiException(HttpStatus.BAD_REQUEST,"Class must be READY or RUNNING");
            }
            Integer planned=db.queryForObject(
                "SELECT count(*) FROM class_sessions WHERE class_id=? AND date=? " +
                "AND start_time=? AND end_time=? AND status='PLANNED'",
                Integer.class,data.classId(),data.date(),data.startTime(),data.endTime());
            if (planned == null || planned == 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "CS support must match a planned class session exactly");
            }
        }
        Integer conflict=db.queryForObject(
            "SELECT count(*) FROM staff_schedules WHERE employee_id=? AND date=? AND status='ASSIGNED' " +
            "AND staff_schedule_id<>? AND start_time<? AND end_time>?",
            Integer.class,data.employeeId(),data.date(),exceptId,data.endTime(),data.startTime());
        if (conflict!=null&&conflict>0) throw new ApiException(HttpStatus.CONFLICT,"STAFF_SCHEDULE_CONFLICT");
    }

    @Transactional
    public Map<String,Object> assignStaff(StaffRequest data) {
        var actor=access.requireRole("CM");
        access.lockEmployee(data.employeeId());
        if ("CS".equalsIgnoreCase(data.staffRole()) &&
            "CLASS_SUPPORT".equals(data.workType()) && data.classId()!=null) {
            lockClass(data.classId());
        }
        validateStaff(data,"");
        String id=UUID.randomUUID().toString();
        String classId="CS".equalsIgnoreCase(data.staffRole())&&"CLASS_SUPPORT".equals(data.workType())
            ?data.classId():null;
        db.update("INSERT INTO staff_schedules (staff_schedule_id,employee_id,class_id,workplace_id,"+
            "date,start_time,end_time,work_type,status,assigned_by) VALUES (?,?,?,?,?,?,?,?,'ASSIGNED',?)",
            id,data.employeeId(),classId,data.workplaceId(),data.date(),
            data.startTime(),data.endTime(),data.workType(),actor.id());
        access.audit(actor,"ASSIGN_STAFF_SHIFT","STAFF_SCHEDULE",id);
        return staffSlot(id);
    }

    @Transactional
    public Map<String,Object> editStaff(String id,StaffRequest data) {
        var actor=access.requireRole("CM");
        Map<String,Object> old=staffSlot(id);
        if (!"ASSIGNED".equals(old.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"Schedule is cancelled");
        if ("CLASS_SUPPORT".equals(old.get("workType")) &&
            (!old.get("workType").equals(data.workType()) ||
             !old.get("employeeId").equals(data.employeeId()) ||
             !java.util.Objects.equals(old.get("classId"), data.classId()) ||
             !old.get("date").toString().equals(data.date().toString()) ||
             !old.get("startTime").toString().startsWith(data.startTime().toString()) ||
             !old.get("endTime").toString().startsWith(data.endTime().toString()))) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Use class-planning staffing replacement to change a CS support lesson");
        }
        java.util.stream.Stream.of((String)old.get("employeeId"),data.employeeId())
            .distinct().sorted().forEach(access::lockEmployee);
        java.util.stream.Stream.of((String)old.get("classId"),data.classId())
            .filter(java.util.Objects::nonNull).distinct().sorted().forEach(this::lockClass);
        validateStaff(data,id);
        String classId="CS".equalsIgnoreCase(data.staffRole())&&"CLASS_SUPPORT".equals(data.workType())
            ?data.classId():null;
        db.update("UPDATE staff_schedules SET employee_id=?,class_id=?,workplace_id=?,date=?,start_time=?," +
            "end_time=?,work_type=? WHERE staff_schedule_id=?",data.employeeId(),classId,data.workplaceId(),
            data.date(),data.startTime(),data.endTime(),data.workType(),id);
        access.audit(actor,"UPDATE_STAFF_SHIFT","STAFF_SCHEDULE",id);
        return staffSlot(id);
    }

    @Transactional
    public void cancelStaff(String id) {
        var actor=access.requireRole("CM");
        Map<String,Object> old=staffSlot(id);
        access.lockEmployee((String)old.get("employeeId"));
        db.update("UPDATE staff_schedules SET status='CANCELLED' WHERE staff_schedule_id=?",id);
        if ("CLASS_SUPPORT".equals(old.get("workType")) && old.get("classId") != null) {
            // A CM-assigned course manager who no longer supports any lesson
            // must not retain class-wide edit rights indefinitely.
            db.update(
                "UPDATE class_access_scopes scope SET " +
                "status=CASE WHEN c.created_by=e.username THEN 'ACTIVE' ELSE 'INACTIVE' END, " +
                "source=CASE WHEN c.created_by=e.username THEN 'CREATED_BY_CS' " +
                "ELSE 'CM_ASSIGNED_COURSE' END " +
                "FROM classes c, employees e " +
                "WHERE scope.class_id=c.class_id AND scope.employee_id=e.employee_id " +
                "AND scope.class_id=? AND scope.employee_id=? AND scope.source IN " +
                "('CM_ASSIGNED_COURSE','ASSIGNED_SCOPE','AUTO_ASSIGNED') " +
                "AND scope.status='ACTIVE' AND NOT EXISTS (" +
                "SELECT 1 FROM staff_schedules s WHERE s.class_id=scope.class_id " +
                "AND s.employee_id=scope.employee_id AND s.work_type='CLASS_SUPPORT' " +
                "AND s.status='ASSIGNED')",
                old.get("classId"), old.get("employeeId"));
        }
        access.audit(actor,"CANCEL_STAFF_SHIFT","STAFF_SCHEDULE",id);
    }
}
