-- V9: Add original_filename to document table, make file_ref nullable (for file deletion)
ALTER TABLE document ADD COLUMN IF NOT EXISTS original_filename VARCHAR(512);
ALTER TABLE document ALTER COLUMN file_ref DROP NOT NULL;
