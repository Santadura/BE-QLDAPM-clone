package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class SchedulingAssignmentReadBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingAssignmentReadBridge bridge=
        new SchedulingAssignmentReadBridge(db,"private-assignment-key");

    @Test void protectsAllReadModelFeedsWithInternalKey() {
        assertThrows(ResponseStatusException.class,()->bridge.export(null));
        assertThrows(ResponseStatusException.class,()->bridge.changes("wrong",20));
        assertThrows(ResponseStatusException.class,()->bridge.acknowledge("wrong",null));
        verifyNoInteractions(db);
    }

    @Test void rejectsMalformedAcknowledgement() {
        assertThrows(ResponseStatusException.class,()->bridge.acknowledge(
            "private-assignment-key",new SchedulingAssignmentReadBridge.Acknowledgement(
                "INVALID","abc",1)));
        verifyNoInteractions(db);
    }

    @Test void acknowledgesOnlyTheVersionSuccessfullyDelivered() {
        bridge.acknowledge("private-assignment-key",
            new SchedulingAssignmentReadBridge.Acknowledgement("TEACHING","assignment-1",7));
        verify(db).update(contains("version=? AND delivered_at IS NULL"),
            eq("TEACHING"),eq("assignment-1"),eq(7L));
    }

    @Test void rejectsRequestWithoutLiveJwt() {
        assertThrows(ResponseStatusException.class,()->bridge.viewer("private-assignment-key",null));
        verifyNoInteractions(db);
    }
}
