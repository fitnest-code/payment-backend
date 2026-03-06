-- Add unique constraint on (user_id, card_id) to prevent cross-user card access
-- This ensures that a card_id can be registered for multiple users (if Epoint allows that),
-- but each (user_id, card_id) pair is unique.
-- This is a security fix to prevent one user from overwriting another user's card data.

ALTER TABLE user_cards
ADD CONSTRAINT uk_user_cards_user_card UNIQUE (user_id, card_id);

