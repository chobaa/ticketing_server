ALTER TABLE events
    ADD COLUMN listing_scope VARCHAR(32) NOT NULL DEFAULT 'PUBLIC' AFTER status;

CREATE INDEX idx_events_listing_scope ON events (listing_scope);
