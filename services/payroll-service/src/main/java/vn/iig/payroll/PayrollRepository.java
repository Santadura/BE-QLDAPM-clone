package vn.iig.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.iig.payroll.PayrollContracts.*;

/**
 * Payroll owns transactionality, calculation, historical ledger and line items.
 * The remote Core is used only before this local transaction starts.
 */
@Repository
public class PayrollRepository {
    private final JdbcTemplate db;
    public PayrollRepository(JdbcTemplate db) { this.db=db; }

    public boolean imported() {
        Integer count=db.queryForObject(
            "SELECT count(*) FROM payroll_meta WHERE key='legacy-import-complete'",Integer.class);
        return count!=null && count>0;
    }

    public boolean salaryDetailsImported() {
        Integer count=db.queryForObject(
            "SELECT count(*) FROM payroll_meta WHERE key='legacy-salary-detail-import-complete'",
            Integer.class);
        return count!=null && count>0;
    }

    /**
     * An already bootstrapped Payroll DB must import the two historical
     * detail columns before serving the employee-management salary route.
     * Never overwrite a later independently calculated ledger.
     */
    @Transactional
    public void backfillSalaryDetails(List<HistoricPayroll> past) {
        if(!imported() || salaryDetailsImported()) return;
        for(HistoricPayroll old:past) {
            if(old==null || !"EMPLOYEE".equals(old.source())) continue;
            db.update("UPDATE payroll_ledgers SET detail_rate=?,detail_amount=? "+
                    "WHERE id=? AND employee_id=? AND source='EMPLOYEE' "+
                    "AND detail_rate IS NULL AND detail_amount IS NULL "+
                    "AND status=?",
                old.detailRate(),old.detailAmount(),old.id(),old.employeeId(),
                old.status());
        }
        db.update("INSERT INTO payroll_meta(key,value) "+
            "VALUES('legacy-salary-detail-import-complete','yes') "+
            "ON CONFLICT(key) DO NOTHING");
    }

    @Transactional
    public void importHistorical(List<HistoricPayroll> past) {
        if(imported()) return;
        for(HistoricPayroll old:past) {
            if(old==null || old.id()==null || old.employeeId()==null) continue;
            int added=db.update(
                "INSERT INTO payroll_ledgers(id,employee_id,employee_name,employee_code,role_id," +
                "period,source,total_sessions,total_hours,rate,total_amount,status,calculated_at," +
                "detail_rate,detail_amount) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(source,employee_id,period) DO NOTHING",
                old.id(),old.employeeId(),old.employeeName(),old.employeeCode(),
                old.roleId(),old.period(),old.source(),old.totalSessions(),
                zero(old.totalHours()),zero(old.rate()),zero(old.totalAmount()),
                old.status(),Timestamp.from(old.calculatedAt()==null?Instant.now():old.calculatedAt()),
                old.detailRate(),old.detailAmount());
            if(added==1 && old.items()!=null) {
                for(HistoricItem item:old.items()) {
                    db.update("INSERT INTO payroll_items(id,payroll_id,schedule_id,work_date," +
                        "source_type,work_name,hours,rate,amount) VALUES(?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT(payroll_id,schedule_id) DO NOTHING",
                        item.id(),old.id(),item.scheduleId(),item.workDate(),item.sourceType(),
                        item.workName(),zero(item.hours()),zero(item.rate()),zero(item.amount()));
                }
            }
        }
        db.update("INSERT INTO payroll_meta(key,value) VALUES('legacy-import-complete','yes') " +
            "ON CONFLICT(key) DO NOTHING");
        db.update("INSERT INTO payroll_meta(key,value) VALUES(" +
            "'legacy-salary-detail-import-complete','yes') " +
            "ON CONFLICT(key) DO NOTHING");
    }

    private BigDecimal zero(BigDecimal n) { return n==null?BigDecimal.ZERO:n; }

    @Transactional
    public void calculate(Facts facts,YearMonth ym) {
        Person person=facts.person();
        boolean teacher="TEACHER".equals(person.role());
        String source=teacher?"EMPLOYEE":"STAFF";
        String id=UUID.randomUUID().toString();
        // A unique row is created before FOR UPDATE, so concurrent replays
        // for the same employee/source/month cannot interleave calculations.
        db.update("INSERT INTO payroll_ledgers(id,employee_id,employee_name,employee_code,role_id," +
            "period,source) VALUES(?,?,?,?,?,?,?) ON CONFLICT(source,employee_id,period) DO NOTHING",
            id,person.employeeId(),person.employeeName(),person.employeeCode(),
            person.role(),ym.toString(),source);
        var existing=db.query(
            "SELECT id,status FROM payroll_ledgers " +
            "WHERE employee_id=? AND period=? AND source=? FOR UPDATE",
            rs->rs.next()?new String[]{rs.getString(1),rs.getString(2)}:null,
            person.employeeId(),ym.toString(),source);
        if(existing==null) throw new IllegalStateException("Missing payroll ledger row");
        if(List.of("PAID","APPROVED","FINALIZED").contains(existing[1]))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Finalized payroll cannot be recalculated");
        id=existing[0];

        var unique=new LinkedHashMap<String,Work>();
        if(facts.works()!=null) for(Work work:facts.works()) {
            if(work==null || work.scheduleId()==null || work.workDate()==null
                    || work.startTime()==null || work.endTime()==null
                    || work.hourlyRate()==null || work.hourlyRate().signum()<0
                    || !YearMonth.from(work.workDate()).equals(ym)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid attendance source facts");
            }
            unique.putIfAbsent(work.scheduleId(),work);
        }

        record Item(Work work,BigDecimal hours,BigDecimal amount) {}
        List<Item> items=new ArrayList<>();
        BigDecimal total=BigDecimal.ZERO;
        BigDecimal totalHours=BigDecimal.ZERO;
        for(Work work:unique.values()) {
            long seconds=Duration.between(work.startTime(),work.endTime()).getSeconds();
            if(seconds<=0)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid work interval");
            BigDecimal hours=BigDecimal.valueOf(seconds).divide(
                BigDecimal.valueOf(3600),2,RoundingMode.HALF_UP);
            BigDecimal amount=work.hourlyRate().multiply(hours).setScale(2,RoundingMode.HALF_UP);
            total=total.add(amount);
            totalHours=totalHours.add(hours);
            items.add(new Item(work,hours,amount));
        }

        db.update("DELETE FROM payroll_items WHERE payroll_id=?",id);
        for(Item item:items) {
            db.update("INSERT INTO payroll_items(id,payroll_id,schedule_id,work_date," +
                "source_type,work_name,hours,rate,amount) VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),id,item.work().scheduleId(),item.work().workDate(),
                item.work().sourceType(),item.work().workName()==null ?
                    item.work().sourceType():item.work().workName(),
                item.hours(),item.work().hourlyRate(),item.amount());
        }
        BigDecimal rate=totalHours.signum()==0?BigDecimal.ZERO:
            total.divide(totalHours,2,RoundingMode.HALF_UP);
        db.update("UPDATE payroll_ledgers SET employee_name=?,employee_code=?,role_id=?," +
            "total_sessions=?,total_hours=?,rate=?,total_amount=?,status='CALCULATED'," +
            "detail_rate=CASE WHEN source='EMPLOYEE' THEN ? ELSE NULL END," +
            "detail_amount=CASE WHEN source='EMPLOYEE' THEN ? ELSE NULL END," +
            "calculated_at=NOW() WHERE id=?",
            person.employeeName(),person.employeeCode(),person.role(),
            items.size(),totalHours,rate,total,rate,total,id);
    }

    private PayrollRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PayrollRow(rs.getString(1),rs.getString(2),rs.getString(3),
            rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7),
            rs.getBigDecimal(8),rs.getBigDecimal(9),rs.getTimestamp(10).toInstant(),
            rs.getString(11));
    }
    private static final String SELECT =
        "SELECT id,period,employee_id,employee_name,employee_code,role_id,total_sessions," +
        "total_hours,total_amount,calculated_at,source FROM payroll_ledgers";

    public List<PayrollRow> byPeriod(String period) {
        return db.query(SELECT+" WHERE period=? ORDER BY employee_name,source,id",
            (rs,i)->map(rs),period);
    }
    public List<PayrollRow> byEmployeePeriod(String employeeId,String period) {
        return db.query(SELECT+" WHERE employee_id=? AND period=? ORDER BY source,id",
            (rs,i)->map(rs),employeeId,period);
    }
    /**
     * Read the independent Payroll ledger, preserving the old HR employee
     * salary list JSON names. The caller's current employee-management ACL
     * must be checked separately through the private Core authorization API.
     */
    public List<java.util.Map<String,Object>> employeeSalaryHistory(String employeeId) {
        return db.queryForList("SELECT source,id,period,total_sessions AS units," +
            "total_hours AS \"totalHours\",rate,total_amount AS amount,status," +
            "detail_rate AS \"detailRate\",detail_amount AS \"detailAmount\" " +
            "FROM payroll_ledgers WHERE employee_id=? ORDER BY period DESC,source,id",
            employeeId);
    }

    public record MonthCost(String period,BigDecimal salaryCost,long payrollRecords) {}
    public List<MonthCost> monthlyCosts(String from,String to) {
        return db.query("SELECT period,COALESCE(SUM(total_amount),0),COUNT(*) " +
            "FROM payroll_ledgers WHERE period BETWEEN ? AND ? GROUP BY period ORDER BY period",
            (rs,i)->new MonthCost(rs.getString(1),rs.getBigDecimal(2),rs.getLong(3)),
            from,to);
    }

    public List<PayrollItem> items(String payrollId) {
        return db.query("SELECT id,work_date,source_type,work_name,hours,rate,amount " +
            "FROM payroll_items WHERE payroll_id=? ORDER BY work_date,id",
            (rs,i)->new PayrollItem(rs.getString(1),rs.getObject(2,java.time.LocalDate.class),
                rs.getString(3),rs.getString(4),rs.getBigDecimal(5),rs.getBigDecimal(6),
                rs.getBigDecimal(7)),payrollId);
    }
}
