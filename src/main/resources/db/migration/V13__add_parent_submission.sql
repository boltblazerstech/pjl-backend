-- V13: Add parent_submission_id for multi-invoice batch submissions

ALTER TABLE submission ADD COLUMN IF NOT EXISTS parent_submission_id BIGINT REFERENCES submission(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_submission_parent_id ON submission(parent_submission_id);
