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

    @Test void batchAcknowledgementUsesExactVersions() {
        var messages=java.util.List.of(
            new SchedulingAssignmentReadBridge.Acknowledgement("STAFF","support-1",9),
            new SchedulingAssignmentReadBridge.Acknowledgement("TEACHING","lesson-1",3));
        bridge.acknowledgeBatch("private-assignment-key",messages);
        verify(db).batchUpdate(contains("version=? AND delivered_at IS NULL"),
            argThat((java.util.List<Object[]> batch)->batch.size()==2
                && batch.get(0)[0].equals("STAFF")
                && batch.get(0)[2].equals(9L)
                && batch.get(1)[2].equals(3L)));
    }

    @Test void batchRejectsInvalidEntryWithoutAnyWrites() {
        assertThrows(ResponseStatusException.class,()->bridge.acknowledgeBatch(
            "private-assignment-key",java.util.List.of(
                new SchedulingAssignmentReadBridge.Acknowledgement("TEACHING","valid",1),
                new SchedulingAssignmentReadBridge.Acknowledgement("BAD","invalid",1))));
        verifyNoInteractions(db);
    }

    @Test void rejectsRequestWithoutLiveJwt() {
        assertThrows(ResponseStatusException.class,()->bridge.viewer("private-assignment-key",null));
        verifyNoInteractions(db);
    }
}
