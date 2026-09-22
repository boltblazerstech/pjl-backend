-- V8: Remove DocumentGroup feature — fold file refs directly into Submission

-- 1. Drop the foreign key / group_id column from submission
ALTER TABLE submission DROP COLUMN IF EXISTS group_id;

-- 2. Drop the document_group table entirely
DROP TABLE IF EXISTS document_group;
