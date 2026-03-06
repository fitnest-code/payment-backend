-- Epoint Integration - Add Missing Fields for Callback Idempotency
-- Version: 1.0
-- Date: 2026-03-06
-- Description: Add new fields to payments table to support complete Epoint integration
--              and implement callback idempotency

BEGIN;

-- Add missing Epoint response fields
ALTER TABLE payments
ADD COLUMN IF NOT EXISTS code VARCHAR(255),
ADD COLUMN IF NOT EXISTS bank_response TEXT,
ADD COLUMN IF NOT EXISTS operation_code VARCHAR(50),
ADD COLUMN IF NOT EXISTS callback_processed BOOLEAN DEFAULT FALSE;

-- Create index for callback idempotency check
-- This optimizes the lookup: "Is this callback already processed?"
CREATE INDEX IF NOT EXISTS idx_payments_callback_processed
ON payments(order_id, callback_processed)
WHERE callback_processed = FALSE;

-- Also create a composite index for audit purposes
CREATE INDEX IF NOT EXISTS idx_payments_callback_audit
ON payments(order_id, callback_processed, updated_at);

-- Add comments for documentation
COMMENT ON COLUMN payments.code IS 'Error/response code from Epoint API';
COMMENT ON COLUMN payments.bank_response IS 'Response from the bank for this transaction';
COMMENT ON COLUMN payments.operation_code IS 'Epoint operation code (200 = success, as per Epoint spec)';
COMMENT ON COLUMN payments.callback_processed IS 'Flag to ensure callback idempotency - prevents duplicate processing of Epoint retry attempts';

COMMIT;

-- Verification queries (run after migration to verify success)
/*
-- Check new columns exist
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name='payments'
AND column_name IN ('code', 'bank_response', 'operation_code', 'callback_processed');

-- Check indexes exist
SELECT indexname FROM pg_indexes
WHERE tablename='payments'
AND indexname LIKE 'idx_payments_callback%';

-- Verify data integrity (should be 0 rows with callback_processed = true initially)
SELECT COUNT(*) as processed_callbacks
FROM payments
WHERE callback_processed = TRUE;

-- Check unused space in callback_processed column
SELECT callback_processed, COUNT(*) as count
FROM payments
GROUP BY callback_processed;
*/

o