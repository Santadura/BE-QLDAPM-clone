package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class HRSalaryRanksInternalControllerTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final HRSalaryRanksInternalController subject=
        new HRSalaryRanksInternalController(db,"internal-secret");

    @Test void credentialSnapshotNotPublic() {
        assertThrows(ResponseStatusException.class,()->subject.exportLegacy(null));
        verifyNoInteractions(db);
    }

    @Test void rejectsUnauthenticatedProjection() {
        var item=new HRSalaryRanksInternalController.RankState("rank-1","TEACHER",
            "Grade 1",new BigDecimal("200000"),"ACTIVE",Instant.now(),1L);
        assertThrows(ResponseStatusException.class,()->subject.project("wrong",item));
        verifyNoInteractions(db);
    }

    @Test void rejectsUnapprovedRoleAndNegativeAmount() {
        var item=new HRSalaryRanksInternalController.RankState("rank-1","ADMIN",
            "Grade 1",new BigDecimal("-1"),"ACTIVE",Instant.now(),1L);
        assertThrows(ResponseStatusException.class,
            ()->subject.project("internal-secret",item));
        verifyNoInteractions(db);
    }
}
