-- Detected by a third party that is allowed to be unavailable, so the column is nullable:
-- an unknown language must never stop a message being stored.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS language VARCHAR(8);
