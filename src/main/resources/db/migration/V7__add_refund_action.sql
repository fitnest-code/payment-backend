-- FitNest Coin Loyalty System - Flyway Migration V7
-- Date: 2026-08-27
-- Description: Add refund_action column to coin_transactions table for refund event action tracking

BEGIN;

ALTER TABLE coin_transactions
ADD COLUMN IF NOT EXISTS refund_action VARCHAR(30);

COMMIT;
