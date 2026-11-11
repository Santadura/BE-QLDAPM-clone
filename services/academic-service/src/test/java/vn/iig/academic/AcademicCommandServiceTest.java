package vn.iig.academic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademicCommandServiceTest {
    AcademicCoreClient identity=mock(AcademicCoreClient.class);
    AcademicWriteClient core=mock(AcademicWriteClient.class);
    AcademicCommandInbox inbox=mock(AcademicCommandInbox.class);
    AcademicCommandService service=new AcademicCommandService(identity,core,inbox);
    JsonNode body=new ObjectMapper().createArrayNode();
    @Test void rejectsWrongRoleBeforeInsertingCommand() {
        when(identity.currentPrincipal("Bearer tc")).thenReturn(
            new AcademicCoreClient.Principal("tc001","ROLE_TC"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.execute("PLAN","class-1",body,"Bearer tc","key")).getStatusCode());
        verifyNoInteractions(core,inbox);
    }
    @Test void noKeyRetainsOneShotCoreBehavior() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(core.command("PLAN","class-1",body,"Bearer admin",null)).thenReturn(body);
        assertEquals(body,service.execute("PLAN","class-1",body,"Bearer admin",null));
        verifyNoInteractions(inbox);
    }
    @Test void replayAlwaysRevalidatesAgainstCore() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(core.command("PLAN","class-1",body,"Bearer admin","key")).thenReturn(body);
        service.execute("PLAN","class-1",body,"Bearer admin","key");
        service.execute("PLAN","class-1",body,"Bearer admin","key");
        verify(core,times(2)).command("PLAN","class-1",body,"Bearer admin","key");
        verify(inbox,times(2)).stage(eq("admin"),eq("class-1"),
            eq("PLAN"),eq("key"),matches("[a-f0-9]{64}"));
    }
    @Test void changedPayloadWithSameKeyCannotReachCore() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(inbox)
            .stage(anyString(),anyString(),anyString(),anyString(),anyString());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.execute("PLAN","class-1",body,"Bearer admin","key")).getStatusCode());
        verifyNoInteractions(core);
    }
}
