package com.example.demo.service;

import com.example.demo.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Phase 8b-2: a durable idempotency boundary for legacy Core assignment writes.
 *
 * When the caller supplies Idempotency-Key, the receipt and domain changes
 * commit or roll back in ONE Core PostgreSQL transaction. This avoids duplicate
 * assignments, audit events and repeat range-replacement side effects after
 * client retries/timeouts. It is not a distributed transaction, and Core
 * remains the only assignment writer until Academic and Scheduling are split.
 */
@Service
public class SchedulingCommandReceiptService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private record Receipt(String requestHash, String responseJson) {}

    public SchedulingCommandReceiptService(JdbcTemplate db, ObjectMapper json,
                                           PlatformTransactionManager txManager) {
        this.db = db;
        this.json = json;
        this.transactions = new TransactionTemplate(txManager);
    }

    /**
     * Without a header, keep the existing public API behaviour unchanged.
     * Authorize the current actor BEFORE calling this method, including for a
     * replay: a disabled/revoked or role-changed account must not receive an
     * old result merely by knowing a previously used key.
     */
    @SuppressWarnings("unchecked")
    public <T> T execute(String key, String actorUsername, String commandType,
                         Object request, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        if (key == null) return work.get();
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Idempotency-Key must be 1-128 safe ASCII characters");
        if (actorUsername == null || actorUsername.isBlank()
                || commandType == null || commandType.isBlank())
            throw new IllegalArgumentException("Authenticated actor and command are required");
        final String digest = fingerprint(request);
        return transactions.execute(tx -> {
            // PostgreSQL's unique-index conflict waits for the first transaction
            // to commit/abort. A rollback permits the retry to do the real work.
            int created = db.update(
                "INSERT INTO scheduling_command_receipts " +
                "(actor_username,command_type,request_key,request_hash) " +
                "VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                actorUsername, commandType, key, digest);
            if (created == 0) {
                List<Receipt> existing = db.query(
                    "SELECT request_hash,response_payload::text FROM " +
                    "scheduling_command_receipts WHERE actor_username=? " +
                    "AND command_type=? AND request_key=? FOR UPDATE",
                    (rs,i) -> new Receipt(rs.getString(1),rs.getString(2)),
                    actorUsername, commandType, key);
                if (existing.isEmpty())
                    throw new IllegalStateException("Idempotency receipt disappeared");
                Receipt prior = existing.get(0);
                if (!digest.equals(prior.requestHash()))
                    throw new ApiException(HttpStatus.CONFLICT,
                        "Idempotency-Key was already used with different request data");
                if (prior.responseJson() == null)
                    throw new IllegalStateException("Incomplete committed idempotency receipt");
                try {
                    // HTTP serialization is identical for original and replayed
                    // Map/List responses. JSON null represents a successful 204.
                    return (T) json.readValue(prior.responseJson(), Object.class);
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("Corrupted idempotency receipt", ex);
                }
            }
            T result = work.get();
            String payload;
            try {
                payload = json.writeValueAsString(result);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Cannot persist assignment response", ex);
            }
            int updated = db.update(
                "UPDATE scheduling_command_receipts SET response_payload=" +
                "CAST(? AS jsonb),completed_at=NOW() " +
                "WHERE actor_username=? AND command_type=? AND request_key=?",
                payload, actorUsername, commandType, key);
            if (updated != 1)
                throw new IllegalStateException("Cannot finish assignment idempotency receipt");
            return result;
        });
    }

    private String fingerprint(Object request) {
        try {
            byte[] encoded=json.writeValueAsBytes(request);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(encoded);
            return HexFormat.of().formatHex(digest);
        } catch(JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot compute assignment request digest",ex);
        }
    }
}
