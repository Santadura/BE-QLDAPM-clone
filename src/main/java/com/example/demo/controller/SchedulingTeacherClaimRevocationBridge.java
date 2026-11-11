package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core-side final stage of a two-DB orphan TC claim withdrawal.
 * Scheduling must FIRST irreversibly reject its STAGED command under
 * its inbox row lock, and produce a durable immutable audit. Core then
 * independently reads that proof over private HTTP while holding the
 * same singleton writer lock as claim acquisition and handback.
 *
 * Never deletes a claim. This transition PENDING -> REVOKED is audited
 * in the Core DB and cannot be used to revoke a confirmed event.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/claims/teacher")
@ConditionalOnProperty(name="iig.scheduling.teacher-claim-recovery-enabled",
                       havingValue="true")
public class SchedulingTeacherClaimRevocationBridge {
    public record Request(String actorUsername,String requestKey,String reason) {}
    public record Receipt(String actorUsername,String requestKey,String status) {}
    private record Proof(String actorUsername,String requestKey,
                         String state,long sourceEventCount,long immutableAuditCount) {}
    private record Claim(String managerEmployeeId,String status,
                         String revokedBy,String reason) {}
    private record Audit(String adminUsername,String reason) {}
    private final JdbcTemplate db;
    private final RestClient scheduling;
    private final byte[] key;

    public SchedulingTeacherClaimRevocationBridge(JdbcTemplate db,
            @Value("${iig.scheduling.internal-key}") String secret,
            @Value("${iig.scheduling.url}") String schedulingUrl) {
        if(secret==null || secret.isBlank() ||
                schedulingUrl==null || schedulingUrl.isBlank())
            throw new IllegalArgumentException(
                "Private Scheduling recovery service key and URL required");
        this.db=db;this.key=secret.getBytes(StandardCharsets.UTF_8);
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(8000);
        this.scheduling=RestClient.builder().baseUrl(schedulingUrl)
            .requestFactory(factory).build();
    }

    @PostMapping("/revoke")
    @Transactional
    public Receipt revoke(
        @RequestHeader(value="X-Internal-Key",required=false) String supplied,
        @RequestBody Request request,Authentication authentication) {
        byte[] actual=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,actual))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Core orphan-claim revocation key");
        if(authentication==null || !authentication.isAuthenticated() ||
                "anonymousUser".equals(authentication.getPrincipal()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Live administrator JWT required");
        String admin=authentication.getName();
        if(request==null || admin==null ||
                !admin.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
                request.actorUsername()==null ||
                !request.actorUsername().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
                request.requestKey()==null ||
                !request.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
                request.reason()==null || request.reason().trim().length()<20 ||
                request.reason().length()>300)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact teacher claim and 20-300 character audit reason required");
        String reason=request.reason().trim();
        Long permitted=db.queryForObject(
            "SELECT count(*) FROM users WHERE username=? AND role_id='ADMIN' " +
            "AND status='ACTIVE'",Long.class,admin);
        if(permitted==null || permitted!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only an active ADMIN may revoke an orphan teacher claim");

        // Serializes against every Core writer claim and QUIESCED->CORE
        // transition. No concurrent Core reference can race past its
        // transaction-local writer row FOR SHARE lock.
        String writer=db.queryForObject(
            "SELECT writer_mode FROM scheduling_assignment_writer_control " +
            "WHERE singleton=TRUE FOR UPDATE",String.class);
        if(!"QUIESCED".equals(writer))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core writer must be QUIESCED for claim withdrawal");
        List<Claim> existing=db.query(
            "SELECT manager_employee_id,status,revoked_by,revocation_reason " +
            "FROM scheduling_assignment_writer_claims " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
            "AND request_key=? FOR UPDATE",
            (rs,i)->new Claim(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4)),
            request.actorUsername(),request.requestKey());
        if(existing.size()!=1 || "CONFIRMED".equals(existing.get(0).status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No pending, unconfirmed TC claim may be revoked");
        Claim claim=existing.get(0);

        // No operator-provided status or count is trusted here. Core
        // retrieves the immutable terminal Scheduling evidence itself.
        Proof proof;
        try {
            proof=scheduling.get()
                .uri(uri->uri.path(
                    "/internal/scheduling/assignments/reconciliation/teacher/proof")
                    .queryParam("actorUsername",request.actorUsername())
                    .queryParam("requestKey",request.requestKey()).build())
                .header("X-Internal-Key",new String(key,StandardCharsets.UTF_8))
                .retrieve().body(Proof.class);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Cannot verify irrevocable Scheduling rejection",ex);
        }
        if(proof==null || !"REJECTED".equals(proof.state()) ||
                !request.actorUsername().equals(proof.actorUsername()) ||
                !request.requestKey().equals(proof.requestKey()) ||
                proof.sourceEventCount()!=0 || proof.immutableAuditCount()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Scheduling command is not irreversibly rejected with zero source events");

        Long refs=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_reference_event_receipts " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            Long.class,request.actorUsername(),request.requestKey());
        if(refs==null || refs!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core has committed references for this TC claim");

        if("REVOKED".equals(claim.status())) {
            List<Audit> audits=db.query(
                "SELECT admin_username,reason FROM scheduling_teacher_claim_revocation_audit " +
                "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
                (rs,i)->new Audit(rs.getString(1),rs.getString(2)),
                request.actorUsername(),request.requestKey());
            if(audits.size()!=1 || !admin.equals(audits.get(0).adminUsername()) ||
                    !reason.equals(audits.get(0).reason()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Already revoked by a different immutable administrator/audit");
            return new Receipt(request.actorUsername(),request.requestKey(),"REVOKED");
        }
        if(!"PENDING".equals(claim.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot revoke an already processed writer claim");
        // The PostgreSQL BEFORE UPDATE guard independently demands a
        // matching immutable audit. Insert it FIRST in this very same
        // Core transaction, then move PENDING -> REVOKED. If either step
        // fails both changes roll back as a unit.
        if(db.update("INSERT INTO scheduling_teacher_claim_revocation_audit " +
                "(actor_username,operation,request_key,admin_username,reason) " +
                "VALUES(?,'ASSIGN_TEACHER',?,?,?)",
                request.actorUsername(),request.requestKey(),admin,reason)!=1)
            throw new IllegalStateException("Core claim revocation audit not committed");
        if(db.update("UPDATE scheduling_assignment_writer_claims SET " +
                "status='REVOKED',revoked_at=NOW(),revoked_by=?,revocation_reason=? " +
                "WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
                "AND request_key=? AND status='PENDING'",
                admin,reason,request.actorUsername(),request.requestKey())!=1)
            throw new IllegalStateException("Core claim revoke lost writer ownership");
        return new Receipt(request.actorUsername(),request.requestKey(),"REVOKED");
    }
}
