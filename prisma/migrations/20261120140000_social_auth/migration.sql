ALTER TABLE "users" ADD COLUMN "password_login_enabled" BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE "users" ADD COLUMN "display_name" VARCHAR(100);

CREATE TABLE "social_identities" (
  "provider" VARCHAR(20) NOT NULL,
  "subject" VARCHAR(255) NOT NULL,
  "username" VARCHAR(50) NOT NULL REFERENCES "users"("username") ON DELETE CASCADE,
  "provider_email" VARCHAR(100),
  "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "social_identities_pkey" PRIMARY KEY ("provider", "subject"),
  CONSTRAINT "social_identities_user_provider_key" UNIQUE ("username", "provider"),
  CONSTRAINT "social_identities_provider_check" CHECK ("provider" IN ('google', 'facebook'))
);
CREATE TABLE "social_login_tickets" (
  "code_hash" VARCHAR(64) PRIMARY KEY,
  "username" VARCHAR(50) NOT NULL REFERENCES "users"("username") ON DELETE CASCADE,
  "challenge" VARCHAR(43) NOT NULL,
  "expires_at" TIMESTAMPTZ(6) NOT NULL,
  "consumed_at" TIMESTAMPTZ(6)
);
CREATE INDEX "social_login_tickets_expires_at_idx" ON "social_login_tickets"("expires_at");
