ALTER TABLE refresh_sessions ADD COLUMN IF NOT EXISTS retry_key uuid;
ALTER TABLE refresh_sessions ADD COLUMN IF NOT EXISTS retry_response text;
ALTER TABLE refresh_sessions ADD COLUMN IF NOT EXISTS retry_until timestamptz;
CREATE TABLE IF NOT EXISTS room_messages (
 id bigserial PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id), user_id uuid NOT NULL REFERENCES users(id),
 kind text NOT NULL, body varchar(80) NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS room_message_recent ON room_messages(room_id,id);
CREATE TABLE IF NOT EXISTS voice_signals (
 id bigserial PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id), sender uuid NOT NULL REFERENCES users(id),
 recipient uuid NOT NULL REFERENCES users(id), payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS voice_signal_recipient ON voice_signals(recipient,id);
CREATE TABLE IF NOT EXISTS voice_members (
 room_id uuid NOT NULL REFERENCES rooms(id), user_id uuid NOT NULL REFERENCES users(id),
 expires_at timestamptz NOT NULL, PRIMARY KEY(room_id,user_id)
);
