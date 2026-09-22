-- Document pages: stores individual file uploads (pages) for a single logical document.
-- A multi-page invoice uploaded as 3 separate images becomes 3 rows here,
-- all pointing to the same document_id.
CREATE TABLE document_page (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    page_order      INT NOT NULL DEFAULT 0,
    file_name       VARCHAR(500),
    mime_type       VARCHAR(100),
    file_data       BYTEA,
    created_at      TIMESTAMP DEFAULT now(),
    updated_at      TIMESTAMP DEFAULT now(),
    created_by      VARCHAR(255) DEFAULT 'system',
    updated_by      VARCHAR(255) DEFAULT 'system'
);

CREATE INDEX idx_document_page_document_id ON document_page(document_id);
