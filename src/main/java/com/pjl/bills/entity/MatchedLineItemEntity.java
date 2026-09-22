package com.pjl.bills.entity;

import com.pjl.core.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Persisted record of a single line item that was matched during bill verification.
 * One row exists per invoice line per submission (regardless of outcome).
 * Submissions that failed at the extraction stage have zero rows.
 */
@Entity
@Table(name = "matched_line_item")
@Getter
@Setter
public class MatchedLineItemEntity extends AuditableEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @Column(name = "item_description", columnDefinition = "TEXT")
    private String itemDescription;

    // ── Invoice side ──
    @Column(name = "invoice_quantity")
    private Integer invoiceQuantity;

    @Column(name = "invoice_rate", precision = 18, scale = 4)
    private BigDecimal invoiceRate;

    @Column(name = "invoice_amount", precision = 18, scale = 4)
    private BigDecimal invoiceAmount;

    // ── PO side ──
    @Column(name = "po_quantity")
    private Integer poQuantity;

    @Column(name = "po_rate", precision = 18, scale = 4)
    private BigDecimal poRate;

    // ── GRN side ──
    @Column(name = "grn_accepted_quantity")
    private Integer grnAcceptedQuantity;

    // ── Match metadata ──
    @Column(name = "match_confidence")
    private Double matchConfidence;

    @Column(name = "exception_reason", columnDefinition = "TEXT")
    private String exceptionReason;
}
