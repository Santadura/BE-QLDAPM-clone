ALTER TABLE storefront_orders
 ADD COLUMN payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID','PENDING','PAID')),
 ADD COLUMN paid_at TIMESTAMPTZ(6),
 ADD COLUMN fulfillment_error VARCHAR(500);
CREATE TABLE payment_attempts (
 attempt_id VARCHAR(50) PRIMARY KEY,
 order_id VARCHAR(50) NOT NULL REFERENCES storefront_orders(order_id),
 provider VARCHAR(10) NOT NULL CHECK (provider IN ('VNPAY','MOMO')),
 amount NUMERIC(14,2) NOT NULL CHECK (amount>0),
 status VARCHAR(20) NOT NULL CHECK (status IN ('CREATING','PENDING','PAID','FAILED','EXPIRED')),
 pay_url TEXT, qr_data TEXT, transaction_id VARCHAR(100), result_code VARCHAR(30),
 created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at TIMESTAMPTZ(6) NOT NULL, paid_at TIMESTAMPTZ(6)
);
CREATE INDEX payment_attempts_order_idx ON payment_attempts(order_id,created_at);
CREATE UNIQUE INDEX payment_attempts_active_order_key ON payment_attempts(order_id) WHERE status IN ('CREATING','PENDING');
CREATE UNIQUE INDEX payment_attempts_provider_transaction_key ON payment_attempts(provider,transaction_id) WHERE transaction_id IS NOT NULL;
