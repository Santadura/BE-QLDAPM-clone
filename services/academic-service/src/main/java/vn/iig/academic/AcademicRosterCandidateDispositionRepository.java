package vn.iig.academic;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A REHEARSAL_ONLY proposal never changed a business row. It blocks real
 * cutover until deliberately DISCARDED with exact immutable evidence.
 * Both the original command/outbox and the disposition are append-only.
 */
@Repository
public class AcademicRosterCandidateDispositionRepository {
    public record Summary(long commands,long disposed,long unresolved,
        long pendingHandoffs) {}
    private record Disposition(String actor,String key,String hash,String by) {}
    private final JdbcTemplate db;
    private final AcademicRosterCandidateRepository candidates;

    public AcademicRosterCandidateDispositionRepository(JdbcTemplate db,
            AcademicRosterCandidateRepository candidates) {
        this.db=db;this.candidates=candidates;
    }

    @Transactional(readOnly=true)
    public Summary summary() {
        var row=db.queryForObject(
            "SELECT "+
            "COUNT(*),"+
            "COUNT(d.command_id),"+
            "COUNT(*) FILTER(WHERE d.command_id IS NULL),"+
            "COUNT(*) FILTER(WHERE h.command_id IS NULL) "+
            "FROM academic_roster_candidate_commands c "+
            "LEFT JOIN academic_roster_candidate_dispositions d "+
            "ON d.command_id=c.command_id "+
            "LEFT JOIN academic_roster_candidate_handoff_receipts h "+
            "ON h.command_id=c.command_id",
            (rs,i)->new Summary(rs.getLong(1),rs.getLong(2),
                rs.getLong(3),rs.getLong(4)));
        if(row==null || row.commands()<0 || row.disposed()<0 ||
            row.unresolved()<0 || row.pendingHandoffs()<0 ||
            row.disposed()+row.unresolved()!=row.commands())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic rehearsal disposition ledger is inconsistent");
        return row;
    }

    @Transactional
    public void discard(String actor,String key,String commandId,
            String disposedBy) {
        if(actor==null || key==null || disposedBy==null ||
            !actor.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
            !disposedBy.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            commandId==null || !commandId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact Academic rehearsal command identity required");
        // Proves original request hash, canonical JSON SHA-256, all outbox
        // identity fields and proposed numeric value before disposition.
        var evidence=candidates.committedEvidence(actor,key,commandId);
        Long acknowledgements=db.queryForObject(
            "SELECT COUNT(*) FROM academic_roster_candidate_handoff_receipts "+
            "WHERE command_id=CAST(? AS UUID) AND actor_username=? "+
            "AND request_key=? AND core_outbox_hash=?",
            Long.class,commandId,actor,key,evidence.outboxHash());
        if(!Long.valueOf(1).equals(acknowledgements))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core handback was not durably acknowledged by Academic");
        int inserted=db.update(
            "INSERT INTO academic_roster_candidate_dispositions "+
            "(command_id,actor_username,request_key,outbox_hash,disposed_by) "+
            "VALUES(CAST(? AS UUID),?,?,?,?) "+
            "ON CONFLICT(command_id) DO NOTHING",
            commandId,actor,key,evidence.outboxHash(),disposedBy);
        if(inserted!=0 && inserted!=1)
            throw new IllegalStateException("Ambiguous Academic rehearsal disposition");
        List<Disposition> rows=db.query(
            "SELECT actor_username,request_key,outbox_hash,disposed_by "+
            "FROM academic_roster_candidate_dispositions "+
            "WHERE command_id=CAST(? AS UUID)",
            (rs,i)->new Disposition(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4)),commandId);
        if(rows.size()!=1 || !actor.equals(rows.get(0).actor()) ||
            !key.equals(rows.get(0).key()) ||
            !evidence.outboxHash().equals(rows.get(0).hash()) ||
            !disposedBy.equals(rows.get(0).by()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic rehearsal disposition cannot be changed by replay");
    }
}
