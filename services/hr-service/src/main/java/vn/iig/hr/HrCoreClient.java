package vn.iig.hr;

import java.util.List;
import java.util.Map;
import vn.iig.hr.SalaryRankModels.RankState;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import vn.iig.hr.CertificateModels.CertificateData;

/** HR calls only authenticated internal HTTP contracts; never Core SQL. */
@Component
public class HrCoreClient {
    private final RestClient client;
    private final String internalKey;
    private final HrIdentityClient identity;

    public HrCoreClient(@Value("${iig.core.url}") String url,
                        @Value("${iig.core.internal-key}") String key,
                        HrIdentityClient identity) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("HR_INTERNAL_API_KEY required");
        internalKey=key;
        this.identity=identity;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public List<CertificateData> exportLegacy() {
        try {
            CertificateData[] rows=client.get().uri("/internal/hr/certificates/export")
                .header("X-Internal-Key",internalKey).retrieve().body(CertificateData[].class);
            return rows==null?List.of():List.of(rows);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"HR import unavailable");
        }
    }

    public void checkEmployeeAccess(String bearerToken, String employeeId) {
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");
        }
        try {
            client.get().uri(builder -> builder.path("/internal/hr/employees/{id}/access")
                    .build(employeeId))
                .header("Authorization",bearerToken)
                .header("X-Internal-Key",internalKey)
                .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            int code=ex.getStatusCode().value();
            if(code==401) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid session");
            if(code==403) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee outside role scope");
            if(code==404) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"HR access check unavailable");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"HR access check unavailable");
        }
    }

    public void project(CertificateData certificate) {
        try {
            client.post().uri("/internal/hr/certificates/project")
                .header("X-Internal-Key",internalKey)
                .body(certificate).retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core certificate projection unavailable");
        }
    }

    public List<RankState> exportLegacySalaryRanks() {
        try {
            RankState[] values=client.get().uri("/internal/hr/salary-ranks/export")
                .header("X-Internal-Key",internalKey).retrieve().body(RankState[].class);
            return values==null?List.of():List.of(values);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Salary-rank bootstrap unavailable");
        }
    }

    public void projectSalaryRank(RankState rank) {
        try {
            client.post().uri("/internal/hr/salary-ranks/project")
                .header("X-Internal-Key",internalKey).body(rank)
                .retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Salary-rank core projection unavailable");
        }
    }

    /** Central live role check via private Identity service. */
    public String currentRole(String bearer) {
        return identity.currentRole(bearer);
    }

    /**
     * An authenticated employee can only resolve their own current profile.
     * Core still owns employee identity until the HR lifecycle cutover.
     */
    @SuppressWarnings("unchecked")
    public String currentEmployeeId(String bearer) {
        if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()<16)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Current profile bearer required");
        try {
            Map<String,Object> profile=client.get().uri("/api/profile")
                .header("Authorization",bearer).retrieve().body(Map.class);
            if(profile==null || !(profile.get("username") instanceof String username)
                    || username.isBlank())
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Current employee account not verified");
            Object employee=profile.get("employeeId");
            return employee instanceof String id && !id.isBlank()?id:null;
        } catch(HttpClientErrorException ex) {
            if(ex.getStatusCode().value()==401)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Current profile session invalid");
            if(ex.getStatusCode().value()==403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current profile forbidden");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Current employee source unavailable");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Current employee source unavailable");
        }
    }
}
