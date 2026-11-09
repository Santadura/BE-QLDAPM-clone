package vn.iig.hr;

import java.time.Instant;
import java.time.LocalDate;
import jakarta.validation.constraints.*;

public final class CertificateModels {
    private CertificateModels() {}

    public record CertificateData(
        String certificateId, String employeeId, String certificateType,
        Double score, Double scoreSW, LocalDate issueDate, LocalDate expiryDate,
        String issuer, String certificateUrl, String status,
        Instant createdAt, long version
    ) {
        public CertificateResponse publicView() {
            return new CertificateResponse(certificateId,certificateType,score,scoreSW,
                issueDate,expiryDate,issuer,certificateUrl,status);
        }
    }

    public record CertificateRequest(
        @NotBlank @Size(max=30) String certificateType,
        @PositiveOrZero Double score,
        @PositiveOrZero Double scoreSW,
        LocalDate issueDate, LocalDate expiryDate,
        @Size(max=100) String issuer,
        @Size(max=500) String certificateUrl,
        @NotBlank @Pattern(regexp="ACTIVE|INACTIVE") String status
    ) {}

    /** Exact fields returned by legacy EmployeeManagementService.CERTIFICATES. */
    public record CertificateResponse(
        String certificateId, String certificateType, Double score, Double scoreSW,
        LocalDate issueDate, LocalDate expiryDate, String issuer,
        String certificateUrl, String status
    ) {}
}
