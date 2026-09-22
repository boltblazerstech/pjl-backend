CREATE TABLE document_group (
    id BIGSERIAL PRIMARY KEY,
    bill_category_id BIGINT NOT NULL REFERENCES bill_category(id),
    name VARCHAR(255) NOT NULL,
    invoice_file_ref VARCHAR(255),
    po_file_ref VARCHAR(255),
    grn_file_ref VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

ALTER TABLE submission 
ADD COLUMN group_id BIGINT REFERENCES document_group(id);
