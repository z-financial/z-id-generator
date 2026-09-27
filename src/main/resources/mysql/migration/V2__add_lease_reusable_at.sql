-- Apply this one-time migration while all ID-generator processes are stopped.
--
-- 2,100,000 microseconds matches the library defaults:
--   1 second (largest supported Snowflake time unit)
-- + 1 second maximum relative clock-skew window
-- + 100 milliseconds safety margin.
--
-- If a deployment configures a larger skew window or safety margin, increase
-- this backfill interval before running the migration. GREATEST(..., DB now)
-- prevents already-expired rows from becoming reusable immediately when the
-- applications restart.

ALTER TABLE leases
    ADD COLUMN reusable_at DATETIME(3) NULL AFTER expired_at;

UPDATE leases
SET reusable_at = TIMESTAMPADD(
        MICROSECOND,
        2100000,
        GREATEST(expired_at, UTC_TIMESTAMP(3)))
WHERE reusable_at IS NULL;

ALTER TABLE leases
    MODIFY COLUMN reusable_at DATETIME(3) NOT NULL,
    ADD CONSTRAINT chk_lease_reusable_after_expiry CHECK (reusable_at > expired_at),
    ADD KEY idx_reusable(reusable_at, lease_id);
