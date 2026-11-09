package vn.iig.audit;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuditEventController.class)
@TestPropertySource(properties = "iig.internal.api-key=test-internal-secret")
class AuditEventControllerTest {
    @Autowired MockMvc mvc;
    @MockBean AuditEventRepository repository;

    private static final String EVENT =
        "{\"eventId\":\"audit-test-1\",\"username\":\"admin\",\"action\":\"CREATE_CLASS\"," +
        "\"entityType\":\"CLASS\",\"entityId\":\"class-1\",\"occurredAt\":\"2026-11-09T04:00:00Z\"}";

    @Test void rejectsMissingKey() throws Exception {
        mvc.perform(post("/internal/audit/events").contentType("application/json").content(EVENT))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    @Test void acceptsAuthorizedEvent() throws Exception {
        mvc.perform(post("/internal/audit/events").header("X-Internal-Key","test-internal-secret")
            .contentType("application/json").content(EVENT))
            .andExpect(status().isAccepted());
        verify(repository).insertIfAbsent(org.mockito.ArgumentMatchers.any(AuditEvent.class));
    }
}
