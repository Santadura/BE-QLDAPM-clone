package vn.iig.hr;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.iig.hr.CertificateModels.*;

@Service
public class CertificateService {
    private static final Logger log=LoggerFactory.getLogger(CertificateService.class);
    private final CertificateRepository repository;
    private final HrCoreClient core;

    public CertificateService(CertificateRepository repository,HrCoreClient core) {
        this.repository=repository;
        this.core=core;
    }

    private void ready() {
        if(!repository.imported()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "HR certificate import is still in progress");
        }
    }

    public List<CertificateResponse> list(String employeeId,String authorization) {
        core.checkEmployeeAccess(authorization,employeeId);
        ready();
        return repository.byEmployee(employeeId).stream().map(CertificateData::publicView).toList();
    }

    public CertificateResponse create(String employeeId,CertificateRequest request,String authorization) {
        core.checkEmployeeAccess(authorization,employeeId);
        ready();
        validate(request);
        CertificateData data=new CertificateData(UUID.randomUUID().toString(),employeeId,
            request.certificateType().trim(),request.score(),request.scoreSW(),
            request.issueDate(),request.expiryDate(),trim(request.issuer()),
            trim(request.certificateUrl()),request.status(),Instant.now(),1);
        return repository.create(data).publicView();
    }

    public CertificateResponse update(String employeeId,String certificateId,
                                      CertificateRequest request,String authorization) {
        core.checkEmployeeAccess(authorization,employeeId);
        ready();
        validate(request);
        CertificateData updated=new CertificateData(certificateId,employeeId,
            request.certificateType().trim(),request.score(),request.scoreSW(),
            request.issueDate(),request.expiryDate(),trim(request.issuer()),
            trim(request.certificateUrl()),request.status(),Instant.now(),1);
        return repository.update(updated).publicView();
    }

    private String trim(String value) {
        return value==null||value.isBlank()?null:value.trim();
    }

    private void validate(CertificateRequest request) {
        if (request.certificateType()==null || request.certificateType().isBlank()
                || request.certificateType().length()>30
                || request.status()==null || !List.of("ACTIVE","INACTIVE").contains(request.status())
                || (request.score()!=null && (!Double.isFinite(request.score()) || request.score()<0))
                || (request.scoreSW()!=null && (!Double.isFinite(request.scoreSW()) || request.scoreSW()<0))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid certificate data");
        }
        if(request.issueDate()!=null && request.expiryDate()!=null
                && request.expiryDate().isBefore(request.issueDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Expiry date must not precede issue date");
        }
        String url=trim(request.certificateUrl());
        if(url!=null) {
            try {
                URI uri=URI.create(url);
                if(uri.getHost()==null || !List.of("http","https").contains(uri.getScheme().toLowerCase())) {
                    throw new IllegalArgumentException("Invalid scheme");
                }
            } catch (RuntimeException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Certificate URL must be HTTP or HTTPS");
            }
        }
    }

    @Scheduled(initialDelay=1000,fixedDelay=4000)
    public void synchronize() {
        if(!repository.imported()) {
            try {
                List<CertificateData> rows=core.exportLegacy();
                repository.importInitial(rows);
                log.info("Bootstrapped {} legacy certificates into HR ownership",rows.size());
            } catch (Exception ex) {
                log.warn("HR certificate bootstrap pending: {}",ex.getMessage());
                return;
            }
        }
        for(CertificateRepository.Pending row:repository.pending(100)) {
            try {
                core.project(row.data());
                repository.acknowledge(row.data().certificateId(),row.version());
            } catch (Exception ex) {
                log.warn("HR certificate {} pending: {}",row.data().certificateId(),ex.getMessage());
                return;
            }
        }
    }
}
