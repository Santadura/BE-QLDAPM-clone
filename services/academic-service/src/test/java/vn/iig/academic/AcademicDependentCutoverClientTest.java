package vn.iig.academic;

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

class AcademicDependentCutoverClientTest {
    private MockRestServiceServer attendance;
    private MockRestServiceServer scheduling;
    private AcademicDependentCutoverClient client;
    private static final String A="{\"ready\":true,\"state\":\"READY\","+
        "\"sourceRows\":2,\"verifiedRows\":2,\"mismatchCount\":0}";
    private static final String S="{\"ready\":true,\"state\":\"READY\","+
        "\"sourceRows\":3,\"localRows\":3,\"writeShadowRows\":3,"+
        "\"checkedRows\":3,\"mismatches\":0,\"pendingChanges\":0,"+
        "\"unresolvedCommands\":0,\"liveSchedules\":3,\"trackedLiveSchedules\":3}";

    @BeforeEach void setup() {
        RestClient.Builder a=RestClient.builder().baseUrl("http://attendance:8100");
        RestClient.Builder s=RestClient.builder().baseUrl("http://scheduling:8098");
        attendance=MockRestServiceServer.bindTo(a).build();
        scheduling=MockRestServiceServer.bindTo(s).build();
        client=new AcademicDependentCutoverClient(a.build(),"attendance-secret",
            s.build(),"scheduling-secret");
    }

    void replyAttendance(String body) {
        attendance.expect(requestTo("http://attendance:8100/internal/attendance/verify/frozen"))
            .andExpect(header("X-Internal-Key","attendance-secret"))
            .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }
    void replyScheduling(String body) {
        scheduling.expect(requestTo("http://scheduling:8098/internal/scheduling/assignments/verify"))
            .andExpect(header("X-Internal-Key","scheduling-secret"))
            .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }

    @Test void independentPrivateReportsMustBothBeFullyReady() {
        replyAttendance(A);replyScheduling(S);
        var proof=client.verify();
        assertEquals(2,proof.attendance().path("verifiedRows").asInt());
        assertEquals(3,proof.scheduling().path("checkedRows").asInt());
        attendance.verify();scheduling.verify();
    }

    @Test void forgedReadyFlagCannotHideSchedulingUnresolvedCommand() {
        replyAttendance(A);
        replyScheduling(S.replace("\"unresolvedCommands\":0",
            "\"unresolvedCommands\":1"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,client::verify).getStatusCode());
        attendance.verify();scheduling.verify();
    }

    @Test void attendanceManifestCountMismatchCannotProduceGlobalReady() {
        replyAttendance(A.replace("\"verifiedRows\":2", "\"verifiedRows\":1"));
        replyScheduling(S);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,client::verify).getStatusCode());
    }

    @Test void remoteAttendance409FailsClosedBeforeSchedulingQuery() {
        attendance.expect(requestTo("http://attendance:8100/internal/attendance/verify/frozen"))
            .andRespond(withStatus(HttpStatus.CONFLICT));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,client::verify).getStatusCode());
        attendance.verify();scheduling.verify();
    }

    @Test void noOrMissingReadinessFieldNeverAuthorizesWriter() {
        replyAttendance("{\"state\":\"READY\",\"sourceRows\":0}");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,client::verify).getStatusCode());
        attendance.verify();scheduling.verify();
    }
}
