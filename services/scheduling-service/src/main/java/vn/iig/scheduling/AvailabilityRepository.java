package vn.iig.scheduling;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.iig.scheduling.SchedulingModels.*;

/**
 * The authoritative availability write model. An employee-scoped PostgreSQL
 * advisory transaction lock serializes register/edit/delete with temporary
 * reservations created by Core assignment transactions.
 */
@Repository
public class AvailabilityRepository {
    public record Pending(Slot slot,long version) {}
    public record AuditEvent(String eventId,String username,String action,String entityId) {}
    private final JdbcTemplate db;
    private static final String SELECT =
        "SELECT availability_id,employee_id,full_name,role_id,available_date," +
        "day_of_week,shift,start_time,end_time,availability_type,campus,note,status," +
        "created_at,reviewed_at,reviewed_by,version,deleted FROM scheduling_availabilities";

    public AvailabilityRepository(JdbcTemplate db) { this.db=db; }

    private Slot map(ResultSet rs) throws SQLException {
        return new Slot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
            rs.getObject(5,LocalDate.class),rs.getString(6),rs.getString(7),
            rs.getObject(8,LocalTime.class),rs.getObject(9,LocalTime.class),
            rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),
            rs.getTimestamp(14).toInstant(),
            rs.getTimestamp(15)==null?null:rs.getTimestamp(15).toInstant(),
            rs.getString(16),rs.getLong(17),rs.getBoolean(18));
    }

    public boolean imported() {
        Integer count=db.queryForObject(
            "SELECT count(*) FROM scheduling_metadata WHERE key='availability-imported'",
            Integer.class);
        return count!=null && count>0;
    }

    @Transactional
    public void importInitial(List<Slot> rows) {
        if(imported()) return;
        for(Slot r:rows) {
            if(r==null || r.id()==null || r.employeeId()==null || r.startTime()==null
                    || r.endTime()==null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Malformed legacy availability data");
            }
            String day=r.dayOfWeek()==null && r.availableDate()!=null ?
                r.availableDate().getDayOfWeek().name().substring(0,3)
                    .toLowerCase(Locale.ROOT) : r.dayOfWeek();
            if(day==null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Legacy recurring availability has no weekday");
            String kind=r.availabilityType()==null?
                (r.availableDate()==null?"RECURRING":"WEEKLY") : r.availabilityType();
            int added=db.update(
                "INSERT INTO scheduling_availabilities (availability_id,employee_id,full_name," +
                "role_id,available_date,day_of_week,shift,start_time,end_time," +
                "availability_type,campus,note,status,created_at,reviewed_at," +
                "reviewed_by,version,deleted) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,FALSE) " +
                "ON CONFLICT(availability_id) DO NOTHING",
                r.id(),r.employeeId(),r.fullName(),r.role(),r.availableDate(),day,
                r.shift(),r.startTime(),r.endTime(),kind,r.campus(),r.note(),
                r.status()==null?"PENDING":r.status().toUpperCase(Locale.ROOT),
                Timestamp.from(r.createdAt()==null?Instant.now():r.createdAt()),
                r.reviewedAt()==null?null:Timestamp.from(r.reviewedAt()),r.reviewedBy());
            if(added==1) enqueue(r.id(),1);
        }
        db.update("INSERT INTO scheduling_metadata(key,value) VALUES('availability-imported','yes') " +
            "ON CONFLICT(key) DO NOTHING");
    }

    public void lockEmployee(String employeeId) {
        // Deliberately serialize all same-worker approval/mutation/reservation.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",employeeId);
    }

    public Slot get(String id) {
        List<Slot> rows=db.query(SELECT+" WHERE availability_id=? AND deleted=FALSE",
            (rs,i)->map(rs),id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Availability not found");
        return rows.get(0);
    }

    public Slot getForUpdate(String id) {
        List<Slot> rows=db.query(SELECT+" WHERE availability_id=? AND deleted=FALSE FOR UPDATE",
            (rs,i)->map(rs),id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Availability not found");
        return rows.get(0);
    }

    public List<Slot> list(String employeeId,LocalDate from,LocalDate to,
                           String role,String status) {
        return db.query(SELECT+" WHERE deleted=FALSE AND " +
            "(CAST(? AS varchar) IS NULL OR employee_id=?) " +
            "AND (CAST(? AS varchar) IS NULL OR role_id=?) " +
            "AND (CAST(? AS varchar) IS NULL OR status=?) " +
            "AND (available_date IS NULL OR available_date BETWEEN ? AND ?) " +
            "ORDER BY full_name,day_of_week,start_time,availability_id",
            (rs,i)->map(rs),employeeId,employeeId,role,role,status,status,from,to);
    }

    public void noOverlap(String employeeId,AvailabilityRequest data,String day,String except) {
        List<Slot> conflicts=db.query(SELECT+
            " WHERE deleted=FALSE AND employee_id=? AND availability_id<>? " +
            "AND status<>'REJECTED' AND " +
            "((CAST(? AS date) IS NULL AND lower(day_of_week)=?) " +
            "OR available_date=? OR (available_date IS NULL AND lower(day_of_week)=?)) " +
            "AND start_time<? AND end_time>? ORDER BY available_date NULLS FIRST,start_time LIMIT 1",
            (rs,i)->map(rs),employeeId,except,data.availableDate(),day,
            data.availableDate(),day,data.endTime(),data.startTime());
        if(!conflicts.isEmpty()) {
            Slot first=conflicts.get(0);
            String scope=first.availableDate()==null?"định kỳ "+first.dayOfWeek():
                "ngày "+first.availableDate();
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Khung giờ trùng với lịch đã đăng ký ("+scope+"): "+
                    first.startTime()+"–"+first.endTime()+
                    ". Vui lòng chọn khung giờ khác hoặc chỉnh sửa lịch hiện có.");
        }
    }

    @Transactional
    public Slot create(Actor actor,AvailabilityRequest body,String day,String kind) {
        lockEmployee(actor.employeeId());
        noOverlap(actor.employeeId(),body,day,"");
        String id=UUID.randomUUID().toString();
        db.update(
            "INSERT INTO scheduling_availabilities (availability_id,employee_id,full_name," +
            "role_id,available_date,day_of_week,shift,start_time,end_time," +
            "availability_type,campus,note,status) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')",
            id,actor.employeeId(),actor.fullName(),actor.role(),body.availableDate(),
            day,body.shift(),body.startTime(),body.endTime(),kind,body.campus(),body.note());
        enqueue(id,1);
        appendAudit(actor,"REGISTER_AVAILABILITY",id);
        return get(id);
    }

    @Transactional
    public Slot update(String id,Actor actor,AvailabilityRequest body,
                       String day,String kind,SchedulingCoreClient core) {
        Slot prior=get(id);
        if(!prior.employeeId().equals(actor.employeeId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Not your availability");
        lockEmployee(actor.employeeId());
        Slot current=getForUpdate(id);
        assertNotReserved(current);
        core.guardEditable(current);
        noOverlap(actor.employeeId(),body,day,id);
        long v=current.version()+1;
        db.update("UPDATE scheduling_availabilities SET available_date=?,day_of_week=?," +
            "shift=?,start_time=?,end_time=?,availability_type=?,campus=?,note=?," +
            "status='PENDING',reviewed_at=NULL,reviewed_by=NULL,version=? " +
            "WHERE availability_id=?",
            body.availableDate(),day,body.shift(),body.startTime(),body.endTime(),
            kind,body.campus(),body.note(),v,id);
        enqueue(id,v);
        appendAudit(actor,"UPDATE_AVAILABILITY",id);
        return get(id);
    }

    @Transactional
    public void delete(String id,Actor actor,SchedulingCoreClient core) {
        Slot prior=get(id);
        if(!prior.employeeId().equals(actor.employeeId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Not your availability");
        lockEmployee(actor.employeeId());
        Slot current=getForUpdate(id);
        assertNotReserved(current);
        core.guardEditable(current);
        long v=current.version()+1;
        db.update("UPDATE scheduling_availabilities SET deleted=TRUE,version=? " +
            "WHERE availability_id=?",v,id);
        enqueue(id,v);
        appendAudit(actor,"DELETE_AVAILABILITY",id);
    }

    @Transactional
    public Slot review(String id,Actor actor,String status) {
        Slot prior=get(id);
        lockEmployee(prior.employeeId());
        Slot current=getForUpdate(id);
        if("TEACHER".equals(current.role()) && !"TC".equals(actor.role()) ||
           !"TEACHER".equals(current.role()) && !"CM".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Reviewer role not permitted");
        if(!"PENDING".equals(current.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only pending availability can be reviewed");
        long v=current.version()+1;
        db.update("UPDATE scheduling_availabilities SET status=?,reviewed_at=NOW()," +
            "reviewed_by=?,version=? WHERE availability_id=?",
            status,actor.employeeId(),v,id);
        enqueue(id,v);
        appendAudit(actor,"REVIEW_AVAILABILITY",id);
        return get(id);
    }

    public void assertNotReserved(Slot current) {
        Long active=db.queryForObject(
            "SELECT count(*) FROM scheduling_availability_reservations " +
            "WHERE availability_id=? AND expires_at>NOW()",
            Long.class,current.id());
        if(active!=null && active>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Availability is reserved by an in-flight assignment");
    }

    private void appendAudit(Actor actor,String action,String id) {
        db.update("INSERT INTO scheduling_availability_audit_outbox " +
            "(event_id,username,action,availability_id) VALUES(?,?,?,?)",
            UUID.randomUUID().toString(),actor.username(),action,id);
    }

    public List<AuditEvent> pendingAudits(int limit) {
        return db.query("SELECT event_id,username,action,availability_id " +
            "FROM scheduling_availability_audit_outbox WHERE delivered_at IS NULL " +
            "ORDER BY created_at,event_id LIMIT ?",
            (rs,i)->new AuditEvent(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4)),limit);
    }

    public void acknowledgeAudit(String id) {
        db.update("UPDATE scheduling_availability_audit_outbox " +
            "SET delivered_at=NOW() WHERE event_id=? AND delivered_at IS NULL",id);
    }

    private void enqueue(String id,long version) {
        db.update("INSERT INTO scheduling_availability_outbox(availability_id,version,delivered_at) " +
            "VALUES(?,?,NULL) ON CONFLICT(availability_id) DO UPDATE " +
            "SET version=EXCLUDED.version,delivered_at=NULL",id,version);
    }

    public List<Pending> pending(int max) {
        return db.query("SELECT a.availability_id,a.employee_id,a.full_name,a.role_id," +
            "a.available_date,a.day_of_week,a.shift,a.start_time,a.end_time," +
            "a.availability_type,a.campus,a.note,a.status,a.created_at,a.reviewed_at," +
            "a.reviewed_by,a.version,a.deleted FROM scheduling_availability_outbox o " +
            "JOIN scheduling_availabilities a ON a.availability_id=o.availability_id " +
            "WHERE o.delivered_at IS NULL ORDER BY a.availability_id LIMIT ?",
            (rs,i)->new Pending(map(rs),rs.getLong(17)),max);
    }

    public void acknowledge(String id,long version) {
        db.update("UPDATE scheduling_availability_outbox SET delivered_at=NOW() " +
            "WHERE availability_id=? AND version=? AND delivered_at IS NULL",id,version);
    }

    /**
     * Serialize overlapping in-flight assignment commands across all future
     * writer services, rather than relying on Core's employee row lock alone.
     * Only short-lived intents are checked here: the durable existing schedule
     * conflict and academic invariants still belong to Core in phase 8b.
     * Must run after lockEmployee() inside the reservation transaction.
     */
    void assertNoConcurrentAssignmentIntent(ReservationRequest request) {
        Long busy=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_intent_reservations i " +
            "JOIN scheduling_availability_reservations lease " +
            "ON lease.reservation_id=i.reservation_id " +
            "WHERE i.employee_id=? AND i.work_date=? AND i.start_time<? " +
            "AND i.end_time>? AND lease.expires_at>NOW()",
            Long.class,request.employeeId(),request.date(),
            request.endTime(),request.startTime());
        if(busy!=null && busy>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "ASSIGNMENT_IN_PROGRESS: Employee already has an in-flight overlapping assignment");
    }

    /** Reserve with the SAME per-employee lock that guards editing/deleting. */
    @Transactional
    public Lease reserve(ReservationRequest request) {
        if(request==null || request.employeeId()==null || request.date()==null ||
           request.startTime()==null || request.endTime()==null ||
           !request.endTime().isAfter(request.startTime()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid assignment time");
        lockEmployee(request.employeeId());
        String day=request.date().getDayOfWeek().name().substring(0,3)
            .toLowerCase(Locale.ROOT);
        List<String> matches=db.query(
            "SELECT availability_id FROM scheduling_availabilities " +
            "WHERE employee_id=? AND deleted=FALSE AND status='APPROVED' " +
            "AND start_time<=? AND end_time>=? " +
            "AND (available_date=? OR (available_date IS NULL AND lower(day_of_week)=?)) " +
            "AND (CAST(? AS varchar) IS NULL OR campus IS NULL OR btrim(campus)='' " +
            "OR lower(btrim(campus))=lower(btrim(?))) " +
            "ORDER BY available_date DESC NULLS LAST,availability_id LIMIT 1",
            (rs,i)->rs.getString(1),request.employeeId(),
            request.startTime(),request.endTime(),request.date(),day,
            request.campus(),request.campus());
        if(matches.isEmpty()) return new Lease(false,null);
        // The advisory employee lock covers this check AND both inserts.
        assertNoConcurrentAssignmentIntent(request);
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO scheduling_availability_reservations " +
            "(reservation_id,availability_id,employee_id,expires_at) " +
            "VALUES(?,?,?,NOW()+interval '10 minutes')",
            id,matches.get(0),request.employeeId());
        db.update("INSERT INTO scheduling_assignment_intent_reservations " +
            "(reservation_id,employee_id,work_date,start_time,end_time) " +
            "VALUES(?,?,?,?,?)",
            id,request.employeeId(),request.date(),request.startTime(),request.endTime());
        return new Lease(true,id);
    }

    public void release(String id) {
        db.update("DELETE FROM scheduling_availability_reservations WHERE reservation_id=?",id);
    }

    public void cleanupExpired() {
        db.update("DELETE FROM scheduling_availability_reservations WHERE expires_at<=NOW()");
    }
}
