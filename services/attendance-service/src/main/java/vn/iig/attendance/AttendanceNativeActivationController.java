package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * PRIVATE, explicit one-way bootstrap of an independent Attendance writer.
 *
 * A verified snapshot is installed in Attendance PostgreSQL, without
 * enabling the public writer or changing the Core writer mode. The
 * operator must separately perform the audited SQL handoff. Every
 * incoming business write is then bound to this generation-specific
 * immutable activation.
 */
@RestController
@RequestMapping("/internal/attendance/native/activate")
@ConditionalOnProperty(name="iig.attendance.native-admission-enabled",
    havingValue="true")
public class AttendanceNativeActivationController {
    public record Result(long writerGeneration,long importedRows,
        long sourceRows,long historicalEvents,long lastEventId,
        String academicProofHash,String state) {}
    private final AttendanceCoreClient core;
    private final AttendanceSourceClient source;
    private final AttendanceCutoverVerifier verifier;
    private final AttendanceNativeLedgerRepository ledger;
    private final ObjectMapper mapper;
    private final RestClient academic;
    private final byte[] attendanceKey;
    private final String academicKey;

    public AttendanceNativeActivationController(AttendanceCoreClient core,
            AttendanceSourceClient source,AttendanceCutoverVerifier verifier,
            AttendanceNativeLedgerRepository ledger,ObjectMapper mapper,
            @Value("${iig.core.internal-key}") String key,
            @Value("${iig.attendance.academic-url}") String academicUrl,
            @Value("${iig.attendance.academic-key}") String academicKey) {
        if(key==null || key.isBlank() ||
            academicKey==null || academicKey.isBlank() ||
            academicUrl==null || !academicUrl.matches("https?://[^\\s]+"))
            throw new IllegalArgumentException(
                "Private Attendance and Academic cutover credentials required");
        this.core=core;this.source=source;this.verifier=verifier;
        this.ledger=ledger;this.mapper=mapper;
        attendanceKey=key.getBytes(StandardCharsets.UTF_8);
        this.academicKey=academicKey;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);factory.setReadTimeout(15000);
        academic=RestClient.builder().baseUrl(academicUrl)
            .requestFactory(factory).build();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }

    /**
     * Fail closed on any historical disparity, unacknowledged command, lost
     * Academic/Scheduling dependency or changed Core writer generation.
     * This never returns an owner token for a public caller.
     */
    @PostMapping
    @Transactional
    public Result activate(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String bearer) {
        if(!MessageDigest.isEqual(attendanceKey,
            (key==null?"":key).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Attendance activation key required");
        var actor=core.currentPrincipal(bearer);
        if(!"ROLE_ADMIN".equals(actor.role()) || actor.username()==null ||
            !actor.username().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only a current Admin may bootstrap independent Attendance ownership");

        var before=source.writerFence();
        if(!"QUIESCED".equals(before.attendanceMode()) ||
            !"QUIESCED".equals(before.schedulingMode()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Freeze Core Scheduling and Attendance writers first");
        var local=verifier.verifyFrozen();
        if(!local.ready() || !"READY".equals(local.state()) ||
            local.sourceRows()!=local.verifiedRows() || local.mismatchCount()!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance bootstrap requires complete independent source parity");

        final JsonNode academicProof;
        try {
            academicProof=academic.get()
                .uri("/internal/academic/cutover/full-readiness")
                .header("X-Internal-Key",academicKey)
                .header("Authorization",bearer)
                .retrieve().body(JsonNode.class);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Independent Academic/Scheduling/Attendance proof unavailable",ex);
        }
        if(academicProof==null || !academicProof.isObject() ||
            !academicProof.path("rosterGeneration").canConvertToLong() ||
            !academicProof.path("sessionGeneration").canConvertToLong() ||
            !academicProof.path("attendanceGeneration").canConvertToLong() ||
            !academicProof.path("schedulingGeneration").canConvertToLong() ||
            academicProof.path("attendanceGeneration").asLong()!=
                before.attendanceGeneration() ||
            academicProof.path("schedulingGeneration").asLong()!=
                before.schedulingGeneration() ||
            !"READY".equals(academicProof.path("downstream")
                .path("attendance").path("state").asText()) ||
            !"READY".equals(academicProof.path("downstream")
                .path("scheduling").path("state").asText()) ||
            !academicProof.path("downstream").path("attendance")
                .path("ready").asBoolean(false) ||
            !academicProof.path("downstream").path("scheduling")
                .path("ready").asBoolean(false))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic cutover proof does not match frozen Attendance writer generations");

        var manifest=source.manifestSummary();
        if(manifest.sourceRows()!=local.sourceRows() ||
            manifest.eventCount()<manifest.sourceRows() ||
            manifest.pendingEvents()!=0 || manifest.divergentRows()!=0 ||
            manifest.liveRows()!=manifest.trackedLiveRows())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Attendance immutable manifest changed since parity verification");

        long generation=before.attendanceGeneration()+1;
        if(generation<=before.attendanceGeneration())
            throw new IllegalStateException("Attendance writer generation overflow");
        String academicHash=hash(academicProof.toString());
        long imported=ledger.activate(new AttendanceNativeLedgerRepository.Activation(
            actor.username(),generation,manifest.sourceRows(),
            manifest.eventCount(),manifest.maxEventId(),academicHash));
        // Roll back this Attendance DB transaction if ANY dependency
        // generation changed while importing the frozen native baseline.
        if(!before.equals(source.writerFence()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core writer changed during native Attendance baseline activation");
        return new Result(generation,imported,manifest.sourceRows(),
            manifest.eventCount(),manifest.maxEventId(),academicHash,
            "BOOTSTRAPPED_AWAITING_CORE_HANDOFF");
    }
}
