-- V12: Widen text columns to prevent length overflow on large imports
ALTER TABLE po_grn_line ALTER COLUMN item_desc TYPE TEXT;
ALTER TABLE po_grn_line ALTER COLUMN payterm_desc TYPE TEXT;
ALTER TABLE po_grn_line ALTER COLUMN supplier_name TYPE TEXT;
ALTER TABLE po_grn_line ALTER COLUMN ou_name TYPE TEXT;
