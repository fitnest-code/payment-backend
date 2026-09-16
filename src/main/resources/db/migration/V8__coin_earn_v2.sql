-- Coin earn formula v2: tier/period multipliers and audit fields

ALTER TABLE coin_settings
    ADD COLUMN IF NOT EXISTS formula_version VARCHAR(50) DEFAULT 'EARN_V1',
    ADD COLUMN IF NOT EXISTS base_earn_rate NUMERIC(10, 6),
    ADD COLUMN IF NOT EXISTS max_giveback_rate NUMERIC(10, 6) DEFAULT 0.050000,
    ADD COLUMN IF NOT EXISTS earn_coin_factor NUMERIC(10, 2) DEFAULT 10.00;

CREATE TABLE IF NOT EXISTS coin_tier_multipliers (
    settings_id BIGINT NOT NULL REFERENCES coin_settings(id) ON DELETE CASCADE,
    tier_name VARCHAR(50) NOT NULL,
    multiplier NUMERIC(10, 4) NOT NULL DEFAULT 1.0000,
    PRIMARY KEY (settings_id, tier_name)
);

CREATE TABLE IF NOT EXISTS coin_period_multipliers (
    settings_id BIGINT NOT NULL REFERENCES coin_settings(id) ON DELETE CASCADE,
    duration_months INT NOT NULL,
    multiplier NUMERIC(10, 4) NOT NULL DEFAULT 1.0000,
    PRIMARY KEY (settings_id, duration_months)
);

ALTER TABLE coin_transactions
    ADD COLUMN IF NOT EXISTS formula_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS eligible_cash_amount NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS raw_coins NUMERIC(18, 6),
    ADD COLUMN IF NOT EXISTS awarded_coins INTEGER,
    ADD COLUMN IF NOT EXISTS earn_breakdown TEXT;

-- Seed v2 defaults for existing active settings row
INSERT INTO coin_tier_multipliers (settings_id, tier_name, multiplier)
SELECT cs.id, t.tier_name, t.multiplier
FROM coin_settings cs
CROSS JOIN (VALUES
    ('BRONZE', 1.0000),
    ('SILVER', 1.1000),
    ('GOLD', 1.2000),
    ('PLATINUM', 1.3000)
) AS t(tier_name, multiplier)
WHERE NOT EXISTS (
    SELECT 1 FROM coin_tier_multipliers ctm WHERE ctm.settings_id = cs.id
);

INSERT INTO coin_period_multipliers (settings_id, duration_months, multiplier)
SELECT cs.id, p.duration_months, p.multiplier
FROM coin_settings cs
CROSS JOIN (VALUES
    (1, 1.0000),
    (3, 1.1500),
    (6, 1.3000),
    (12, 1.5000)
) AS p(duration_months, multiplier)
WHERE NOT EXISTS (
    SELECT 1 FROM coin_period_multipliers cpm WHERE cpm.settings_id = cs.id
);

UPDATE coin_settings
SET formula_version = COALESCE(formula_version, 'EARN_V2_20260901'),
    base_earn_rate = COALESCE(base_earn_rate, 0.020000),
    max_giveback_rate = COALESCE(max_giveback_rate, 0.050000),
    earn_coin_factor = COALESCE(earn_coin_factor, 10.00)
WHERE base_earn_rate IS NULL;
