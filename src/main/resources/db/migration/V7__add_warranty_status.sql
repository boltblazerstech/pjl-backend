-- V7: Add warranty_status informational label to submission
-- This field is derived from the invoice's warranty_text at verification time.
-- Values: NOT_MENTIONED, STATED, DENIED
-- It is purely informational and has no effect on GREEN/AMBER/RED classification.
ALTER TABLE submission ADD COLUMN IF NOT EXISTS warranty_status VARCHAR(20);
