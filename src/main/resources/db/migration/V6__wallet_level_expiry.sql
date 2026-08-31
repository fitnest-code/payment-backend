-- FitNest Coin Loyalty System - Flyway Migration V6
-- Date: 2026-08-27
-- Description: Add first_coin_earned_at and expiry_date to coin_wallets for wallet-level expiration model

BEGIN;

ALTER TABLE coin_wallets
ADD COLUMN IF NOT EXISTS first_coin_earned_at TIMESTAMP WITH TIME ZONE,
ADD COLUMN IF NOT EXISTS expiry_date TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_coin_wallets_expiry ON coin_wallets(expiry_date) WHERE balance > 0;

COMMIT;
