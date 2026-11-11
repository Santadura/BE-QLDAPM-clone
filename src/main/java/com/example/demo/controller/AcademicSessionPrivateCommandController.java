package com.example.demo.controller;

import com.example.demo.service.ClassSessionService;
import com.example.demo.service.SchedulingCommandReceiptService;
import com.example.demo.service.ClassSessionService.RescheduleRequest;
import com.example.demo.service.ClassSessionService.PlannedSessionInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Transitional private transport; Core remains the ONLY session writer.
 * Domain changes, legacy audit and command receipt commit in one Core tx.
 */
@RestController
@RequestMapping("/internal/academic/commands/classes/{classId}")
@ConditionalOnProperty(name="iig.academic.bridge-enabled",havingValue="true")
public class AcademicSessionPrivateCommandController {
    private final ClassSessionService sessions;
    private final SchedulingCommandReceiptService receipts;
    private final byte[] secret;
    public AcademicSessionPrivateCommandController(ClassSessionService sessions,
        SchedulingCommandReceiptService receipts,
        @Value("${iig.academic.internal-key}") String key) {
        if(key==null || key.isBlank())throw new IllegalArgumentException("Academic key required");
        this.sessions=sessions;this.receipts=receipts;
        secret=key.getBytes(StandardCharsets.UTF_8);
    }
    private String authorize(String key,String bearer,String classId,Authentication auth) {
        if(!MessageDigest.isEqual(secret,(key==null?"":key).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid private Academic key");
        if(bearer==null || !bearer.startsWith("Bearer ") || bearer.substring(7).isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Live bearer token required");
        if(classId==null || !classId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid class ID");
        return sessions.requireSessionManager(classId,auth);
    }
    @PutMapping("/planned-sessions")
    public List<Map<String,Object>> replace(
        @RequestHeader(value="X-Internal-Key",required=false) String key,
        @RequestHeader(value="Authorization",required=false) String bearer,
        @RequestHeader(value="Idempotency-Key",required=false) String requestKey,
        @PathVariable String classId,@RequestBody List<PlannedSessionInput> body,
        Authentication auth) {
        String actor=authorize(key,bearer,classId,auth);
        return receipts.execute(requestKey,actor,"ACADEMIC:PLAN:"+classId,
            body,()->sessions.replace(classId,body,auth));
    }
    @PostMapping("/reschedule")
    public List<Map<String,Object>> reschedule(
        @RequestHeader(value="X-Internal-Key",required=false) String key,
        @RequestHeader(value="Authorization",required=false) String bearer,
        @RequestHeader(value="Idempotency-Key",required=false) String requestKey,
        @PathVariable String classId,@RequestBody RescheduleRequest body,
        Authentication auth) {
        String actor=authorize(key,bearer,classId,auth);
        return receipts.execute(requestKey,actor,"ACADEMIC:RESCHEDULE:"+classId,
            body,()->sessions.reschedule(classId,body,auth));
    }
}
