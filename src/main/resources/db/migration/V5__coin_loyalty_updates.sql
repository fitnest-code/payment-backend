-- FitNest Coin Loyalty System - Flyway Migration V5
-- Date: 2026-08-26
-- Description: Remove 20% max discount constraint, set default max discount to 100% (full discount support)

BEGIN;

ALTER TABLE coin_settings
ALTER COLUMN max_discount_percentage SET DEFAULT 100.00;

UPDATE coin_settings
SET max_discount_percentage = 100.00
WHERE max_discount_percentage = 20.00;

COMMIT;
