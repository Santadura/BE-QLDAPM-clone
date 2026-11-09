package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import com.example.demo.service.EmployeeManagementService;

class HRCertificatesInternalControllerTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final EmployeeManagementService employees=mock(EmployeeManagementService.class);
    private final HRCertificatesInternalController api=
        new HRCertificatesInternalController(db,employees,"test-service-key");

    @Test void cannotExportOrProjectWithoutServiceKey() {
        assertThrows(ResponseStatusException.class, ()->api.export(null));
        assertThrows(ResponseStatusException.class, ()->api.project("wrong-key",null));
        verifyNoInteractions(db,employees);
    }

    @Test void validatesScopesThroughExistingEmployeeManagementService() {
        Authentication actor=mock(Authentication.class);
        api.requireEmployeeAccess("test-service-key","emp-001",actor);
        verify(employees).detail("emp-001",actor);
    }

    @Test void rejectsInvalidCertificateVersionBeforeDatabaseAccess() {
        var invalid=new HRCertificatesInternalController.CertificateData(
            "cert-1","emp-1","IELTS",8.0,null,null,null,null,null,"ACTIVE",
            java.time.Instant.now(),0);
        assertThrows(ResponseStatusException.class,
            ()->api.project("test-service-key",invalid));
        verifyNoInteractions(db);
    }
}
