package vn.iig.hr;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import vn.iig.hr.CertificateModels.CertificateRequest;
import vn.iig.hr.CertificateModels.CertificateResponse;

/**
 * Preserves existing employee certificate API paths and response fields.
 * Reads/writes are backed by independent HR PostgreSQL.
 */
@RestController
@RequestMapping("/api/employee-management/{employeeId}/certificates")
public class CertificateController {
    private final CertificateService certificates;

    public CertificateController(CertificateService certificates) {
        this.certificates=certificates;
    }

    @GetMapping
    public List<CertificateResponse> list(
            @PathVariable String employeeId,
            @RequestHeader(value="Authorization",required=false) String authorization) {
        return certificates.list(employeeId,authorization);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CertificateResponse create(
            @PathVariable String employeeId,@Valid @RequestBody CertificateRequest request,
            @RequestHeader(value="Authorization",required=false) String authorization) {
        return certificates.create(employeeId,request,authorization);
    }

    @PutMapping("/{certificateId}")
    public CertificateResponse update(
            @PathVariable String employeeId,@PathVariable String certificateId,
            @Valid @RequestBody CertificateRequest request,
            @RequestHeader(value="Authorization",required=false) String authorization) {
        return certificates.update(employeeId,certificateId,request,authorization);
    }
}
