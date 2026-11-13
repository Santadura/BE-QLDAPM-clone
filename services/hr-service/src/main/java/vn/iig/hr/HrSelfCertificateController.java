package vn.iig.hr;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.hr.CertificateModels.CertificateData;
import vn.iig.hr.CertificateModels.CertificateResponse;

/** Original self-profile certificate route now queries HR-owned PostgreSQL. */
@RestController
@RequestMapping("/api/profile")
public class HrSelfCertificateController {
    private final HrCoreClient core;
    private final CertificateRepository certificates;
    public HrSelfCertificateController(HrCoreClient core,CertificateRepository certificates) {
        this.core=core;
        this.certificates=certificates;
    }
    @GetMapping("/certificates")
    public List<CertificateResponse> list(
            @RequestHeader(value="Authorization",required=false) String bearer) {
        String employeeId=core.currentEmployeeId(bearer);
        if(!certificates.imported())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "HR certificate import pending");
        if(employeeId==null) return List.of();
        return certificates.byEmployee(employeeId).stream()
            .map(CertificateData::publicView).toList();
    }
}
