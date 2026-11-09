package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable command admission before ANY independent Scheduling DB mutation.
 *
 * The claim and writer-mode check serialize on the Core singleton writer row.
 * A claim remains even when Scheduling times out or its local transaction
 * fails. This is intentional: an uncertain handback must require explicit
 * cross-database reconciliation, not auto-expire after a few minutes.
 *
 * Private, service-key-protected and unavailable from the public Gateway.
 * It is not an independent assignment API.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/claims")
@ConditionalOnProperty(name="iig.scheduling.bridge-enabled",havingValue="true")
public class SchedulingAssignmentWriterClaimBridge {
    public record ClaimRequest(String actorUsername,String managerEmployeeId,
                               String operation,String requestKey) {}
    public record ClaimReceipt(String actorUsername,String operation,
                               String requestKey,String status) {}
    private record Stored(String managerEmployeeId,String status) {}

    private static final Set<String> SUPPORTED=
        Set.of("ASSIGN_STAFF","EDIT_STAFF","CANCEL_STAFF");

    private final JdbcTemplate db;
    private final byte[] secret;

    public SchedulingAssignmentWriterClaimBridge(
            JdbcTemplate db,@Value("${iig.scheduling.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.db=db;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    @Transactional
    public ClaimReceipt acquire(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestBody ClaimRequest request) {
        byte[] supplied=key==null?new byte[0]:key.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Scheduling writer claim service key");

        if(request==null || request.actorUsername()==null ||
                request.actorUsername().isBlank() ||
                request.actorUsername().length()>100 ||
                request.managerEmployeeId()==null ||
                request.managerEmployeeId().isBlank() ||
                request.managerEmployeeId().length()>50 ||
                request.operation()==null ||
                !SUPPORTED.contains(request.operation()) ||
                request.requestKey()==null ||
                !request.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid independent Scheduling writer claim");

        // Lock the very same Core control row as the handback UPDATE.
        // Once this transaction commits a claim, handback is permanently
        // fenced until a separately audited recovery is completed.
        String mode=db.queryForObject(
            "SELECT writer_mode FROM scheduling_assignment_writer_control " +
            "WHERE singleton=TRUE FOR UPDATE",String.class);
        if(!"QUIESCED".equals(mode))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core assignment writes must be QUIESCED");

        Long activeManager=db.queryForObject(
            "SELECT count(*) FROM employees e JOIN users u ON u.username=e.username " +
            "WHERE e.employee_id=? AND e.username=? AND e.status='ACTIVE' " +
            "AND u.status='ACTIVE' AND u.role_id='CM'",
            Long.class,request.managerEmployeeId(),request.actorUsername());
        if(activeManager==null || activeManager!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "An active CM is required for this command claim");

        db.update(
            "INSERT INTO scheduling_assignment_writer_claims " +
            "(actor_username,operation,request_key,manager_employee_id) " +
            "VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
            request.actorUsername(),request.operation(),request.requestKey(),
            request.managerEmployeeId());
        List<Stored> rows=db.query(
            "SELECT manager_employee_id,status FROM scheduling_assignment_writer_claims " +
            "WHERE actor_username=? AND operation=? AND request_key=? FOR UPDATE",
            (rs,i)->new Stored(rs.getString(1),rs.getString(2)),
            request.actorUsername(),request.operation(),request.requestKey());
        if(rows.size()!=1 ||
                !request.managerEmployeeId().equals(rows.get(0).managerEmployeeId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A command key already belongs to a different manager");
        return new ClaimReceipt(request.actorUsername(),request.operation(),
            request.requestKey(),rows.get(0).status());
    }
}
