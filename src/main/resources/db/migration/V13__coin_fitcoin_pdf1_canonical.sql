-- PDF 1 canonical Fitcoin model (FitNest Fitcoin model).
-- Fitcoins = Amount paid x Giveback rate x 10, HALF_UP.
-- Giveback = 0.02 x Tier x Period, capped at 0.05. 1 AZN = 10 Fitcoins.
-- Dev fix: dev DB had tier keys "1"-"4" all 1.00 and periods all 1.00.
-- This migration is idempotent and enforces canonical values for all settings rows.

UPDATE coin_settings
SET formula_version = 'EARN_V2_20260901',
    base_earn_rate = 0.020000,
    max_giveback_rate = 0.050000,
    earn_coin_factor = 10.00,
    spend_rate_coin_to_azn = 10.00
WHERE formula_version IS DISTINCT FROM 'EARN_V2_20260901'
   OR base_earn_rate IS DISTINCT FROM 0.020000
   OR max_giveback_rate IS DISTINCT FROM 0.050000
   OR earn_coin_factor IS DISTINCT FROM 10.00
   OR spend_rate_coin_to_azn IS DISTINCT FROM 10.00;

-- Canonical tier multipliers by package ID (1=Bronze, 2=Silver, 3=Gold, 4=Platinum).
INSERT INTO coin_tier_multipliers (settings_id, tier_name, multiplier)
SELECT cs.id, t.tier_name, t.multiplier
FROM coin_settings cs
CROSS JOIN (VALUES
    ('1', 1.0000),
    ('2', 1.1000),
    ('3', 1.2000),
    ('4', 1.3000)
) AS t(tier_name, multiplier)
ON CONFLICT (settings_id, tier_name)
DO UPDATE SET multiplier = EXCLUDED.multiplier;

-- Canonical tier multipliers by uppercase tier name (V8 seed form + case-insensitive lookup).
INSERT INTO coin_tier_multipliers (settings_id, tier_name, multiplier)
SELECT cs.id, t.tier_name, t.multiplier
FROM coin_settings cs
CROSS JOIN (VALUES
    ('BRONZE', 1.0000),
    ('SILVER', 1.1000),
    ('GOLD', 1.2000),
    ('PLATINUM', 1.3000)
) AS t(tier_name, multiplier)
ON CONFLICT (settings_id, tier_name)
DO UPDATE SET multiplier = EXCLUDED.multiplier;

-- Canonical period multipliers (PDF 1: 1->1.00, 3->1.15, 6->1.30, 12->1.50).
INSERT INTO coin_period_multipliers (settings_id, duration_months, multiplier)
SELECT cs.id, p.duration_months, p.multiplier
FROM coin_settings cs
CROSS JOIN (VALUES
    (1, 1.0000),
    (3, 1.1500),
    (6, 1.3000),
    (12, 1.5000)
) AS p(duration_months, multiplier)
ON CONFLICT (settings_id, duration_months)
DO UPDATE SET multiplier = EXCLUDED.multiplier;
