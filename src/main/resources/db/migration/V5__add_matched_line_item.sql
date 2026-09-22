CREATE TABLE matched_line_item (
    id                      BIGSERIAL PRIMARY KEY,
    submission_id           BIGINT NOT NULL REFERENCES submission(id) ON DELETE CASCADE,

    -- Description of the item as extracted from the invoice
    item_description        TEXT,

    -- Invoice-side values
    invoice_quantity        INT,
    invoice_rate            NUMERIC(18, 4),
    invoice_amount          NUMERIC(18, 4),

    -- PO-side values (from the matched PO line)
    po_quantity             INT,
    po_rate                 NUMERIC(18, 4),

    -- GRN-side values (null if GRN not present or line not matched to GRN)
    grn_accepted_quantity   INT,

    -- Score (0.0 – 1.0+) from the matching algorithm
    match_confidence        DOUBLE PRECISION,

    -- Exception message specific to this line item, if any (null = no exception)
    exception_reason        TEXT,

    -- Audit columns (required by AuditableEntity)
    created_at              TIMESTAMP DEFAULT now(),
    updated_at              TIMESTAMP DEFAULT now(),
    created_by              VARCHAR(255) DEFAULT 'system',
    updated_by              VARCHAR(255) DEFAULT 'system'
);

CREATE INDEX idx_matched_line_item_submission_id ON matched_line_item(submission_id);
