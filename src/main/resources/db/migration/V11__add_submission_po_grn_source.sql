-- V11: Add source tracking to submission

ALTER TABLE submission ADD COLUMN IF NOT EXISTS po_grn_source VARCHAR(50) DEFAULT 'upload';
ALTER TABLE submission ADD COLUMN IF NOT EXISTS po_grn_import_batch_id BIGINT REFERENCES po_grn_import_batch(id);
