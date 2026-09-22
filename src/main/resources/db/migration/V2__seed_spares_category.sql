INSERT INTO bill_category (
    name, 
    required_document_types, 
    verification_strategy_key,
    created_by,
    updated_by
) VALUES (
    'Spares', 
    '["invoice", "po", "grn"]'::jsonb, 
    'spares',
    'system',
    'system'
);
