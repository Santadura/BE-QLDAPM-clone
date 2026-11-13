package vn.iig.hr;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.iig.hr.SalaryRankModels.*;

@Service
public class SalaryRankService {
    private static final Logger log=LoggerFactory.getLogger(SalaryRankService.class);
    private static final Set<String> WORKER_ROLES=Set.of("TEACHER","CS","SALE");
    private final SalaryRankRepository repository;
    private final HrCoreClient core;
    public SalaryRankService(SalaryRankRepository repository,HrCoreClient core) {
        this.repository=repository;
        this.core=core;
    }

    private void ready() {
        if(!repository.imported()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Salary rank bootstrap is still in progress");
        }
    }

    public List<RankView> list(String authorization,String requestedRole) {
        String actor=core.currentRole(authorization);
        ready();
        Set<String> allowed=switch(actor) {
            case "ADMIN" -> WORKER_ROLES;
            case "TC" -> Set.of("TEACHER");
            case "CM" -> Set.of("CS","SALE");
            default -> Set.of();
        };
        String role=requestedRole==null || requestedRole.isBlank()?null:
            requestedRole.trim().toUpperCase(Locale.ROOT);
        if(allowed.isEmpty() || (role!=null && !allowed.contains(role))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Salary rank outside permitted management scope");
        }
        return repository.all().stream()
            .filter(row->allowed.contains(row.roleId()) && (role==null || role.equals(row.roleId())))
            .map(RankState::publicView).toList();
    }

    /** Existing employee-management rank options backed only by HR writes. */
    public List<java.util.Map<String,Object>> managerOptions(String bearer) {
        return list(bearer,null).stream()
            .filter(row->"ACTIVE".equals(row.status()))
            .map(row->{
                java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();
                result.put("salaryRankId",row.id());
                result.put("role",row.roleId());
                result.put("rank",row.rank());
                result.put("amount",row.amount());
                result.put("status",row.status());
                return result;
            }).toList();
    }

    public RankView create(RankRequest request,String authorization) {
        requireAdmin(authorization);
        ready();
        String role=request.roleId().trim().toUpperCase(Locale.ROOT);
        if(!WORKER_ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid salary rank role");
        }
        String status=validatedStatus(request.status(),"ACTIVE");
        return repository.create(new RankState(UUID.randomUUID().toString(),role,
            request.rank().trim(),request.amount(),status,Instant.now(),1)).publicView();
    }

    public RankView update(String id,RankUpdate request,String authorization) {
        requireAdmin(authorization);
        ready();
        RankState current=repository.get(id);
        String status=validatedStatus(request.status(),current.status());
        return repository.update(id,request.rank().trim(),request.amount(),status).publicView();
    }

    private void requireAdmin(String authorization) {
        if(!"ADMIN".equals(core.currentRole(authorization))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only Admin may manage salary ranks");
        }
    }

    private String validatedStatus(String supplied,String defaultStatus) {
        String value=supplied==null?defaultStatus:supplied.trim().toUpperCase(Locale.ROOT);
        if(!Set.of("ACTIVE","INACTIVE").contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid salary rank status");
        }
        return value;
    }

    @Scheduled(initialDelay=1000,fixedDelay=4000)
    public void synchronize() {
        if(!repository.imported()) {
            try {
                List<RankState> legacy=core.exportLegacySalaryRanks();
                repository.importInitial(legacy);
                log.info("Imported {} historical salary ranks into HR ownership",legacy.size());
            } catch(Exception e) {
                log.warn("Salary rank bootstrap pending: {}",e.getMessage());
                return;
            }
        }
        for(SalaryRankRepository.Pending pending:repository.pending(100)) {
            try {
                core.projectSalaryRank(pending.rank());
                repository.acknowledge(pending.rank().id(),pending.version());
            } catch(Exception e) {
                log.warn("Salary rank {} still pending projection: {}",pending.rank().id(),e.getMessage());
                return;
            }
        }
    }
}
