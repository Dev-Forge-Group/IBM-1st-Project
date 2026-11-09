-- New reservations use the six non-overlapping, same-day fixed slots.
-- Only requested/confirmed rows occupy a slot; NULL permits inactive history.
-- Existing active duplicates must be reviewed before applying this migration.
ALTER TABLE space_reservations
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN sr_st IN ('requested', 'confirmed') THEN 1 ELSE NULL END
    ) STORED,
    ADD UNIQUE INDEX uk_space_reservation_active_slot
        (space_id, sr_start_at, sr_end_at, active_slot);
