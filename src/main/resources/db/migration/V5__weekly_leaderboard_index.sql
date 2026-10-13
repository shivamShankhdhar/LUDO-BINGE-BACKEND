CREATE INDEX IF NOT EXISTS finished_matches_leaderboard_idx ON matches(finished_at) WHERE status='FINISHED';
