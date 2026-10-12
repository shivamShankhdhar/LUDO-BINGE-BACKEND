-- Flyway creates and tracks the dedicated schema before applying this migration.
-- No game/auth tables exist in the foundation yet. Add them with feature-specific
-- V2+ migrations rather than creating a speculative production data model.
COMMENT ON SCHEMA "${flyway:defaultSchema}" IS 'Ludo Binge authoritative backend; managed by Flyway';
