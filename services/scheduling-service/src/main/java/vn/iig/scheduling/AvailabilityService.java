package vn.iig.scheduling;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.iig.scheduling.SchedulingModels.*;

/** Availability is registered, reviewed and versioned only in Scheduling DB. */
@Service
public class AvailabilityService {
    private static final Logger log=LoggerFactory.getLogger(AvailabilityService.class);
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> WORKERS=Set.of("TEACHER","CS","SALE");
    private final AvailabilityRepository store;
    private final SchedulingCoreClient core;

    public AvailabilityService(AvailabilityRepository store,SchedulingCoreClient core) {
        this.store=store;
        this.core=core;
    }

    private Actor require(String bearer,String... roles) {
        Actor actor=core.currentActor(bearer);
        if(!Set.of(roles).contains(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Role is not allowed for this action");
        return actor;
    }

    private void ready() {
        if(!store.imported())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Availability migration is still importing");
    }

    private LocalDate monday(LocalDate date) {
        LocalDate d=date==null?LocalDate.now(BUSINESS_ZONE):date;
        return d.with(DayOfWeek.MONDAY);
    }

    private String normalizedDay(String day) {
        if(day==null) return null;
        String normalized=day.trim().toLowerCase(Locale.ROOT);
        if(!Set.of("mon","tue","wed","thu","fri","sat","sun").contains(normalized))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid dayOfWeek");
        return normalized;
    }

    private String day(LocalDate date) {
        return date.getDayOfWeek().name().substring(0,3).toLowerCase(Locale.ROOT);
    }

    private String kind(AvailabilityRequest req) {
        return req.availabilityType()==null?"RECURRING":
            req.availabilityType().trim().toUpperCase(Locale.ROOT);
    }

    private void validate(AvailabilityRequest body) {
        if(body==null || body.startTime()==null || body.endTime()==null
                || !body.endTime().isAfter(body.startTime()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "End time must be later than start time");
        String type=kind(body);
        if(!Set.of("RECURRING","WEEKLY").contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "availabilityType must be RECURRING or WEEKLY");
        if("WEEKLY".equals(type) && body.availableDate()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "availableDate required for WEEKLY availability");
        if("RECURRING".equals(type) && body.availableDate()!=null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "RECURRING availability cannot include availableDate");
        if(body.availableDate()==null && body.dayOfWeek()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"dayOfWeek required");
        if(body.dayOfWeek()!=null && body.availableDate()!=null &&
           !normalizedDay(body.dayOfWeek()).equals(day(body.availableDate())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "dayOfWeek does not match availableDate");
        if(body.note()!=null && body.note().length()>255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "note maximum length is 255");
        if(body.campus()!=null && body.campus().length()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"campus maximum length is 100");
        if(body.shift()!=null && body.shift().length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"shift maximum length is 50");
    }

    private String requestDay(AvailabilityRequest req) {
        return req.availableDate()==null?normalizedDay(req.dayOfWeek()):day(req.availableDate());
    }

    public List<Map<String,Object>> mine(String bearer,LocalDate week) {
        Actor actor=require(bearer,"TEACHER","CS","SALE");
        ready();
        LocalDate from=monday(week);
        return store.list(actor.employeeId(),from,from.plusDays(6),null,null)
            .stream().map(Slot::publicData).toList();
    }

    public List<Map<String,Object>> list(String bearer,String role,
                                         String status,LocalDate week) {
        Actor actor=require(bearer,"TC","CM","ADMIN");
        ready();
        String selected=role==null?("TC".equals(actor.role())?"TEACHER":null):
            role.trim().toUpperCase(Locale.ROOT);
        if(selected!=null && !WORKERS.contains(selected))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid role");
        if(("TC".equals(actor.role()) && selected!=null && !"TEACHER".equals(selected)) ||
            ("CM".equals(actor.role()) && "TEACHER".equals(selected)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot inspect this role");
        LocalDate from=monday(week);
        String reviewStatus=status==null?null:status.trim().toUpperCase(Locale.ROOT);
        return store.list(null,from,from.plusDays(6),selected,reviewStatus)
            .stream()
            .filter(slot->"ADMIN".equals(actor.role()) ||
                ("TC".equals(actor.role()) && "TEACHER".equals(slot.role())) ||
                ("CM".equals(actor.role()) && Set.of("CS","SALE").contains(slot.role())))
            .map(Slot::publicData).toList();
    }

    public Map<String,Object> create(String bearer,AvailabilityRequest body) {
        Actor actor=require(bearer,"TEACHER","CS","SALE");
        ready();
        validate(body);
        return store.create(actor,body,requestDay(body),kind(body)).publicData();
    }

    public Map<String,Object> update(String bearer,String id,AvailabilityRequest body) {
        Actor actor=require(bearer,"TEACHER","CS","SALE");
        ready();
        validate(body);
        return store.update(id,actor,body,requestDay(body),kind(body),core).publicData();
    }

    public void delete(String bearer,String id) {
        Actor actor=require(bearer,"TEACHER","CS","SALE");
        ready();
        store.delete(id,actor,core);
    }

    public Map<String,Object> review(String bearer,String id,ReviewRequest request) {
        Actor actor=require(bearer,"TC","CM");
        ready();
        if(request==null || request.status()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review status required");
        String status=request.status().trim().toUpperCase(Locale.ROOT);
        if(!Set.of("APPROVED","REJECTED").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "status must be APPROVED or REJECTED");
        return store.review(id,actor,status).publicData();
    }

    @Scheduled(initialDelay=1000,fixedDelay=3000)
    public void syncWithCore() {
        if(!store.imported()) {
            try {
                List<Slot> old=core.exportLegacy();
                store.importInitial(old);
                log.info("Imported {} old availability records to Scheduling-owned database",old.size());
            } catch(Exception ex) {
                log.warn("Scheduling initial availability import pending: {}",ex.getMessage());
                return;
            }
        }
        for(AvailabilityRepository.Pending item:store.pending(100)) {
            try {
                core.project(item.slot());
                store.acknowledge(item.slot().id(),item.version());
            } catch(Exception ex) {
                log.warn("Scheduling reference projection for {} pending: {}",
                    item.slot().id(),ex.getMessage());
                break;
            }
        }
    }

    @Scheduled(initialDelay=30000,fixedDelay=60000)
    public void cleanReservations() {
        store.cleanupExpired();
    }
}
