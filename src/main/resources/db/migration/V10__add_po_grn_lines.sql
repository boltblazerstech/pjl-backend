-- V10: Create PO/GRN lines and import tracking

CREATE TABLE po_grn_import_batch (
    id BIGSERIAL PRIMARY KEY,
    file_name VARCHAR(512),
    rows_read INT NOT NULL DEFAULT 0,
    rows_inserted INT NOT NULL DEFAULT 0,
    rows_updated INT NOT NULL DEFAULT 0,
    rows_skipped INT NOT NULL DEFAULT 0,
    distinct_pos INT NOT NULL DEFAULT 0,
    submissions_resolved INT NOT NULL DEFAULT 0,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE po_grn_line (
    id BIGSERIAL PRIMARY KEY,
    import_batch_id BIGINT REFERENCES po_grn_import_batch(id),
    
    po_number VARCHAR(100) NOT NULL,
    po_amendment_no VARCHAR(100),
    po_date DATE,
    po_order_qty NUMERIC(19, 4),
    supplier_code VARCHAR(100),
    supplier_name VARCHAR(255),
    payterm_desc VARCHAR(255),
    
    gr_no VARCHAR(100),
    gr_date DATE,
    gr_status VARCHAR(100),
    gr_line_no INT,
    
    po_line_no INT NOT NULL,
    item_code VARCHAR(100),
    item_desc VARCHAR(512),
    delivery_note_qty NUMERIC(19, 4),
    accepted_qty NUMERIC(19, 4),
    rejected_qty NUMERIC(19, 4),
    moved_qty NUMERIC(19, 4),
    received_qty NUMERIC(19, 4),
    
    po_unit_rate NUMERIC(19, 4),
    po_line_value NUMERIC(19, 4),
    po_status VARCHAR(100),
    grn_value NUMERIC(19, 4),
    delynoten VARCHAR(255),
    item_type VARCHAR(100),
    ou_id VARCHAR(100),
    ou_name VARCHAR(255),
    
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- PG 15+ allows NULLS NOT DISTINCT for unique indexing on nullable columns
ALTER TABLE po_grn_line ADD CONSTRAINT uq_po_grn_line 
UNIQUE NULLS NOT DISTINCT (po_number, po_amendment_no, po_line_no, gr_no, gr_line_no);
