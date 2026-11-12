package vn.iig.attendance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AttendanceCommittedReceiptClientTest {
    private MockRestServiceServer remote;
    private AttendanceCommittedReceiptClient client;
    private final AttendanceCommandInbox.Pending item=new AttendanceCommandInbox.Pending(
        "cs001","class-1","teach-1","request-1","a".repeat(64));
    private static final String URL="http://backend:8081/internal/attendance/commands/receipt";

    @BeforeEach void setup() {
        var builder=RestClient.builder().baseUrl("http://backend:8081");
        remote=MockRestServiceServer.bindTo(builder).build();
        client=new AttendanceCommittedReceiptClient(builder.build(),"secret");
    }

    private String exact() {
        return "{\"actorUsername\":\"cs001\",\"classId\":\"class-1\","+
            "\"teachingScheduleId\":\"teach-1\",\"requestKey\":\"request-1\","+
            "\"requestHash\":\""+item.requestHash()+"\",\"response\":{"+
            "\"teachingScheduleId\":\"teach-1\",\"status\":\"PRESENT\"}}";
    }

    @Test void verifiedCommittedReceiptCanReconcileAnAmbiguousLocalInbox() {
        remote.expect(requestTo(URL)).andExpect(header("X-Internal-Key","secret"))
            .andRespond(withSuccess(exact(),MediaType.APPLICATION_JSON));
        var response=client.confirmed(item);
        assertEquals("teach-1",response.path("teachingScheduleId").asText());
        assertEquals("PRESENT",response.path("status").asText());
        remote.verify();
    }

    @Test void missingCoreReceiptKeepsLocalRequestPending() {
        remote.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertNull(client.confirmed(item));
        remote.verify();
    }

    @Test void forgedHashOrWrongScheduleCanNeverReleasePendingState() {
        remote.expect(requestTo(URL)).andRespond(withSuccess(
            exact().replace(item.requestHash(),"b".repeat(64)),
            MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->client.confirmed(item)).getStatusCode());
        remote.verify();
    }

    @Test void CoreNetworkFailureMustNotBeTreatedAsAbsentCommittedReceipt() {
        remote.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->client.confirmed(item)).getStatusCode());
        remote.verify();
    }
}
