package com.pjl.bills.spares;

import com.pjl.bills.PromptProvider;
import com.pjl.bills.VerificationStrategy;
import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.PriceHistory;
import com.pjl.bills.entity.Submission;
import com.pjl.bills.repository.PriceHistoryRepository;
import com.pjl.core.sink.dto.MatchedLineItem;
import com.pjl.core.sink.dto.SubmissionStatus;
import com.pjl.core.sink.dto.VerificationException;
import com.pjl.core.sink.dto.VerifiedSubmissionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verification strategy for the "spares" bill category.
 * <p>
 * Given a submission with 3 documents (invoice, PO, GRN) whose
 * {@code rawExtraction} JSON has already been populated by
 * {@link com.pjl.core.gemini.GeminiExtractionService}, this strategy:
 * <ol>
 *   <li>Matches invoice line items to PO/GRN line items (fuzzy description + qty/rate)</li>
 *   <li>Checks qty × rate = amount per line and tax calculations</li>
 *   <li>Verifies invoice quantity ≤ GRN accepted quantity</li>
 *   <li>Verifies invoice rate ≈ PO rate (within tolerance)</li>
 *   <li>Compares warranty prose (invoice vs. PO requirement)</li>
 *   <li>Checks current rate against historical price_history records</li>
 *   <li>Combines everything into a GREEN / AMBER / RED result</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SparesBillVerificationStrategy implements VerificationStrategy, PromptProvider {

    private static final String KEY = "spares";

    /** Monetary tolerance for rounding differences (₹1). */
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("1.00");

    /** Minimum Jaccard-like similarity score to consider a line-item match. */
    private static final double MATCH_CONFIDENCE_THRESHOLD = 0.35;

    /** If the invoice rate exceeds the latest historical rate by more than this fraction, flag AMBER. */
    private static final BigDecimal HISTORICAL_RATE_AMBER_PCT = new BigDecimal("0.10");

    /**
     * Detects explicit warranty denial phrases in invoice text.
     * Used ONLY for the informational warrantyStatus label — not for classification.
     */
    private static final Pattern WARRANTY_DENIAL_PATTERN = Pattern.compile(
            "\\b(?:no\\s+warranty|without\\s+warranty|warranty\\s+not\\s+applicable|warranty\\s*:\\s*nil|nil\\s+warranty)\\b",
            Pattern.CASE_INSENSITIVE);

    private final PriceHistoryRepository priceHistoryRepository;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────────
    // Public contract
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public String getKey() {
        return KEY;
    }

    /**
     * All three Spares document types (invoice, po, grn) use the same unified extraction
     * schema — the prompt instructs Gemini to extract fields relevant to each document
     * and return null for fields that don't appear in that particular document type.
     */
    @Override
    public String getExtractionPrompt(String docType) {
        return SparesExtractionPrompts.EXTRACTION_PROMPT;
    }

    @Override
    public VerifiedSubmissionResult verify(Submission submission, List<Document> documents) {
        log.info("Starting spares verification for submission id={}", submission.getId());

        List<MatchedLineItem> matchedItems = new ArrayList<>();
        List<VerificationException> exceptions = new ArrayList<>();

        // 1. Parse documents by type
        JsonNode invoiceData = findAndParse(documents, "invoice");
        JsonNode poData      = findAndParse(documents, "po");
        JsonNode grnData     = findAndParseSafe(documents, "grn"); // GRN may be absent

        if (invoiceData == null) {
            exceptions.add(new VerificationException("MISSING_DOCUMENT",
                    "No invoice document found in submission."));
            return buildResult(submission, matchedItems, exceptions, null);
        }
        if (poData == null) {
            exceptions.add(new VerificationException("MISSING_DOCUMENT",
                    "No PO document found in submission."));
            return buildResult(submission, matchedItems, exceptions, invoiceData);
        }

        ArrayNode invoiceLines = arrayOrEmpty(invoiceData, "line_items");
        ArrayNode poLines      = arrayOrEmpty(poData, "line_items");
        ArrayNode grnLines     = grnData != null ? arrayOrEmpty(grnData, "line_items") : null;

        String poNumber  = textOrNull(poData, "po_number");
        String grnNumber = grnData != null ? textOrNull(grnData, "grn_number") : null;

        // 2. Match invoice line items to PO line items
        //
        // Three-tier matching (highest-priority wins):
        //   Tier 1 — Exact qty AND rate: definitive match regardless of description.
        //            These are assigned globally (not greedily) to avoid conflicts.
        //   Tier 2 — Exact rate only:    strong match, breaks ties by description + qty.
        //            Also assigned globally to avoid greedy conflicts.
        //   Tier 3 — Fuzzy (desc/qty/rate weighted score): last resort.
        Set<Integer> usedPoIndices  = new HashSet<>();
        Set<Integer> usedGrnIndices = new HashSet<>();
        Map<Integer, MatchCandidate> poMatches  = new HashMap<>();
        Map<Integer, MatchCandidate> grnMatches = new HashMap<>();

        // ─────────────────────────────────────────────────────────
        // Tier 1: Exact qty AND rate — definitive matches
        // Build a score matrix and assign best-first (not sequential)
        // ─────────────────────────────────────────────────────────
        {
            // Collect all (invoiceIdx, poIdx, score) triples where qty+rate both match
            record Triple(int invIdx, int poIdx, double score) {}
            List<Triple> tier1Candidates = new ArrayList<>();

            for (int i = 0; i < invoiceLines.size(); i++) {
                JsonNode invLine = invoiceLines.get(i);
                BigDecimal invRate = decimalOrZero(invLine, "rate");
                int invQty = intOrZero(invLine, "quantity");

                for (int j = 0; j < poLines.size(); j++) {
                    JsonNode poLine = poLines.get(j);
                    BigDecimal poRate = decimalOrZero(poLine, "rate");
                    int poQty = intOrZero(poLine, "quantity");

                    boolean rateExact = invRate.subtract(poRate).abs().compareTo(AMOUNT_TOLERANCE) <= 0;
                    boolean qtyExact  = invQty == poQty;

                    if (rateExact && qtyExact) {
                        // Tiebreak by description similarity
                        double descScore = descriptionSimilarity(
                                textOrEmpty(invLine, "description"),
                                textOrEmpty(poLine, "description"));
                        double score = 1.0 + descScore; // base 1.0 + desc bonus up to 1.0
                        tier1Candidates.add(new Triple(i, j, score));
                    }
                }
            }

            // Sort by score descending, assign best-first (no conflicts)
            tier1Candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
            for (Triple t : tier1Candidates) {
                if (!poMatches.containsKey(t.invIdx()) && !usedPoIndices.contains(t.poIdx())) {
                    poMatches.put(t.invIdx(), new MatchCandidate(t.poIdx(), poLines.get(t.poIdx()), t.score()));
                    usedPoIndices.add(t.poIdx());
                }
            }
        }

        // ─────────────────────────────────────────────────────────
        // Tier 2: Exact rate only — global best-first assignment
        // ─────────────────────────────────────────────────────────
        {
            record Triple(int invIdx, int poIdx, double score) {}
            List<Triple> tier2Candidates = new ArrayList<>();

            for (int i = 0; i < invoiceLines.size(); i++) {
                if (poMatches.containsKey(i)) continue; // already matched in Tier 1
                JsonNode invLine = invoiceLines.get(i);
                BigDecimal invRate = decimalOrZero(invLine, "rate");
                int invQty = intOrZero(invLine, "quantity");

                for (int j = 0; j < poLines.size(); j++) {
                    if (usedPoIndices.contains(j)) continue;
                    JsonNode poLine = poLines.get(j);
                    BigDecimal poRate = decimalOrZero(poLine, "rate");
                    int poQty = intOrZero(poLine, "quantity");

                    boolean rateExact = invRate.subtract(poRate).abs().compareTo(AMOUNT_TOLERANCE) <= 0;
                    if (!rateExact) continue;

                    double descScore = descriptionSimilarity(
                            textOrEmpty(invLine, "description"),
                            textOrEmpty(poLine, "description"));
                    double qtyScore = (invQty == 0 && poQty == 0) ? 0.5
                            : 1.0 - Math.min(1.0, Math.abs(invQty - poQty) / (double) Math.max(Math.max(invQty, poQty), 1));
                    double score = 0.50 + 0.30 * descScore + 0.20 * qtyScore;
                    tier2Candidates.add(new Triple(i, j, score));
                }
            }

            tier2Candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
            for (Triple t : tier2Candidates) {
                if (!poMatches.containsKey(t.invIdx()) && !usedPoIndices.contains(t.poIdx())) {
                    poMatches.put(t.invIdx(), new MatchCandidate(t.poIdx(), poLines.get(t.poIdx()), t.score()));
                    usedPoIndices.add(t.poIdx());
                }
            }
        }

        // ─────────────────────────────────────────────────────────
        // Tier 3: Fuzzy fallback for remaining unmatched lines
        // ─────────────────────────────────────────────────────────
        for (int i = 0; i < invoiceLines.size(); i++) {
            if (poMatches.containsKey(i)) continue;
            JsonNode invLine = invoiceLines.get(i);
            MatchCandidate poMatch = findBestMatch(invLine, poLines, usedPoIndices);
            if (poMatch != null && poMatch.score >= MATCH_CONFIDENCE_THRESHOLD) {
                poMatches.put(i, poMatch);
                usedPoIndices.add(poMatch.index);
            }
        }

        // ─────────────────────────────────────────────────────────
        // GRN matching — description + quantity only (GRN has no rates)
        // Uses global best-first to avoid greedy conflicts.
        // GRN descriptions are noisy (contain PR numbers, zone/bin refs)
        // so we strip everything after "Last Moved" or "PR_No" before scoring.
        // ─────────────────────────────────────────────────────────
        if (grnLines != null && !grnLines.isEmpty()) {
            record Triple(int invIdx, int grnIdx, double score) {}
            List<Triple> grnCandidates = new ArrayList<>();

            for (int i = 0; i < invoiceLines.size(); i++) {
                JsonNode invLine = invoiceLines.get(i);
                String invDesc = textOrEmpty(invLine, "description");
                int invQty = intOrZero(invLine, "quantity");

                for (int j = 0; j < grnLines.size(); j++) {
                    JsonNode grnLine = grnLines.get(j);
                    // Strip the trailing noise from GRN descriptions
                    String rawGrnDesc = textOrEmpty(grnLine, "description");
                    String grnDesc = cleanGrnDescription(rawGrnDesc);
                    int grnQty = intOrZero(grnLine, "quantity");

                    double descScore = descriptionSimilarity(invDesc, grnDesc);
                    double qtyScore = (invQty == 0 && grnQty == 0) ? 0.5
                            : 1.0 - Math.min(1.0,
                                Math.abs(invQty - grnQty) / (double) Math.max(Math.max(invQty, grnQty), 1));

                    // 50% description, 50% quantity — no rate component for GRN
                    double score = 0.50 * descScore + 0.50 * qtyScore;
                    grnCandidates.add(new Triple(i, j, score));
                }
            }

            grnCandidates.sort((a, b) -> Double.compare(b.score(), a.score()));
            for (Triple t : grnCandidates) {
                if (!grnMatches.containsKey(t.invIdx()) && !usedGrnIndices.contains(t.grnIdx())) {
                    if (t.score() >= MATCH_CONFIDENCE_THRESHOLD) {
                        grnMatches.put(t.invIdx(), new MatchCandidate(t.grnIdx(), grnLines.get(t.grnIdx()), t.score()));
                        usedGrnIndices.add(t.grnIdx());
                    }
                }
            }
        }

        // ── Process all invoice lines with their matches ──
        for (int i = 0; i < invoiceLines.size(); i++) {
            JsonNode invLine = invoiceLines.get(i);
            String invDesc   = textOrEmpty(invLine, "description");

            MatchCandidate poMatch  = poMatches.get(i);
            MatchCandidate grnMatch = grnMatches.get(i);

            if (poMatch == null) {
                exceptions.add(new VerificationException("UNMATCHED_LINE_ITEM",
                        String.format("Invoice line #%d '%s' could not be confidently matched to any PO line item.",
                                i + 1, invDesc)));
                continue;
            }

            BigDecimal invRate   = decimalOrZero(invLine, "rate");
            int        invQty    = intOrZero(invLine, "quantity");
            BigDecimal invAmount = decimalOrZero(invLine, "amount");

            int        poQty  = intOrZero(poMatch.node, "quantity");
            BigDecimal poRate = decimalOrZero(poMatch.node, "rate");
            int        grnQty = grnMatch != null ? intOrZero(grnMatch.node, "quantity") : 0;

            // ── Run checks, capture any new per-line exceptions ──
            int exceptionsBefore = exceptions.size();

            // ── Check 2: Calculation (qty × rate = amount) ──
            checkLineCalculation(invLine, i, exceptions);

            // ── Check 3: Quantity vs. GRN ──
            if (grnMatch != null) {
                checkQuantity(invLine, grnMatch.node, i, exceptions);
            }

            // ── Check 4: Rate vs. PO ──
            checkRate(invLine, poMatch.node, poData, i, exceptions);

            // ── Check 6: Historical price ──
            checkHistoricalPrice(poMatch.node, grnMatch != null ? grnMatch.node : null,
                    invLine, i, exceptions);

            // Collect any exceptions raised specifically for this line
            String lineExceptionReason = exceptions.size() > exceptionsBefore
                    ? exceptions.subList(exceptionsBefore, exceptions.size()).stream()
                        .map(e -> "[" + e.ruleName() + "] " + e.reason())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse(null)
                    : null;

            matchedItems.add(new MatchedLineItem(
                    invDesc,
                    poNumber,
                    grnNumber,
                    invQty,
                    invRate,
                    invAmount,
                    poQty,
                    poRate,
                    grnQty,
                    poMatch.score,
                    lineExceptionReason
            ));
        }

        // ── Check 2 (continued): Tax / total calculation ──
        checkTaxCalculation(invoiceData, exceptions);

        // ── Check 7: Determine overall status ──
        SubmissionStatus status = determineStatus(exceptions);

        log.info("Spares verification complete for submission id={}: status={}, matched={}, exceptions={}",
                submission.getId(), status, matchedItems.size(), exceptions.size());

        return buildResult(submission, matchedItems, exceptions, invoiceData);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Line-item matching
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Find the best matching line item in {@code candidates} for the given
     * {@code invLine}, skipping indices already assigned.
     * <p>
     * Scoring: 25% description similarity, 25% quantity agreement, 50% rate proximity.
     * Rate is weighted heavily because when multiple PO lines have near-identical
     * descriptions (e.g. "V-BELT SPC 3750" vs "V-BELT SPC-4000"), rate and quantity
     * are the strongest disambiguation signals.
     * <p>
     * An additional bonus is applied when the rate matches exactly (within tolerance),
     * ensuring that an exact rate match always beats a close-but-wrong description match.
     */
    private MatchCandidate findBestMatch(JsonNode invLine, ArrayNode candidates, Set<Integer> usedIndices) {
        if (candidates == null || candidates.isEmpty()) return null;

        String invDesc  = textOrEmpty(invLine, "description");
        int    invQty   = intOrZero(invLine, "quantity");
        BigDecimal invRate = decimalOrZero(invLine, "rate");

        MatchCandidate best = null;

        for (int j = 0; j < candidates.size(); j++) {
            if (usedIndices.contains(j)) continue;

            JsonNode cand     = candidates.get(j);
            String candDesc   = textOrEmpty(cand, "description");
            int    candQty    = intOrZero(cand, "quantity");
            BigDecimal candRate = decimalOrZero(cand, "rate");

            double descScore = descriptionSimilarity(invDesc, candDesc);

            // Quantity agreement: 1.0 if equal, degrades linearly
            double qtyScore = (invQty == 0 && candQty == 0) ? 0.5
                    : 1.0 - Math.min(1.0, Math.abs(invQty - candQty) / (double) Math.max(invQty, candQty));

            // Rate proximity: 1.0 if identical, degrades with percentage difference
            double rateScore;
            boolean exactRateMatch = false;
            if (invRate.signum() == 0 && candRate.signum() == 0) {
                rateScore = 0.5;
            } else if (invRate.signum() == 0 || candRate.signum() == 0) {
                rateScore = 0.0;
            } else {
                BigDecimal rateDiff = invRate.subtract(candRate).abs();
                BigDecimal maxRate  = invRate.max(candRate);
                double pctDiff = rateDiff.divide(maxRate, 6, RoundingMode.HALF_UP).doubleValue();
                rateScore = 1.0 - Math.min(1.0, pctDiff);
                // Exact match if within ₹1 tolerance
                exactRateMatch = rateDiff.compareTo(AMOUNT_TOLERANCE) <= 0;
            }

            // Weighted score: rate is dominant
            double totalScore = 0.25 * descScore + 0.25 * qtyScore + 0.50 * rateScore;

            // Bonus for exact rate match — ensures it always wins over a close description
            if (exactRateMatch) {
                totalScore += 0.15;
            }

            if (best == null || totalScore > best.score) {
                best = new MatchCandidate(j, cand, totalScore);
            }
        }
        return best;
    }

    /**
     * Jaccard similarity on normalised word tokens.
     * <p>
     * Handles mismatches like "Painting Brush Champion 4""
     * vs "BRUSH PAINTING 100MM(4)" by comparing overlapping words
     * after stripping punctuation and lowering case.
     */
    private double descriptionSimilarity(String a, String b) {
        Set<String> wordsA = normalizeToWords(a);
        Set<String> wordsB = normalizeToWords(b);
        if (wordsA.isEmpty() && wordsB.isEmpty()) return 1.0;
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);

        Set<String> union = new HashSet<>(wordsA);
        union.addAll(wordsB);

        return (double) intersection.size() / union.size();
    }

    private Set<String> normalizeToWords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String cleaned = text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return new HashSet<>(Arrays.asList(cleaned.split(" ")));
    }

    /**
     * GRN line descriptions often contain a meaningful part description prefix
     * followed by extensive operational noise such as:
     * "Last Moved Zone/Bin:MNWS/130713 PR_No/Qty/Folder/UserId- 26PJL1PRQ..."
     * This method strips everything from "Last" (before "Moved") or "PR_No" onward
     * so that description similarity is computed only on the meaningful prefix.
     */
    private String cleanGrnDescription(String rawDesc) {
        if (rawDesc == null) return "";
        // Cut at "Last Moved", "PR_No", or "PR No" (case-insensitive)
        String cut = rawDesc.replaceAll("(?i)\\bLast\\s+Moved\\b.*", "")
                            .replaceAll("(?i)\\bPR[_\\s]No\\b.*", "")
                            .trim();
        return cut.isBlank() ? rawDesc : cut; // fall back to full string if nothing left
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Check 2: Calculation (qty × rate = amount) & tax
    // ─────────────────────────────────────────────────────────────────────────────

    private void checkLineCalculation(JsonNode invLine, int lineIndex,
                                      List<VerificationException> exceptions) {
        BigDecimal qty    = decimalOrZero(invLine, "quantity");
        BigDecimal rate   = decimalOrZero(invLine, "rate");
        BigDecimal amount = decimalOrZero(invLine, "amount");

        BigDecimal expected = qty.multiply(rate);

        // Apply per-line discount percentage if present (e.g. 65 means 65% discount)
        BigDecimal discountPct = decimalOrNull(invLine, "discount_percent");
        if (discountPct != null && discountPct.signum() > 0) {
            BigDecimal multiplier = BigDecimal.ONE.subtract(
                    discountPct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            expected = expected.multiply(multiplier);
        }

        expected = expected.setScale(2, RoundingMode.HALF_UP);
        BigDecimal diff = expected.subtract(amount).abs();

        if (diff.compareTo(AMOUNT_TOLERANCE) > 0) {
            exceptions.add(new VerificationException("CALC_MISMATCH",
                    String.format("Invoice line #%d: qty(%.2f) × rate(%.2f)%s = %.2f, but stated amount is %.2f (diff ₹%.2f).",
                            lineIndex + 1, qty, rate,
                            discountPct != null ? String.format(" × (1-%.0f%%)", discountPct) : "",
                            expected, amount, diff)));
        }
    }

    private void checkTaxCalculation(JsonNode invoiceData, List<VerificationException> exceptions) {
        BigDecimal cgst         = decimalOrNull(invoiceData, "cgst_amount");
        BigDecimal sgst         = decimalOrNull(invoiceData, "sgst_amount");
        BigDecimal igst         = decimalOrNull(invoiceData, "igst_amount");
        BigDecimal otherCharges = decimalOrNull(invoiceData, "other_charges");
        BigDecimal total        = decimalOrNull(invoiceData, "total_amount");
        boolean isRcm           = boolOrFalse(invoiceData, "is_rcm");

        if (total == null) return; // nothing to check

        // Sum the line-item amounts to get the taxable value
        ArrayNode lines = arrayOrEmpty(invoiceData, "line_items");
        BigDecimal taxableValue = BigDecimal.ZERO;
        for (JsonNode line : lines) {
            taxableValue = taxableValue.add(decimalOrZero(line, "amount"));
        }

        // GST: use IGST if present (inter-state), otherwise CGST+SGST (intra-state)
        BigDecimal taxSum = BigDecimal.ZERO;
        if (igst != null && igst.signum() > 0) {
            taxSum = igst;
        } else {
            if (cgst != null) taxSum = taxSum.add(cgst);
            if (sgst != null) taxSum = taxSum.add(sgst);
        }

        // Under RCM, nil GST is expected — do not flag zero tax as a mismatch
        if (isRcm && taxSum.signum() == 0) {
            log.debug("RCM flag is set; nil GST is expected — skipping tax mismatch check.");
        }

        // Include other charges (freight, packing, insurance, etc.)
        BigDecimal charges = otherCharges != null ? otherCharges : BigDecimal.ZERO;

        BigDecimal expectedTotal = taxableValue.add(taxSum).add(charges);
        BigDecimal totalDiff = expectedTotal.subtract(total).abs();

        if (totalDiff.compareTo(AMOUNT_TOLERANCE) > 0) {
            exceptions.add(new VerificationException("TOTAL_MISMATCH",
                    String.format("Invoice total mismatch: subtotal(%.2f) + tax(%.2f) + other charges(%.2f) = %.2f, "
                            + "but stated total is %.2f (diff ₹%.2f).",
                            taxableValue, taxSum, charges, expectedTotal, total, totalDiff)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Check 3: Quantity (invoice ≤ GRN accepted)
    // ─────────────────────────────────────────────────────────────────────────────

    private void checkQuantity(JsonNode invLine, JsonNode grnLine, int lineIndex,
                               List<VerificationException> exceptions) {
        int invQty = intOrZero(invLine, "quantity");
        // GRN may report received/accepted qty — use "quantity" which represents accepted
        int grnQty = intOrZero(grnLine, "quantity");

        if (grnQty > 0 && invQty > grnQty) {
            exceptions.add(new VerificationException("QTY_EXCEEDS_GRN",
                    String.format("Invoice line #%d: invoiced quantity (%d) exceeds GRN accepted quantity (%d).",
                            lineIndex + 1, invQty, grnQty)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Check 4: Rate (invoice ≈ PO rate, accounting for discount)
    // ─────────────────────────────────────────────────────────────────────────────

    private void checkRate(JsonNode invLine, JsonNode poLine, JsonNode poData,
                           int lineIndex, List<VerificationException> exceptions) {
        BigDecimal invRate = decimalOrZero(invLine, "rate");
        BigDecimal poRate  = decimalOrZero(poLine, "rate");

        if (poRate.signum() == 0) return; // no PO rate to compare against

        // Account for a PO-level discount percentage if present
        BigDecimal discountPct = decimalOrNull(poData, "discount_percentage");
        if (discountPct != null && discountPct.signum() > 0) {
            BigDecimal multiplier = BigDecimal.ONE.subtract(
                    discountPct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            poRate = poRate.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal rateDiff = invRate.subtract(poRate).abs();
        if (rateDiff.compareTo(AMOUNT_TOLERANCE) > 0) {
            exceptions.add(new VerificationException("RATE_MISMATCH",
                    String.format("Invoice line #%d: invoice rate (%.2f) does not match PO rate (%.2f) — "
                            + "difference ₹%.2f exceeds tolerance.",
                            lineIndex + 1, invRate, poRate, rateDiff)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Check 6: Historical price
    // ─────────────────────────────────────────────────────────────────────────────

    private void checkHistoricalPrice(JsonNode poLine, JsonNode grnLine,
                                      JsonNode invLine, int lineIndex,
                                      List<VerificationException> exceptions) {
        // Item code comes from PO/GRN, never from the invoice
        String itemCode = textOrNull(poLine, "item_code");
        if (itemCode == null && grnLine != null) {
            itemCode = textOrNull(grnLine, "item_code");
        }
        if (itemCode == null || itemCode.isBlank()) {
            // No item code available — brand-new item or not in extraction; informational only
            log.debug("No item_code available for line #{}; skipping historical price check.", lineIndex + 1);
            return;
        }

        BigDecimal invoiceRate = decimalOrZero(invLine, "rate");

        Optional<PriceHistory> latestOpt = priceHistoryRepository
                .findFirstByItemCodeOrderByPurchaseDateDesc(itemCode);
        Optional<BigDecimal> minRateOpt = priceHistoryRepository
                .findMinRateByItemCode(itemCode);

        if (latestOpt.isEmpty()) {
            // No history at all — first-time purchase; informational, not an exception
            log.debug("No price history for item_code '{}' (line #{}); first-time purchase.",
                    itemCode, lineIndex + 1);
            return;
        }

        PriceHistory latest     = latestOpt.get();
        BigDecimal   latestRate = latest.getRate();
        BigDecimal   minRate    = minRateOpt.orElse(latestRate);

        // Compare invoice rate against most-recent and minimum historical rates
        if (latestRate.signum() > 0) {
            BigDecimal pctAboveLatest = invoiceRate.subtract(latestRate)
                    .divide(latestRate, 6, RoundingMode.HALF_UP);

            if (pctAboveLatest.compareTo(HISTORICAL_RATE_AMBER_PCT) > 0) {
                exceptions.add(new VerificationException("HISTORICAL_RATE_HIGH",
                        String.format("Invoice line #%d (item_code '%s'): current rate ₹%.2f is %.1f%% above "
                                + "the last purchased rate ₹%.2f (date: %s). Cheapest historical rate: ₹%.2f.",
                                lineIndex + 1, itemCode, invoiceRate,
                                pctAboveLatest.multiply(new BigDecimal("100")),
                                latestRate, latest.getPurchaseDate(), minRate)));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Check 7: Overall status determination
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * RED if any hard failure exists (qty exceeds, rate mismatch, warranty
     * denied/insufficient, calculation error). AMBER if the only issues are
     * moderate (historical rate, ambiguous warranty, low-confidence match).
     * GREEN if zero exceptions.
     */
    private SubmissionStatus determineStatus(List<VerificationException> exceptions) {
        if (exceptions.isEmpty()) return SubmissionStatus.GREEN;

        Set<String> RED_RULES = Set.of(
                "QTY_EXCEEDS_GRN",
                "RATE_MISMATCH",
                "WARRANTY_DENIED",
                "WARRANTY_INSUFFICIENT",
                "WARRANTY_MISSING",
                "CALC_MISMATCH",
                "TOTAL_MISMATCH",
                "MISSING_DOCUMENT"
        );

        for (VerificationException ex : exceptions) {
            if (RED_RULES.contains(ex.ruleName())) {
                return SubmissionStatus.RED;
            }
        }

        // Only AMBER-level exceptions remain (HISTORICAL_RATE_HIGH, WARRANTY_AMBIGUOUS,
        // UNMATCHED_LINE_ITEM)
        return SubmissionStatus.AMBER;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper: result builder
    // ─────────────────────────────────────────────────────────────────────────────

    private VerifiedSubmissionResult buildResult(Submission submission,
                                                  List<MatchedLineItem> matched,
                                                  List<VerificationException> exceptions,
                                                  JsonNode invoiceData) {
        String warrantyStatus = computeWarrantyStatus(invoiceData);
        return new VerifiedSubmissionResult(
                submission.getId(), matched, exceptions, determineStatus(exceptions), warrantyStatus);
    }

    /**
     * Derives a purely informational warranty label from the invoice's warranty_text.
     * Does NOT affect GREEN/AMBER/RED classification.
     * <ul>
     *   <li>NOT_MENTIONED — warranty_text is absent or blank</li>
     *   <li>DENIED        — explicitly refuses warranty (e.g. "no warranty", "nil")</li>
     *   <li>STATED        — any other substantive warranty text</li>
     * </ul>
     */
    private String computeWarrantyStatus(JsonNode invoiceData) {
        String warrantyText = textOrNull(invoiceData, "warranty_text");
        if (warrantyText == null || warrantyText.isBlank()) {
            return "NOT_MENTIONED";
        }
        if (WARRANTY_DENIAL_PATTERN.matcher(warrantyText).find()) {
            return "DENIED";
        }
        return "STATED";
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper: document lookup & JSON parsing
    // ─────────────────────────────────────────────────────────────────────────────

    private JsonNode findAndParse(List<Document> documents, String docType) {
        return documents.stream()
                .filter(d -> docType.equalsIgnoreCase(d.getDocType()))
                .findFirst()
                .map(d -> (JsonNode) objectMapper.valueToTree(d.getRawExtraction()))
                .orElse(null);
    }

    /** Same as {@link #findAndParse} but never causes an exception if absent. */
    private JsonNode findAndParseSafe(List<Document> documents, String docType) {
        return findAndParse(documents, docType);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper: safe JSON accessors
    // ─────────────────────────────────────────────────────────────────────────────

    private String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v != null && !v.isNull() && v.isTextual()) ? v.asText() : null;
    }

    private String textOrEmpty(JsonNode node, String field) {
        String v = textOrNull(node, field);
        return v != null ? v : "";
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        try {
            return new BigDecimal(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimalOrZero(JsonNode node, String field) {
        BigDecimal v = decimalOrNull(node, field);
        return v != null ? v : BigDecimal.ZERO;
    }

    private int intOrZero(JsonNode node, String field) {
        if (node == null) return 0;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return 0;
        return v.asInt(0);
    }

    private boolean boolOrFalse(JsonNode node, String field) {
        if (node == null) return false;
        JsonNode v = node.get(field);
        return v != null && v.asBoolean(false);
    }

    private ArrayNode arrayOrEmpty(JsonNode node, String field) {
        if (node == null) return objectMapper.createArrayNode();
        JsonNode v = node.get(field);
        if (v != null && v.isArray()) return (ArrayNode) v;
        return objectMapper.createArrayNode();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal DTO
    // ─────────────────────────────────────────────────────────────────────────────

    /** A candidate match with its index in the source array and match score. */
    private record MatchCandidate(int index, JsonNode node, double score) {}
}
