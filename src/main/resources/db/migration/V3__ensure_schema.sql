DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns 
    WHERE table_name = 'users' AND column_name = 'id' AND udt_name <> 'uuid'
  ) THEN
    DROP TABLE users CASCADE;
  END IF;
END $$;
CREATE TABLE IF NOT EXISTS users (
 id uuid PRIMARY KEY, google_sub text UNIQUE, display_name varchar(80) NOT NULL,
 avatar_url text, avatar_version bigint NOT NULL DEFAULT 1,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DELETED','SUSPENDED')),
 generation bigint NOT NULL DEFAULT 0, connected_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS auth_challenges (id uuid PRIMARY KEY, nonce_hash text NOT NULL UNIQUE, expires_at timestamptz NOT NULL, consumed boolean NOT NULL DEFAULT false);
CREATE TABLE IF NOT EXISTS refresh_sessions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), family uuid NOT NULL,
 token_hash text NOT NULL UNIQUE, access_hash text UNIQUE, access_expires timestamptz NOT NULL,
 expires_at timestamptz NOT NULL, consumed boolean NOT NULL DEFAULT false, revoked boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS refresh_family_idx ON refresh_sessions(family);
CREATE INDEX IF NOT EXISTS refresh_user_idx ON refresh_sessions(user_id);
CREATE TABLE IF NOT EXISTS rooms (
 id uuid PRIMARY KEY, host_id uuid NOT NULL REFERENCES users(id), capacity int NOT NULL CHECK(capacity BETWEEN 2 AND 6),
 board text NOT NULL CHECK(board IN ('CLASSIC','SIX_PLAYER_HEX')), ruleset text NOT NULL,
 phase text NOT NULL DEFAULT 'LOBBY' CHECK(phase IN ('LOBBY','READY_CHECK','COUNTDOWN','PLAYING','FINISHED','CLOSED')),
 revision bigint NOT NULL DEFAULT 0, public_room boolean NOT NULL DEFAULT false,
 countdown_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK((capacity<=4 AND board='CLASSIC') OR (capacity>=5 AND board='SIX_PLAYER_HEX'))
);
CREATE TABLE IF NOT EXISTS room_members (
 room_id uuid NOT NULL REFERENCES rooms(id), user_id uuid NOT NULL REFERENCES users(id),
 seat int NOT NULL CHECK(seat BETWEEN 0 AND 5), ready boolean NOT NULL DEFAULT false,
 joined_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(room_id,user_id), UNIQUE(room_id,seat)
);
CREATE TABLE IF NOT EXISTS user_allocations (user_id uuid PRIMARY KEY REFERENCES users(id), kind text NOT NULL CHECK(kind IN ('ROOM','QUEUE')), resource_id uuid NOT NULL);
CREATE TABLE IF NOT EXISTS room_invites (room_id uuid PRIMARY KEY REFERENCES rooms(id), token_hash text NOT NULL UNIQUE, code varchar(8) NOT NULL UNIQUE, expires_at timestamptz NOT NULL);
CREATE TABLE IF NOT EXISTS join_requests (
 id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id), user_id uuid NOT NULL REFERENCES users(id),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','DECLINED','CANCELLED','EXPIRED')),
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS pending_join_idx ON join_requests(room_id,user_id) WHERE status='PENDING';
CREATE INDEX IF NOT EXISTS pending_join_expiry_idx ON join_requests(expires_at) WHERE status='PENDING';
CREATE TABLE IF NOT EXISTS api_receipts (user_id uuid NOT NULL REFERENCES users(id), key uuid NOT NULL, request_hash text NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,key));
CREATE TABLE IF NOT EXISTS matches (
 id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id), revision bigint NOT NULL DEFAULT 0,
 snapshot jsonb NOT NULL, status text NOT NULL DEFAULT 'PLAYING', deadline timestamptz NOT NULL,
 owner_id text, owner_epoch bigint NOT NULL DEFAULT 0, lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS one_live_match_idx ON matches(room_id) WHERE status='PLAYING';
CREATE TABLE IF NOT EXISTS match_players (match_id uuid NOT NULL REFERENCES matches(id), user_id uuid NOT NULL REFERENCES users(id), seat int NOT NULL, profile jsonb NOT NULL, PRIMARY KEY(match_id,user_id), UNIQUE(match_id,seat));
CREATE TABLE IF NOT EXISTS game_events (match_id uuid NOT NULL REFERENCES matches(id), seq bigint NOT NULL, event_id uuid NOT NULL UNIQUE, payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(match_id,seq));
CREATE TABLE IF NOT EXISTS command_receipts (match_id uuid NOT NULL REFERENCES matches(id), user_id uuid NOT NULL REFERENCES users(id), command_id uuid NOT NULL, request_hash text NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(match_id,user_id,command_id));
CREATE TABLE IF NOT EXISTS outbox (id bigserial PRIMARY KEY, room_id uuid REFERENCES rooms(id), user_id uuid REFERENCES users(id), payload jsonb NOT NULL, expires_at timestamptz, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS outbox_created_idx ON outbox(created_at);
CREATE TABLE IF NOT EXISTS gateway_offsets (instance_id text PRIMARY KEY, last_id bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS proposals (id uuid PRIMARY KEY, capacity int NOT NULL, expires_at timestamptz NOT NULL, status text NOT NULL DEFAULT 'PENDING');
CREATE TABLE IF NOT EXISTS queue_tickets (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), capacity int NOT NULL CHECK(capacity BETWEEN 2 AND 6),
 region text NOT NULL, ruleset text NOT NULL, status text NOT NULL DEFAULT 'SEARCHING',
 proposal_id uuid REFERENCES proposals(id), room_id uuid REFERENCES rooms(id), expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS queue_partition_idx ON queue_tickets(capacity,region,ruleset,created_at) WHERE status='SEARCHING';
CREATE UNIQUE INDEX IF NOT EXISTS active_ticket_idx ON queue_tickets(user_id) WHERE status IN ('SEARCHING','RESERVED','ACCEPTED');
CREATE TABLE IF NOT EXISTS blocks (user_id uuid REFERENCES users(id), blocked_id uuid REFERENCES users(id), PRIMARY KEY(user_id,blocked_id), CHECK(user_id<>blocked_id));
CREATE TABLE IF NOT EXISTS reports (id uuid PRIMARY KEY, reporter uuid NOT NULL REFERENCES users(id), reported uuid NOT NULL REFERENCES users(id), room_id uuid NOT NULL REFERENCES rooms(id), reason varchar(500) NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS player_stats (user_id uuid PRIMARY KEY REFERENCES users(id), played bigint NOT NULL DEFAULT 0, wins bigint NOT NULL DEFAULT 0, forfeits bigint NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS result_applications (match_id uuid REFERENCES matches(id), user_id uuid REFERENCES users(id), PRIMARY KEY(match_id,user_id));
CREATE TABLE IF NOT EXISTS rate_limits (key text PRIMARY KEY, window_at timestamptz NOT NULL, count int NOT NULL);
CREATE TABLE IF NOT EXISTS media_revocations (id bigserial PRIMARY KEY, room_id uuid NOT NULL, user_id uuid NOT NULL, completed boolean NOT NULL DEFAULT false, attempts int NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS media_webhooks (id text PRIMARY KEY, received_at timestamptz NOT NULL DEFAULT now());
