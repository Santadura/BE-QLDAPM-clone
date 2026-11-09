-- Idempotent, ordered HR certificate projection acknowledgements in Academic core.
-- Source-of-truth certificate writes move to HR; this table is transient
-- until all certificate references have migrated.
CREATE TABLE IF NOT EXISTS "hr_certificate_versions" (
  "certificate_id" VARCHAR(50) NOT NULL PRIMARY KEY,
  "version" BIGINT NOT NULL DEFAULT 0,
  "updated_at" TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
