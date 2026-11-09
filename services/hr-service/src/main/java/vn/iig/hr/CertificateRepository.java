package vn.iig.hr;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import vn.iig.hr.CertificateModels.CertificateData;

@Repository
public class CertificateRepository {
    private final JdbcTemplate db;
    private static final String FIELDS =
        "certificate_id,employee_id,certificate_type,score,score_sw,issue_date," +
        "expiry_date,issuer,certificate_url,status,created_at,version";

    public record Pending(CertificateData data, long version) {}

    public CertificateRepository(JdbcTemplate db) {
        this.db=db;
    }

    private CertificateData map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CertificateData(rs.getString(1),rs.getString(2),rs.getString(3),
            (Double)rs.getObject(4,Double.class),(Double)rs.getObject(5,Double.class),
            rs.getObject(6,LocalDate.class),rs.getObject(7,LocalDate.class),
            rs.getString(8),rs.getString(9),rs.getString(10),
            rs.getTimestamp(11).toInstant(),rs.getLong(12));
    }

    public boolean imported() {
        Integer count=db.queryForObject(
            "SELECT count(*) FROM hr_metadata WHERE key='certificates-imported'",Integer.class);
        return count!=null && count>0;
    }

    @Transactional
    public void importInitial(List<CertificateData> existing) {
        if(imported()) return;
        for(CertificateData row:existing) {
            int inserted=db.update(
                "INSERT INTO hr_certificates("+FIELDS+") VALUES(?,?,?,?,?,?,?,?,?,?,?,1) " +
                "ON CONFLICT(certificate_id) DO NOTHING",
                row.certificateId(),row.employeeId(),row.certificateType(),
                row.score(),row.scoreSW(),row.issueDate(),row.expiryDate(),row.issuer(),
                row.certificateUrl(),row.status(),
                Timestamp.from(row.createdAt()==null?Instant.now():row.createdAt()));
            if(inserted==1) markPending(row.certificateId(),1L);
        }
        db.update("INSERT INTO hr_metadata(key,value) VALUES('certificates-imported','yes') " +
            "ON CONFLICT DO NOTHING");
    }

    public List<CertificateData> byEmployee(String employeeId) {
        return db.query("SELECT "+FIELDS+" FROM hr_certificates WHERE employee_id=? " +
            "ORDER BY created_at DESC,certificate_id",
            (rs,i)->map(rs),employeeId);
    }

    public CertificateData byId(String employeeId,String certificateId) {
        List<CertificateData> rows=db.query(
            "SELECT "+FIELDS+" FROM hr_certificates WHERE employee_id=? AND certificate_id=?",
            (rs,i)->map(rs),employeeId,certificateId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Certificate not found");
        return rows.get(0);
    }

    @Transactional
    public CertificateData create(CertificateData row) {
        db.update("INSERT INTO hr_certificates("+FIELDS+") VALUES(?,?,?,?,?,?,?,?,?,?,?,1)",
            row.certificateId(),row.employeeId(),row.certificateType(),
            row.score(),row.scoreSW(),row.issueDate(),row.expiryDate(),
            row.issuer(),row.certificateUrl(),row.status(),
            Timestamp.from(row.createdAt()));
        markPending(row.certificateId(),1L);
        return byId(row.employeeId(),row.certificateId());
    }

    @Transactional
    public CertificateData update(CertificateData value) {
        Long current=db.query(
            "SELECT version FROM hr_certificates WHERE certificate_id=? AND employee_id=? FOR UPDATE",
            rs->rs.next()?rs.getLong(1):null,value.certificateId(),value.employeeId());
        if(current==null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Certificate not found");
        }
        long next=current+1;
        db.update("UPDATE hr_certificates SET certificate_type=?,score=?,score_sw=?,issue_date=?," +
            "expiry_date=?,issuer=?,certificate_url=?,status=?,version=?,updated_at=NOW() " +
            "WHERE employee_id=? AND certificate_id=?",
            value.certificateType(),value.score(),value.scoreSW(),value.issueDate(),
            value.expiryDate(),value.issuer(),value.certificateUrl(),value.status(),
            next,value.employeeId(),value.certificateId());
        markPending(value.certificateId(),next);
        return byId(value.employeeId(),value.certificateId());
    }

    private void markPending(String id,long version) {
        db.update("INSERT INTO hr_certificate_outbox(certificate_id,version,delivered_at) " +
            "VALUES(?,?,NULL) ON CONFLICT(certificate_id) DO UPDATE " +
            "SET version=EXCLUDED.version,delivered_at=NULL",id,version);
    }

    public List<Pending> pending(int limit) {
        return db.query("SELECT c.certificate_id,c.employee_id,c.certificate_type," +
            "c.score,c.score_sw,c.issue_date,c.expiry_date,c.issuer,c.certificate_url," +
            "c.status,c.created_at,c.version FROM hr_certificate_outbox o " +
            "JOIN hr_certificates c ON c.certificate_id=o.certificate_id " +
            "WHERE o.delivered_at IS NULL ORDER BY c.certificate_id LIMIT ?",
            (rs,i)->new Pending(map(rs),rs.getLong(12)),limit);
    }

    public void acknowledge(String id,long version) {
        db.update("UPDATE hr_certificate_outbox SET delivered_at=NOW() " +
            "WHERE certificate_id=? AND version=? AND delivered_at IS NULL",id,version);
    }
}
