ALTER TABLE welcome_bonus_identifiers
    ADD COLUMN IF NOT EXISTS popup_shown BOOLEAN NOT NULL DEFAULT FALSE;

-- Historical awards predate the popup UX; mark them shown so we don't surprise existing users.
UPDATE welcome_bonus_identifiers
SET popup_shown = TRUE
WHERE popup_shown = FALSE;
