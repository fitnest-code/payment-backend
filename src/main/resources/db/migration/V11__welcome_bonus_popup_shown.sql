-- Canonical column name aligned with API field welcomeBonusPopupShown.
ALTER TABLE welcome_bonus_identifiers
    ADD COLUMN IF NOT EXISTS welcome_bonus_popup_shown BOOLEAN NOT NULL DEFAULT FALSE;

-- If an earlier draft added the short name popup_shown, migrate it.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'welcome_bonus_identifiers'
          AND column_name = 'popup_shown'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'welcome_bonus_identifiers'
          AND column_name = 'welcome_bonus_popup_shown'
    ) THEN
        EXECUTE 'UPDATE welcome_bonus_identifiers SET welcome_bonus_popup_shown = popup_shown';
        EXECUTE 'ALTER TABLE welcome_bonus_identifiers DROP COLUMN popup_shown';
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'welcome_bonus_identifiers'
          AND column_name = 'popup_shown'
    ) THEN
        EXECUTE 'ALTER TABLE welcome_bonus_identifiers RENAME COLUMN popup_shown TO welcome_bonus_popup_shown';
    END IF;
END $$;

-- Historical awards predate the popup UX; mark them shown so we don't surprise existing users.
UPDATE welcome_bonus_identifiers
SET welcome_bonus_popup_shown = TRUE
WHERE welcome_bonus_popup_shown = FALSE;
