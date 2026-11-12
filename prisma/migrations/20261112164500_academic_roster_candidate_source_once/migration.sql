-- Core and Academic are independent PostgreSQL databases. An already
-- released Core roster claim MUST NOT admit a new actor/request key for
-- the same immutable source event: Academic already stores a UNIQUE
-- (entity_type,entity_id,source_event_id) candidate transaction. Without
-- this Core constraint, a second request can create an OPEN orphan that
-- cannot stage locally and permanently fences handback.
--
-- Keep the original (actor,request_key) primary key for idempotent retries.
-- This additional uniqueness is across BOTH OPEN and RELEASED states.
-- Deliberately fail migration if any preexisting claims conflict.
CREATE UNIQUE INDEX academic_roster_claim_once_per_source_event
  ON academic_roster_candidate_claims
    (entity_type,entity_id,source_event_id);
