package vn.iig.reporting;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Reports aggregate source-of-truth values through Payroll's private HTTP API. */
@Component
public class ReportingPayrollClient {
    public record MonthCost(String period,BigDecimal salaryCost,long payrollRecords) {}

    private final RestClient client;
    private final String internalKey;

    public ReportingPayrollClient(@Value("${iig.payroll.url}") String url,
                                  @Value("${iig.payroll.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("PAYROLL_INTERNAL_API_KEY required in Reporting");
        internalKey=key;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public List<MonthCost> monthly(String from,String to) {
        try {
            MonthCost[] values=client.get().uri(uri->uri
                .path("/internal/payroll/financial")
                .queryParam("from",from).queryParam("to",to).build())
                .header("X-Internal-Key",internalKey)
                .retrieve().body(MonthCost[].class);
            return values==null?List.of():Arrays.asList(values);
        } catch(RestClientException ex) {
            // Fail closed rather than showing a stale Core salary total.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payroll financial source unavailable");
        }
    }
}
