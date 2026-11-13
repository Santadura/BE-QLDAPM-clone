package vn.iig.payroll;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.payroll.PayrollContracts.*;

/**
 * Payroll owns all externally visible calculation and payroll ledgers.
 * Core only supplies authenticated person/schedule facts over private HTTP.
 * HR rank reviews/assignments still use Core until HR employee ownership moves.
 */
@Service
public class PayrollService {
    private static final Logger log=LoggerFactory.getLogger(PayrollService.class);
    private static final Set<String> STAFF_ROLES=Set.of("TEACHER","CS","SALE");
    private final PayrollRepository ledger;
    private final PayrollCoreClient core;

    public PayrollService(PayrollRepository ledger,PayrollCoreClient core) {
        this.ledger=ledger;
        this.core=core;
    }

    private void requireReady() {
        if(!ledger.imported())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Historical payroll import has not completed");
    }

    private YearMonth ym(String period) {
        try {
            if(period==null || !period.matches("\\d{4}-\\d{2}"))
                throw new IllegalArgumentException();
            return YearMonth.parse(period);
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"period must use YYYY-MM");
        }
    }

    private boolean manages(String viewerRole,String targetRole) {
        return "ADMIN".equals(viewerRole)
            || ("TC".equals(viewerRole) && "TEACHER".equals(targetRole))
            || ("CM".equals(viewerRole) && Set.of("CS","SALE").contains(targetRole));
    }

    private Person requireRead(Actor actor,String employeeId,Map<String,Person> people) {
        Person target=people.get(employeeId);
        if(target==null || !STAFF_ROLES.contains(target.role()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
        if(employeeId.equals(actor.employeeId()) || manages(actor.role(),target.role()))
            return target;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Payroll outside your role scope");
    }

    private Person requireManage(Actor actor,String employeeId,Map<String,Person> people) {
        Person target=people.get(employeeId);
        if(target==null || !STAFF_ROLES.contains(target.role()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
        if(!manages(actor.role(),target.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Manager role required");
        return target;
    }

    private Map<String,Person> people() {
        return core.people().stream().collect(Collectors.toMap(
            Person::employeeId,Function.identity(),(left,right)->left));
    }

    public List<PayrollRow> list(String bearer,String period,String employeeId) {
        ym(period);
        Actor actor=core.actor(bearer);
        requireReady();
        Map<String,Person> people=people();
        if(employeeId!=null && !employeeId.isBlank())
            requireRead(actor,employeeId,people);
        return ledger.byPeriod(period).stream().filter(row->
            (employeeId==null || employeeId.isBlank() || employeeId.equals(row.employeeId()))
            && people.containsKey(row.employeeId())
            && (row.employeeId().equals(actor.employeeId()) ||
                manages(actor.role(),people.get(row.employeeId()).role())))
            .toList();
    }

    public List<Map<String,Object>> employeeSalaryHistory(String bearer,String employeeId) {
        // Fail closed while the independent historical detail backfill is
        // missing or Core's role/account authority is unavailable.
        core.requireEmployeeManagementAccess(bearer,employeeId);
        requireReady();
        if(!ledger.salaryDetailsImported())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Legacy salary-detail import is still incomplete");
        return ledger.employeeSalaryHistory(employeeId);
    }

    public PayrollDetail detail(String bearer,String employeeId,String period) {
        ym(period);
        Actor actor=core.actor(bearer);
        requireReady();
        Person person=requireRead(actor,employeeId,people());
        String expectedSource="TEACHER".equals(person.role())?"EMPLOYEE":"STAFF";
        PayrollRow row=ledger.byEmployeePeriod(employeeId,period).stream()
            .filter(item->expectedSource.equals(item.source())).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Payroll has not been calculated for this period"));
        return new PayrollDetail(row.id(),row.period(),row.employeeId(),row.employeeName(),
            row.employeeCode(),row.roleId(),row.totalSessions(),row.totalHours(),
            row.totalAmount(),row.calculatedAt(),row.source(),ledger.items(row.id()));
    }

    public List<PayrollRow> calculate(String bearer,String period,String employeeId) {
        YearMonth month=ym(period);
        Actor actor=core.actor(bearer);
        requireReady();
        Map<String,Person> people=people();
        if(employeeId==null || employeeId.isBlank()) {
            if(!Set.of("ADMIN","TC","CM").contains(actor.role()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Manager role required");
        } else {
            requireManage(actor,employeeId,people);
        }

        // Snapshot facts over HTTP before opening any local DB transaction.
        // Scheduling can be changing concurrently; an eventual-consistency
        // event contract will replace this transitional synchronous read.
        for(Facts facts:core.facts(month.toString())) {
            if(facts==null || facts.person()==null) continue;
            Person person=people.get(facts.person().employeeId());
            if(person==null || !"ACTIVE".equals(person.status())) continue;
            if(employeeId!=null && !employeeId.isBlank() &&
                    !employeeId.equals(person.employeeId())) continue;
            if(!manages(actor.role(),person.role())) continue;
            ledger.calculate(new Facts(person,facts.works()),month);
        }
        return list(bearer,month.toString(),employeeId);
    }

    @Scheduled(initialDelay=1000,fixedDelay=4000)
    public void bootstrap() {
        if(ledger.imported() && ledger.salaryDetailsImported()) return;
        try {
            var original=core.historical();
            if(!ledger.imported()) {
                ledger.importHistorical(original);
                log.info("Imported {} existing payroll records into exclusive Payroll database",
                    original.size());
            } else {
                ledger.backfillSalaryDetails(original);
                log.info("Completed backward-compatible employee salary detail migration");
            }
        } catch(Exception ex) {
            log.warn("Payroll historical/detail import pending: {}",ex.getMessage());
        }
    }
}
