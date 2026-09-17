-- Make welcome bonus one-per-user at the database, and backfill identifiers
-- for historical BONUS credits that never wrote a row (catch-up cron raced Kafka).

DELETE FROM welcome_bonus_identifiers a
    USING welcome_bonus_identifiers b
WHERE a.user_id = b.user_id
  AND a.id > b.id;

INSERT INTO welcome_bonus_identifiers (user_id, welcome_bonus_popup_shown, created_date, last_modified_date)
SELECT DISTINCT t.user_id, TRUE, NOW(), NOW()
FROM coin_transactions t
WHERE t.type = 'BONUS'
  AND NOT EXISTS (
      SELECT 1 FROM welcome_bonus_identifiers i WHERE i.user_id = t.user_id
  );

DROP INDEX IF EXISTS idx_wb_user_id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_user_id
    ON welcome_bonus_identifiers (user_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_phone_hash
    ON welcome_bonus_identifiers (phone_hash)
    WHERE phone_hash IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_email_hash
    ON welcome_bonus_identifiers (email_hash)
    WHERE email_hash IS NOT NULL;
