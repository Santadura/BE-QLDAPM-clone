package vn.iig.academic;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/**
 * Append-only closure of a NON-AUTHORITATIVE timetable rehearsal.
 *
 * A Core RELEASED claim and exact local handback are prerequisites.
 * Discard never modifies class_sessions, Attendance, Scheduling, an
 * independent Academic source projection or the original candidate outbox.
 * The caller's @Transactional boundary protects the local disposition.
 */
@Repository
public class AcademicSessionCandidateDispositionRepository {
    public record Summary(long commands,long disposed,long unresolved,
        long pendingHandoffs,long divergentDispositions) {}
    public record Receipt(String requestKey,String commandId,String disposition) {}
    private record Disposition(String actor,String key,String hash,String by,
        String state) {}

    private final JdbcTemplate db;
    private final AcademicSessionCandidateRepository candidates;

    public AcademicSessionCandidateDispositionRepository(JdbcTemplate db,
            AcademicSessionCandidateRepository candidates) {
        this.db=db;this.candidates=candidates;
    }

    public Summary summary() {
        var result=db.queryForObject(
            "SELECT COUNT(*),COUNT(d.command_id),"+
            "COUNT(*) FILTER(WHERE d.command_id IS NULL),"+
            "COUNT(*) FILTER(WHERE h.command_id IS NULL),"+
            "COUNT(*) FILTER(WHERE d.command_id IS NOT NULL AND ("+
            "h.command_id IS NULL OR o.command_id IS NULL OR "+
            "d.actor_username IS DISTINCT FROM c.operator_username OR "+
            "d.request_key IS DISTINCT FROM c.request_key OR "+
            "d.outbox_hash IS DISTINCT FROM o.payload_hash OR "+
            "h.actor_username IS DISTINCT FROM c.operator_username OR "+
            "h.request_key IS DISTINCT FROM c.request_key OR "+
            "h.core_outbox_hash IS DISTINCT FROM o.payload_hash)) "+
            "FROM academic_session_candidate_commands c "+
            "LEFT JOIN academic_session_candidate_outbox o ON o.command_id=c.command_id "+
            "LEFT JOIN academic_candidate_handoff_receipts h ON h.command_id=c.command_id "+
            "LEFT JOIN academic_session_candidate_dispositions d ON d.command_id=c.command_id",
            (rs,i)->new Summary(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getLong(4),rs.getLong(5)));
        if(result==null || result.commands()<0 ||
            result.disposed()<0 || result.unresolved()<0 ||
            result.pendingHandoffs()<0 || result.divergentDispositions()!=0 ||
            result.disposed()+result.unresolved()!=result.commands())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session rehearsal disposition ledger is incomplete or divergent");
        return result;
    }

    public Receipt discard(String actor,String key,String commandId,
            String disposedBy) {
        if(actor==null || key==null || disposedBy==null ||
            !actor.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
            !disposedBy.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            commandId==null ||
            !commandId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact Academic session rehearsal identity required");
        var proof=candidates.committedEvidence(actor,key,commandId);
        if(!actor.equals(proof.actor()) || !key.equals(proof.key()) ||
            !commandId.equalsIgnoreCase(proof.commandId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session candidate identity does not match immutable evidence");
        Long acknowledgements=db.queryForObject(
            "SELECT COUNT(*) FROM academic_candidate_handoff_receipts "+
            "WHERE command_id=CAST(? AS UUID) AND actor_username=? "+
            "AND request_key=? AND core_outbox_hash=?",
            Long.class,commandId,actor,key,proof.outboxHash());
        if(!Long.valueOf(1).equals(acknowledgements))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session Core handback is not locally acknowledged");

        int inserted=db.update(
            "INSERT INTO academic_session_candidate_dispositions "+
            "(command_id,actor_username,request_key,outbox_hash,disposed_by) "+
            "VALUES(CAST(? AS UUID),?,?,?,?) "+
            "ON CONFLICT(command_id) DO NOTHING",
            commandId,actor,key,proof.outboxHash(),disposedBy);
        if(inserted!=0 && inserted!=1)
            throw new IllegalStateException("Ambiguous Academic session rehearsal disposition");
        List<Disposition> matches=db.query(
            "SELECT actor_username,request_key,outbox_hash,disposed_by,disposition "+
            "FROM academic_session_candidate_dispositions "+
            "WHERE command_id=CAST(? AS UUID)",
            (rs,i)->new Disposition(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getString(5)),
            commandId);
        if(matches.size()!=1 || !actor.equals(matches.get(0).actor()) ||
            !key.equals(matches.get(0).key()) ||
            !proof.outboxHash().equals(matches.get(0).hash()) ||
            !disposedBy.equals(matches.get(0).by()) ||
            !"REHEARSAL_DISCARDED".equals(matches.get(0).state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session disposition changed on replay");
        return new Receipt(key,commandId,"REHEARSAL_DISCARDED");
    }
}
