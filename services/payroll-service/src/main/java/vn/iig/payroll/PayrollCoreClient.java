package vn.iig.payroll;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.payroll.PayrollContracts.*;

/** Internal, service-key authenticated source facts. No direct Core JDBC. */
@Component
public class PayrollCoreClient {
    private final RestClient client;
    private final String internalKey;
    private final PayrollIdentityClient identity;

    public PayrollCoreClient(@Value("${iig.core.url}") String coreUrl,
                             @Value("${iig.core.internal-key}") String key,
                             PayrollIdentityClient identity) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("PAYROLL_INTERNAL_API_KEY required");
        this.internalKey=key;
        this.identity=identity;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(8000);
        this.client=RestClient.builder().baseUrl(coreUrl).requestFactory(factory).build();
    }

    public Actor actor(String bearer) {
        if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()<16)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");
        PayrollIdentityClient.Principal live=identity.currentPrincipal(bearer);
        try {
            Actor result=client.get().uri("/internal/payroll/actor")
                .header("Authorization",bearer)
                .header("X-Internal-Key",internalKey)
                .retrieve().body(Actor.class);
            if(result==null || result.username()==null || result.role()==null ||
                !result.username().equals(live.username()) ||
                !("ROLE_"+result.role()).equals(live.role()))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid active session");
            return result;
        } catch(HttpClientErrorException ex) {
            if(ex.getStatusCode().value()==401)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid session");
            if(ex.getStatusCode().value()==403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account inactive");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Identity source unavailable");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Identity source unavailable");
        }
    }

    /**
     * Employee-management permission checks remain live and role-scoped.
     * The salary DATA is read solely from Payroll PostgreSQL; Core returns
     * no salary rows or database snapshot through this endpoint.
     */
    public void requireEmployeeManagementAccess(String bearer,String employeeId) {
        if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()<16)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Valid employee management bearer token required");
        if(employeeId==null || employeeId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Employee ID required");
        try {
            client.get().uri("/internal/payroll/employee-management-access/{id}",employeeId)
                .header("Authorization",bearer)
                .header("X-Internal-Key",internalKey)
                .retrieve().toBodilessEntity();
        } catch(HttpClientErrorException ex) {
            if(ex.getStatusCode().value()==401)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Employee management session invalid");
            if(ex.getStatusCode().value()==403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Outside employee management role scope");
            if(ex.getStatusCode().value()==404)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Employee not found");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Employee management authorization unavailable",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Employee management authorization unavailable",ex);
        }
    }

    public List<Person> people() {
        try {
            Person[] rows=client.get().uri("/internal/payroll/people")
                .header("X-Internal-Key",internalKey).retrieve().body(Person[].class);
            return rows==null ? List.of() : Arrays.asList(rows);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Employee data unavailable");
        }
    }

    public List<Facts> facts(String period) {
        try {
            Facts[] rows=client.get().uri(uri->uri.path("/internal/payroll/facts")
                .queryParam("period",period).build())
                .header("X-Internal-Key",internalKey).retrieve().body(Facts[].class);
            return rows==null?List.of():Arrays.asList(rows);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Attendance data unavailable");
        }
    }

    public List<HistoricPayroll> historical() {
        try {
            HistoricPayroll[] records=client.get().uri("/internal/payroll/export")
                .header("X-Internal-Key",internalKey).retrieve().body(HistoricPayroll[].class);
            return records==null?List.of():Arrays.asList(records);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Historical payroll import unavailable");
        }
    }
}
