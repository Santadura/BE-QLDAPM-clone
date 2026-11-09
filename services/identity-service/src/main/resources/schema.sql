-- Transitional isolated authentication database.
-- Legacy core remains the user/role write authority until all account commands
-- are migrated; this table is an explicitly synchronized credential projection.
CREATE TABLE IF NOT EXISTS identity_users (
  username VARCHAR(50) PRIMARY KEY,
  email VARCHAR(100) NOT NULL,
  password_hash TEXT NOT NULL,
  role_id VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL,
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS identity_revocations (
  token_id VARCHAR(50) PRIMARY KEY,
  revoked_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
