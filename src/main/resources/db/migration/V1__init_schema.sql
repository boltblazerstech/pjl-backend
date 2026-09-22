-- 1. bill_category
CREATE TABLE bill_category (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    name VARCHAR(255) NOT NULL UNIQUE,
    required_document_types JSONB,
    verification_strategy_key VARCHAR(255) NOT NULL
);

-- 2. submission
CREATE TABLE submission (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    bill_category_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    uploaded_at TIMESTAMPTZ,
    reviewer_notes TEXT,

    CONSTRAINT fk_submission_bill_category 
        FOREIGN KEY (bill_category_id) 
        REFERENCES bill_category(id)
);

-- 3. document
CREATE TABLE document (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    submission_id BIGINT NOT NULL,
    doc_type VARCHAR(50) NOT NULL,
    file_ref VARCHAR(255) NOT NULL,
    raw_extraction JSONB,

    CONSTRAINT fk_document_submission 
        FOREIGN KEY (submission_id) 
        REFERENCES submission(id) 
        ON DELETE CASCADE
);

-- 4. price_history
CREATE TABLE price_history (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    item_code VARCHAR(255) NOT NULL,
    vendor VARCHAR(255),
    rate NUMERIC(19, 4) NOT NULL,
    purchase_date DATE,
    source VARCHAR(100)
);

CREATE INDEX idx_price_history_item_code ON price_history(item_code);

-- 5. audit_log
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    entity_type VARCHAR(100) NOT NULL,
    entity_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    detail TEXT,
    actor VARCHAR(255)
);

CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);
