package vn.iig.hr;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.hr.SalaryRankModels.RankState;

/** Salary-rank writes and outbox commit atomically in the HR database. */
@Repository
public class SalaryRankRepository {
    public record Pending(RankState rank,long version) {}
    private final JdbcTemplate db;

    public SalaryRankRepository(JdbcTemplate db) { this.db=db; }

    private RankState read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RankState(rs.getString(1),rs.getString(2),rs.getString(3),
            rs.getBigDecimal(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getLong(7));
    }

    private static final String SELECT =
        "SELECT salary_rank_id,role_id,rank,amount,status,created_at,version FROM hr_salary_ranks";

    public boolean imported() {
        Integer count=db.queryForObject(
            "SELECT count(*) FROM hr_metadata WHERE key='salary-ranks-imported'",Integer.class);
        return count!=null && count>0;
    }

    @Transactional
    public void importInitial(List<RankState> rows) {
        if(imported()) return;
        for(RankState r:rows) {
            int added=db.update("INSERT INTO hr_salary_ranks(" +
                    "salary_rank_id,role_id,rank,amount,status,created_at,version) " +
                    "VALUES(?,?,?,?,?,?,1) ON CONFLICT(salary_rank_id) DO NOTHING",
                r.id(),r.roleId(),r.rank(),r.amount(),r.status(),
                Timestamp.from(r.createdAt()==null?Instant.now():r.createdAt()));
            if(added==1) markPending(r.id(),1L);
        }
        db.update("INSERT INTO hr_metadata(key,value) VALUES('salary-ranks-imported','yes') " +
            "ON CONFLICT DO NOTHING");
    }

    public List<RankState> all() {
        return db.query(SELECT+" ORDER BY role_id,amount,rank",(rs,i)->read(rs));
    }

    public RankState get(String id) {
        List<RankState> rows=db.query(SELECT+" WHERE salary_rank_id=?",(rs,i)->read(rs),id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Salary rank not found");
        return rows.get(0);
    }

    @Transactional
    public RankState create(RankState value) {
        try {
            db.update("INSERT INTO hr_salary_ranks(salary_rank_id,role_id,rank,amount," +
                    "status,created_at,version) VALUES(?,?,?,?,?,?,1)",
                value.id(),value.roleId(),value.rank(),value.amount(),value.status(),
                Timestamp.from(value.createdAt()));
        } catch(DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Salary rank already exists");
        }
        markPending(value.id(),1L);
        return get(value.id());
    }

    @Transactional
    public RankState update(String id,String rank,java.math.BigDecimal amount,String status) {
        Long old=db.query("SELECT version FROM hr_salary_ranks WHERE salary_rank_id=? FOR UPDATE",
            rs->rs.next()?rs.getLong(1):null,id);
        if(old==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Salary rank not found");
        long next=old+1;
        try {
            db.update("UPDATE hr_salary_ranks SET rank=?,amount=?,status=?," +
                "version=?,updated_at=NOW() WHERE salary_rank_id=?",
                rank,amount,status,next,id);
        } catch(DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Salary rank already exists");
        }
        markPending(id,next);
        return get(id);
    }

    private void markPending(String id,long version) {
        db.update("INSERT INTO hr_salary_rank_outbox(salary_rank_id,version,delivered_at) " +
            "VALUES(?,?,NULL) ON CONFLICT(salary_rank_id) DO UPDATE " +
            "SET version=EXCLUDED.version,delivered_at=NULL",id,version);
    }

    public List<Pending> pending(int limit) {
        return db.query("SELECT r.salary_rank_id,r.role_id,r.rank,r.amount,r.status," +
                "r.created_at,r.version FROM hr_salary_rank_outbox o " +
                "JOIN hr_salary_ranks r ON r.salary_rank_id=o.salary_rank_id " +
                "WHERE o.delivered_at IS NULL ORDER BY r.salary_rank_id LIMIT ?",
            (rs,i)->new Pending(read(rs),rs.getLong(7)),limit);
    }

    public void acknowledge(String id,long version) {
        db.update("UPDATE hr_salary_rank_outbox SET delivered_at=NOW() " +
            "WHERE salary_rank_id=? AND version=? AND delivered_at IS NULL",id,version);
    }
}
