-- Kapital Bank (KAPITAL) HPP integration.
-- provider_order_id: bank order id (payments.order_id keeps the FitNest order id so outbox/gRPC keys stay stable).
-- encrypted_order_password: HPP one-time order password, AES-GCM + base64 (see PaymentSecretEncryption); never plaintext.
-- installment_months: NULL = card, 3|6|12 = TAKSIT=N.
-- pmo_result_code: bank result code kept separately from payments.code (which carries approval code on success).
ALTER TABLE payments ADD COLUMN IF NOT EXISTS provider_order_id VARCHAR(64);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS encrypted_order_password TEXT;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS installment_months INTEGER;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS pmo_result_code VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_payments_provider_order_id ON payments(provider_order_id);
