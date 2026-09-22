package com.pjl.bills.spares;

/**
 * Holds the exact, validated prompts and JSON schemas used to extract data 
 * from Spares-related documents via the Gemini API.
 */
public final class SparesExtractionPrompts {

    private SparesExtractionPrompts() {
        // Prevent instantiation of utility class
    }

    public static final String EXTRACTION_PROMPT = """
            Extract data from the provided document(s) and output ONLY a valid JSON object matching the exact schema below.
            
            SCHEMA:
            {
              "grn_present": boolean,
              "po_number": string or null,
              "grn_number": string or null,
              "grn_date": string (DD/MM/YYYY) or null,
              "supplier_code": string or null,
              "cost_center": string or null,
              "analysis_code": string or null,
              "sub_analysis_code": string or null,
              "supplier_name": string,
              "supplier_gstin": string or null,
              "invoice_no": string,
              "invoice_date": string (DD/MM/YYYY),
              "pay_term": string or null,
              "warranty_text": string or null,
              "po_warranty_requirement": string or null,
              "is_rcm": boolean,
              "line_items": [{ "description": string, "hsn_sac": string or null,
                "uom": string or null, "quantity": number, "rate": number,
                "discount_percent": number or null,
                "amount": number }],
              "cgst_amount": number or null,
              "sgst_amount": number or null,
              "igst_amount": number or null,
              "other_charges": number or null,
              "total_amount": number,
              "amount_in_words": string or null
            }
            
            FIELD NOTES AND INSTRUCTIONS:
            - line_items: IMPORTANT! You MUST extract EVERY SINGLE line item from the invoice. Do not summarize, skip, or truncate any line items, even if there are many. Look across all pages of the invoice. Missing even a single line item will cause financial validation to fail.
            - grn_number, cost_center, analysis_code, sub_analysis_code, and supplier_code come from the GRN page if present. If they are not found on the GRN page, return null. Never guess or infer these values from other pages.
            - is_rcm: If true, it means nil GST is expected (this is not an error).
            - discount_percent: If the invoice has a "Disc. %" or "Discount %" column on each line item, extract that numeric percentage value (e.g. 65 for 65%). If no per-line discount column exists, return null. This is critical for calculation verification.
            - igst_amount: Extract the IGST amount if present (inter-state supply). Many invoices use IGST instead of CGST+SGST. Extract whichever applies; leave the others null.
            - other_charges: Sum of any additional charges outside the line items — freight, packing, insurance, loading, etc. These are real invoice charges that contribute to the total but are NOT part of the line-item subtotal. Extract as a single number. Return null if no such charges exist.
            - warranty_text: Extract the raw prose regarding warranty directly from the Invoice.
            - po_warranty_requirement: Extract the raw prose regarding warranty requirement directly from the PO. Keep this separate from warranty_text so they can be compared later.
            """;
}
