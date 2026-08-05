-- FitNest Coin Loyalty System - Flyway Migration V4
-- Date: 2026-08-03
-- Description: Add coin_wallets, coin_transactions, coin_settings, and welcome_bonus_identifiers tables

BEGIN;

CREATE TABLE IF NOT EXISTS coin_wallets (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    balance NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    created_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_modified_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    last_modified_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_coin_wallets_user_id ON coin_wallets(user_id);

CREATE TABLE IF NOT EXISTS coin_transactions (
    id BIGSERIAL PRIMARY KEY,
    wallet_id BIGINT NOT NULL REFERENCES coin_wallets(id),
    user_id BIGINT NOT NULL,
    type VARCHAR(30) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    balance_after NUMERIC(12, 2) NOT NULL,
    order_id VARCHAR(100),
    payment_id BIGINT,
    expiry_date TIMESTAMP WITH TIME ZONE,
    remaining_amount NUMERIC(12, 2) DEFAULT 0.00,
    description VARCHAR(255),
    created_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_modified_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    last_modified_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_coin_tx_user_id ON coin_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_coin_tx_expiry ON coin_transactions(remaining_amount, expiry_date) WHERE remaining_amount > 0;

CREATE TABLE IF NOT EXISTS coin_settings (
    id BIGSERIAL PRIMARY KEY,
    welcome_bonus_amount NUMERIC(10, 2) NOT NULL DEFAULT 50.00,
    earn_rate_azn_to_coin NUMERIC(10, 2) NOT NULL DEFAULT 1.00,
    spend_rate_coin_to_azn NUMERIC(10, 2) NOT NULL DEFAULT 20.00,
    max_discount_percentage NUMERIC(5, 2) NOT NULL DEFAULT 20.00,
    expiry_months INT NOT NULL DEFAULT 12,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_modified_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    last_modified_by VARCHAR(255)
);

INSERT INTO coin_settings (welcome_bonus_amount, earn_rate_azn_to_coin, spend_rate_coin_to_azn, max_discount_percentage, expiry_months, active)
SELECT 50.00, 1.00, 20.00, 20.00, 12, TRUE
WHERE NOT EXISTS (SELECT 1 FROM coin_settings);

CREATE TABLE IF NOT EXISTS welcome_bonus_identifiers (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    phone_hash VARCHAR(255),
    email_hash VARCHAR(255),
    created_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_modified_date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    last_modified_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_wb_phone_hash ON welcome_bonus_identifiers(phone_hash);
CREATE INDEX IF NOT EXISTS idx_wb_email_hash ON welcome_bonus_identifiers(email_hash);
CREATE INDEX IF NOT EXISTS idx_wb_user_id ON welcome_bonus_identifiers(user_id);

ALTER TABLE payments ADD COLUMN IF NOT EXISTS coins_used NUMERIC(12, 2) DEFAULT 0.00;

COMMIT;
