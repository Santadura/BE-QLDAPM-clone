package com.example.demo.service;

import com.example.demo.dto.PayrollDtos;
import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PayrollService {
    private static final Set<String> PAYROLL_ROLES = Set.of("TEACHER", "SALE", "CS");
    private final JdbcTemplate db;
    private final SchedulingAccess access;

    private record Viewer(String employeeId, String username, String role) {}

    private Viewer viewer() {
        var active = access.requireActiveRole("ADMIN", "TC", "CM", "TEACHER", "SALE", "CS");
        List<Map<String,Object>> rows = db.queryForList("SELECT employee_id FROM employees WHERE username=? AND status='ACTIVE'", active.username());
        return new Viewer(rows.isEmpty() ? null : (String) rows.get(0).get("employee_id"), active.username(), active.role());
    }
    private static YearMonth period(String value) {
        try { return YearMonth.parse(value); } catch (Exception ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "period must use YYYY-MM"); }
    }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static BigDecimal money(Object value) { return value instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(value)); }
    private String employeeRole(String employeeId) {
        List<String> roles = db.query("SELECT u.role_id FROM employees e JOIN users u ON u.username=e.username WHERE e.employee_id=?", (rs,n)->rs.getString(1), employeeId);
        if (roles.isEmpty() || !PAYROLL_ROLES.contains(roles.get(0))) throw new ApiException(HttpStatus.BAD_REQUEST, "Employee must be Teacher, Sale or CS");
        return roles.get(0);
    }
    private boolean manages(String viewerRole, String targetRole) {
        return "ADMIN".equals(viewerRole) || ("TC".equals(viewerRole) && "TEACHER".equals(targetRole)) ||
                ("CM".equals(viewerRole) && ("SALE".equals(targetRole) || "CS".equals(targetRole)));
    }
    private void canRead(Viewer viewer, String employeeId) {
        String targetRole = employeeRole(employeeId);
        if (employeeId.equals(viewer.employeeId()) || manages(viewer.role(), targetRole)) return;
        throw new ApiException(HttpStatus.FORBIDDEN, "You may only view payroll within your scope");
    }
    private void canManage(Viewer viewer, String employeeId) {
        if (!manages(viewer.role(), employeeRole(employeeId))) throw new ApiException(HttpStatus.FORBIDDEN, "You may only manage payroll within your scope");
    }

    public List<Map<String,Object>> ranks(String roleId) {
        Viewer v = viewer();
        String requested = roleId == null || roleId.isBlank() ? null : roleId.trim().toUpperCase();
        if (!"ADMIN".equals(v.role())) {
            Set<String> permitted = "TC".equals(v.role()) ? Set.of("TEACHER") :
                    "CM".equals(v.role()) ? Set.of("SALE", "CS") : Set.of();
            if (permitted.isEmpty() || (requested != null && !permitted.contains(requested))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You may only view salary ranks within your management scope");
            }
            String placeholders = String.join(",", Collections.nCopies(permitted.size(), "?"));
            return db.queryForList("SELECT salary_rank_id AS id,role_id AS \"roleId\",rank,amount,status,created_at AS \"createdAt\" FROM salary_ranks WHERE role_id IN (" + placeholders + ") ORDER BY role_id,amount", permitted.toArray());
        }
        String sql = "SELECT salary_rank_id AS id, role_id AS \"roleId\", rank, amount, status, created_at AS \"createdAt\" FROM salary_ranks" +
                (requested == null ? "" : " WHERE role_id=?") + " ORDER BY role_id, amount";
        return requested == null ? db.queryForList(sql) : db.queryForList(sql, requested);
    }
    @Transactional public Map<String,Object> createRank(PayrollDtos.SalaryRankRequest request) {
        Viewer v = viewer(); if (!"ADMIN".equals(v.role())) throw new ApiException(HttpStatus.FORBIDDEN, "Only Admin manages salary ranks");
        String role = request.roleId().trim().toUpperCase(); if (!PAYROLL_ROLES.contains(role)) throw new ApiException(HttpStatus.BAD_REQUEST, "Salary rank role must be TEACHER, SALE or CS");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO salary_ranks(salary_rank_id,role_id,rank,amount,status) VALUES(?,?,?,?,?)", id, role, request.rank().trim(), request.amount(), request.status() == null ? "ACTIVE" : request.status().trim().toUpperCase());
        return db.queryForMap("SELECT salary_rank_id AS id,role_id AS \"roleId\",rank,amount,status FROM salary_ranks WHERE salary_rank_id=?", id);
    }
    @Transactional public Map<String,Object> updateRank(String id, PayrollDtos.SalaryRankUpdate request) {
        Viewer v = viewer(); if (!"ADMIN".equals(v.role())) throw new ApiException(HttpStatus.FORBIDDEN, "Only Admin manages salary ranks");
        int changed = db.update("UPDATE salary_ranks SET rank=?,amount=?,status=COALESCE(?,status) WHERE salary_rank_id=?", request.rank().trim(), request.amount(), request.status() == null ? null : request.status().trim().toUpperCase(), id);
        if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Salary rank not found");
        return db.queryForMap("SELECT salary_rank_id AS id,role_id AS \"roleId\",rank,amount,status FROM salary_ranks WHERE salary_rank_id=?", id);
    }
    @Transactional public List<Map<String,Object>> assignRank(String employeeId, PayrollDtos.AssignSalaryRank request) {
        Viewer v = viewer(); if (!"ADMIN".equals(v.role())) throw new ApiException(HttpStatus.FORBIDDEN, "Only Admin assigns salary ranks");
        String role=employeeRole(employeeId);
        List<Map<String,Object>> ranks=db.queryForList("SELECT amount FROM salary_ranks WHERE salary_rank_id=? AND role_id=? AND status='ACTIVE'",request.salaryRankId(),role);
        if(ranks.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"Salary rank must be active and match employee role");
        LocalDate effective=request.effectiveDate()==null?LocalDate.now():request.effectiveDate();
        if(effective.isAfter(LocalDate.now())) throw new ApiException(HttpStatus.BAD_REQUEST,"effectiveDate cannot be in the future");
        db.update("UPDATE employees SET salary_rank_id=?,reviewed_date=now() WHERE employee_id=?",request.salaryRankId(),employeeId);
        db.update("INSERT INTO salary_rank_history(history_id,employee_id,salary_rank_id,amount,effective_at,approved_by,reason) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),employeeId,request.salaryRankId(),ranks.get(0).get("amount"),effective.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),v.employeeId(),request.note() == null ? "Admin assigned salary rank" : request.note());
        db.update("INSERT INTO audit_logs(audit_log_id,username,action,entity_type,entity_id,description) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),v.username(),"ASSIGN_SALARY_RANK","EMPLOYEE",employeeId,request.note());
        return history(employeeId);
    }

    @Transactional public List<Map<String,Object>> calculate(String value, String employeeId) {
        Viewer v = viewer(); YearMonth ym = period(value);
        List<String> ids;
        if (employeeId != null && !employeeId.isBlank()) { canManage(v, employeeId); ids = List.of(employeeId); }
        else {
            if (!Set.of("ADMIN","TC","CM").contains(v.role())) throw new ApiException(HttpStatus.FORBIDDEN, "Manager role required");
            String filter = "ADMIN".equals(v.role()) ? "('TEACHER','SALE','CS')" : "TC".equals(v.role()) ? "('TEACHER')" : "('SALE','CS')";
            ids = db.query("SELECT e.employee_id FROM employees e JOIN users u ON u.username=e.username WHERE e.status='ACTIVE' AND u.role_id IN " + filter, (rs,n)->rs.getString(1));
        }
        for (String id : ids) calculateOne(ym, id, v.employeeId());
        return payrolls(value, employeeId);
    }
    private void calculateOne(YearMonth ym, String employeeId, String actorId) {
        String p = ym.toString(); String role = employeeRole(employeeId);
        List<Map<String,Object>> sources = "TEACHER".equals(role)
                ? db.queryForList("SELECT t.teaching_schedule_id AS id,t.date,t.start_time,t.end_time,c.name AS work_name FROM teaching_schedules t JOIN teacher_session_attendance a ON a.teaching_schedule_id=t.teaching_schedule_id AND a.status IN ('PRESENT','LATE') JOIN classes c ON c.class_id=t.class_id WHERE t.teacher_id=? AND t.status='ASSIGNED' AND t.date BETWEEN ? AND ?", employeeId, ym.atDay(1), ym.atEndOfMonth())
                : db.queryForList("SELECT staff_schedule_id AS id,date,start_time,end_time,COALESCE(work_type,'Ca làm việc') AS work_name FROM staff_schedules WHERE employee_id=? AND status='ASSIGNED' AND date BETWEEN ? AND ?", employeeId, ym.atDay(1), ym.atEndOfMonth());
        boolean teacher = "TEACHER".equals(role);
        String payrollTable = teacher ? "employee_salaries" : "staff_salaries";
        String idColumn = teacher ? "employee_salary_id" : "staff_salary_id";
        List<String> existing = db.query("SELECT " + idColumn + " FROM " + payrollTable + " WHERE employee_id=? AND period=? FOR UPDATE", (rs,n)->rs.getString(1), employeeId,p);
        String payrollId = existing.isEmpty() ? UUID.randomUUID().toString() : existing.get(0);
        if (existing.isEmpty()) {
            if (teacher) db.update("INSERT INTO employee_salaries(employee_salary_id,employee_id,period) VALUES(?,?,?)", payrollId,employeeId,p);
            else db.update("INSERT INTO staff_salaries(staff_salary_id,employee_id,period,managed_by) VALUES(?,?,?,?)", payrollId,employeeId,p,actorId);
        }
        String source = teacher ? "EMPLOYEE" : "STAFF";
        db.update("DELETE FROM salary_calculation_items WHERE payroll_source=? AND payroll_id=?", source, payrollId);
        BigDecimal total = BigDecimal.ZERO, hours = BigDecimal.ZERO;
        for (Map<String,Object> s : sources) {
            LocalDate day = ((java.sql.Date)s.get("date")).toLocalDate();
            BigDecimal h = BigDecimal.valueOf(((java.sql.Time)s.get("end_time")).toLocalTime().toSecondOfDay() - ((java.sql.Time)s.get("start_time")).toLocalTime().toSecondOfDay()).divide(BigDecimal.valueOf(3600));
            BigDecimal rate = rateOn(employeeId, day); BigDecimal amount = rate.multiply(h);
            db.update("INSERT INTO salary_calculation_items(item_id,payroll_source,payroll_id,schedule_id,work_date,work_type,attendance_status,worked_hours,salary_rank_id,hourly_rate,pay_factor,amount) VALUES(?,?,?,?,?,?,?,?,NULL,?,?,?)", UUID.randomUUID().toString(),source,payrollId,s.get("id"),day,teacher ? "TEACHING" : "STAFF","PRESENT",h,rate,BigDecimal.ONE,amount);
            total=total.add(amount); hours=hours.add(h);
        }
        if (teacher) db.update("UPDATE employee_salaries SET total_sessions=?,total_hours=?,rate=?,amount=?,status='CALCULATED' WHERE employee_salary_id=?", sources.size(),hours,sources.isEmpty()?BigDecimal.ZERO:total.divide(hours, 2, java.math.RoundingMode.HALF_UP),total,payrollId);
        else db.update("UPDATE staff_salaries SET total_shifts=?,total_hours=?,rate=?,amount=?,status='CALCULATED',managed_by=? WHERE staff_salary_id=?", sources.size(),hours,sources.isEmpty()?BigDecimal.ZERO:total.divide(hours, 2, java.math.RoundingMode.HALF_UP),total,actorId,payrollId);
    }
    private BigDecimal rateOn(String employeeId, LocalDate date) {
        List<BigDecimal> history = db.query("SELECT amount FROM salary_rank_history WHERE employee_id=? AND effective_at::date<=? ORDER BY effective_at DESC,created_at DESC LIMIT 1", (rs,n)->rs.getBigDecimal(1),employeeId,date);
        if (!history.isEmpty()) return history.get(0);
        List<BigDecimal> rank = db.query("SELECT COALESCE((SELECT r.amount FROM employees e JOIN salary_ranks r ON r.salary_rank_id=e.salary_rank_id WHERE e.employee_id=?),(SELECT r.amount FROM employees e JOIN users u ON u.username=e.username JOIN salary_ranks r ON r.role_id=u.role_id AND r.status='ACTIVE' WHERE e.employee_id=? ORDER BY r.amount LIMIT 1))",(rs,n)->rs.getBigDecimal(1),employeeId,employeeId);
        return rank.isEmpty() || rank.get(0) == null ? BigDecimal.ZERO : rank.get(0);
    }
    public List<Map<String,Object>> payrolls(String value, String employeeId) {
        Viewer v=viewer(); period(value);
        if (employeeId != null && !employeeId.isBlank()) canRead(v,employeeId);
        else if (!Set.of("ADMIN","TC","CM").contains(v.role())) employeeId=v.employeeId();
        String filter = employeeId == null || employeeId.isBlank() ? "" : " AND e.employee_id=?";
        String manager = "TC".equals(v.role()) ? " AND u.role_id='TEACHER'" : "CM".equals(v.role()) ? " AND u.role_id IN ('SALE','CS')" : "";
        String sql="SELECT * FROM (" +
                "SELECT s.employee_salary_id AS id,s.period,s.employee_id AS \"employeeId\",e.full_name AS \"employeeName\",e.employee_code AS \"employeeCode\",u.role_id AS \"roleId\",s.total_sessions AS \"totalSessions\",s.total_hours AS \"totalHours\",s.amount AS \"totalAmount\",s.created_at AS \"calculatedAt\",'EMPLOYEE' AS source FROM employee_salaries s JOIN employees e ON e.employee_id=s.employee_id JOIN users u ON u.username=e.username WHERE s.period=?"+filter+manager+
                " UNION ALL SELECT s.staff_salary_id AS id,s.period,s.employee_id AS \"employeeId\",e.full_name AS \"employeeName\",e.employee_code AS \"employeeCode\",u.role_id AS \"roleId\",s.total_shifts AS \"totalSessions\",s.total_hours AS \"totalHours\",s.amount AS \"totalAmount\",s.created_at AS \"calculatedAt\",'STAFF' AS source FROM staff_salaries s JOIN employees e ON e.employee_id=s.employee_id JOIN users u ON u.username=e.username WHERE s.period=?"+filter+manager+
                ") p ORDER BY \"employeeName\"";
        if (employeeId == null || employeeId.isBlank()) return db.queryForList(sql,value,value);
        return db.queryForList(sql,value,employeeId,value,employeeId);
    }
    public Map<String,Object> payrollDetail(String employeeId,String value) {
        Viewer v=viewer(); canRead(v,employeeId); period(value);
        List<Map<String,Object>> rows=payrolls(value,employeeId); if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Payroll has not been calculated for this period");
        Map<String,Object> result=new LinkedHashMap<>(rows.get(0)); result.put("items",db.queryForList("SELECT item_id AS id,work_date AS \"workDate\",work_type AS \"sourceType\",work_type AS \"workName\",worked_hours AS hours,hourly_rate AS rate,amount FROM salary_calculation_items WHERE payroll_source=? AND payroll_id=? ORDER BY work_date",result.get("source"),result.get("id"))); return result;
    }

    @Transactional public Map<String,Object> createReview(PayrollDtos.ReviewRequest request) {
        Viewer v=viewer(); String role=employeeRole(request.employeeId());
        if (!(request.employeeId().equals(v.employeeId()) || manages(v.role(),role))) throw new ApiException(HttpStatus.FORBIDDEN,"You may only request review within your scope");
        Integer eligible=db.queryForObject("SELECT count(*) FROM employees WHERE employee_id=? AND created_at <= now() - interval '6 months'",Integer.class,request.employeeId());
        Integer previouslyRejected=db.queryForObject("SELECT count(*) FROM salary_review_requests WHERE employee_id=? AND status='REJECTED'",Integer.class,request.employeeId());
        // A rejected request may be corrected and re-submitted immediately;
        // six-month tenure gates only the employee's first review request.
        if ((eligible==null || eligible==0) && (previouslyRejected==null || previouslyRejected==0)) {
            throw new ApiException(HttpStatus.CONFLICT,"Employee must complete at least 6 months before a salary review");
        }
        Integer validRank=db.queryForObject("SELECT count(*) FROM salary_ranks WHERE salary_rank_id=? AND role_id=? AND status='ACTIVE'",Integer.class,request.proposedSalaryRankId(),role);
        if(validRank==null||validRank==0) throw new ApiException(HttpStatus.BAD_REQUEST,"Proposed rank must be active and match employee role");
        String id=UUID.randomUUID().toString();
        BigDecimal proposedAmount=db.queryForObject("SELECT amount FROM salary_ranks WHERE salary_rank_id=?",BigDecimal.class,request.proposedSalaryRankId());
        db.update("INSERT INTO salary_review_requests(request_id,employee_id,current_salary_rank_id,proposed_salary_rank_id,proposed_amount,requested_by,reason,status) SELECT ?,e.employee_id,e.salary_rank_id,?,?,?,?,? FROM employees e WHERE e.employee_id=?",id,request.proposedSalaryRankId(),proposedAmount,v.employeeId(),request.reason().trim(),"PENDING",request.employeeId());
        db.update("INSERT INTO audit_logs(audit_log_id,username,action,entity_type,entity_id,description) VALUES(?,?,?,?,?,?)", UUID.randomUUID().toString(), v.username(), "CREATE_SALARY_REVIEW", "SALARY_REVIEW", id, request.reason().trim());
        return reviewById(id);
    }
    public List<Map<String,Object>> reviews(String status) {
        Viewer v=viewer(); if(!"ADMIN".equals(v.role())) throw new ApiException(HttpStatus.FORBIDDEN,"Only Admin can view review requests");
        String condition=status==null||status.isBlank()?"":" WHERE r.status=?";
        String sql="SELECT r.request_id AS id,r.employee_id AS \"employeeId\",e.full_name AS \"employeeName\",u.role_id AS \"roleId\",r.status,r.reason,r.decision_note AS \"adminNote\",r.requested_at AS \"createdAt\",r.reviewed_at AS \"reviewedAt\",old.rank AS \"currentRank\",next.rank AS \"proposedRank\",r.proposed_amount AS \"proposedAmount\" FROM salary_review_requests r JOIN employees e ON e.employee_id=r.employee_id JOIN users u ON u.username=e.username LEFT JOIN salary_ranks old ON old.salary_rank_id=r.current_salary_rank_id JOIN salary_ranks next ON next.salary_rank_id=r.proposed_salary_rank_id"+condition+" ORDER BY r.requested_at DESC";
        return condition.isEmpty()?db.queryForList(sql):db.queryForList(sql,status.trim().toUpperCase());
    }
    @Transactional public Map<String,Object> decideReview(String id, PayrollDtos.ReviewDecision decision) {
        Viewer v=viewer(); if(!"ADMIN".equals(v.role())) throw new ApiException(HttpStatus.FORBIDDEN,"Only Admin approves salary reviews");
        String action=decision.decision().trim().toUpperCase(); if(!Set.of("APPROVE","REJECT").contains(action)) throw new ApiException(HttpStatus.BAD_REQUEST,"decision must be APPROVE or REJECT");
        List<Map<String,Object>> rows=db.queryForList("SELECT employee_id,proposed_salary_rank_id,current_salary_rank_id,status FROM salary_review_requests WHERE request_id=? FOR UPDATE",id); if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Salary review request not found");
        Map<String,Object> r=rows.get(0); if(!"PENDING".equals(r.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"Review request was already decided");
        String state="APPROVE".equals(action)?"APPROVED":"REJECTED";
        db.update("UPDATE salary_review_requests SET status=?,decision_note=?,reviewed_by=?,reviewed_at=now() WHERE request_id=?",state,decision.note(),v.employeeId(),id);
        if("APPROVED".equals(state)) {
            String employeeId=text(r.get("employee_id")), rankId=text(r.get("proposed_salary_rank_id"));
            // Preserve the pre-approval rate as a baseline before switching the
            // employee's current rank. This makes a later recalculation of an
            // old month use the old rate rather than the newly approved one.
            BigDecimal amount=db.queryForObject("SELECT amount FROM salary_ranks WHERE salary_rank_id=?",BigDecimal.class,rankId); LocalDate effective=LocalDate.now();
            db.update("UPDATE employees SET salary_rank_id=?,reviewed_date=now() WHERE employee_id=?",rankId,employeeId);
            db.update("INSERT INTO salary_rank_history(history_id,employee_id,previous_salary_rank_id,salary_rank_id,amount,effective_at,review_request_id,approved_by,reason) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),employeeId,r.get("current_salary_rank_id"),rankId,amount,effective.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),id,v.employeeId(),decision.note() == null ? "Approved salary review" : decision.note());
        }
        db.update("INSERT INTO audit_logs(audit_log_id,username,action,entity_type,entity_id,description) VALUES(?,?,?,?,?,?)", UUID.randomUUID().toString(), v.username(), "SALARY_REVIEW_" + state, "SALARY_REVIEW", id, decision.note());
        return reviewById(id);
    }
    private Map<String,Object> reviewById(String id) {
        List<Map<String,Object>> rows = db.queryForList("SELECT r.request_id AS id,r.employee_id AS \"employeeId\",e.full_name AS \"employeeName\",u.role_id AS \"roleId\",r.status,r.reason,r.decision_note AS \"adminNote\",r.requested_at AS \"createdAt\",r.reviewed_at AS \"reviewedAt\",old.rank AS \"currentRank\",next.rank AS \"proposedRank\",r.proposed_amount AS \"proposedAmount\" FROM salary_review_requests r JOIN employees e ON e.employee_id=r.employee_id JOIN users u ON u.username=e.username LEFT JOIN salary_ranks old ON old.salary_rank_id=r.current_salary_rank_id JOIN salary_ranks next ON next.salary_rank_id=r.proposed_salary_rank_id WHERE r.request_id=?", id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Salary review request not found");
        return rows.get(0);
    }
    public List<Map<String,Object>> history(String employeeId) { Viewer v=viewer(); canRead(v,employeeId); return db.queryForList("SELECT h.history_id AS id,h.amount,h.effective_at AS \"effectiveDate\",h.reason AS note,h.created_at AS \"createdAt\",r.rank,r.role_id AS \"roleId\" FROM salary_rank_history h JOIN salary_ranks r ON r.salary_rank_id=h.salary_rank_id WHERE h.employee_id=? ORDER BY h.effective_at DESC,h.created_at DESC",employeeId); }
    public Map<String,Object> salaryProfile(String employeeId) {
        Viewer v = viewer(); canRead(v, employeeId);
        List<Map<String,Object>> rows = db.queryForList("SELECT e.employee_id AS \"employeeId\",e.full_name AS \"employeeName\",e.employee_code AS \"employeeCode\",u.role_id AS \"roleId\",COALESCE(r.salary_rank_id,d.salary_rank_id) AS \"salaryRankId\",COALESCE(r.rank,d.rank) AS \"currentRank\",COALESCE(r.amount,d.amount) AS \"currentAmount\",(r.salary_rank_id IS NULL) AS \"usingDefaultRank\",e.reviewed_date AS \"reviewedDate\" FROM employees e JOIN users u ON u.username=e.username LEFT JOIN salary_ranks r ON r.salary_rank_id=e.salary_rank_id LEFT JOIN LATERAL (SELECT salary_rank_id,rank,amount FROM salary_ranks WHERE role_id=u.role_id AND status='ACTIVE' ORDER BY amount LIMIT 1) d ON true WHERE e.employee_id=?", employeeId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Employee not found");
        return rows.get(0);
    }
}
