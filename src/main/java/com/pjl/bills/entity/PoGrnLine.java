package com.pjl.bills.entity;

import com.pjl.core.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "po_grn_line")
@Getter
@Setter
public class PoGrnLine extends AuditableEntity {

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private PoGrnImportBatch importBatch;

    @com.fasterxml.jackson.annotation.JsonProperty("importBatchId")
    public Long getImportBatchId() {
        return importBatch != null ? importBatch.getId() : null;
    }

    @Column(name = "po_number", nullable = false, length = 100)
    private String poNumber;

    @Column(name = "po_amendment_no", length = 100)
    private String poAmendmentNo;

    @Column(name = "po_date")
    private LocalDate poDate;

    @Column(name = "po_order_qty")
    private BigDecimal poOrderQty;

    @Column(name = "supplier_code", length = 100)
    private String supplierCode;

    @Column(name = "supplier_name", length = 255)
    private String supplierName;

    @Column(name = "payterm_desc", length = 255)
    private String paytermDesc;

    @Column(name = "gr_no", length = 100)
    private String grNo;

    @Column(name = "gr_date")
    private LocalDate grDate;

    @Column(name = "gr_status", length = 100)
    private String grStatus;

    @Column(name = "gr_line_no")
    private Integer grLineNo;

    @Column(name = "po_line_no", nullable = false)
    private Integer poLineNo;

    @Column(name = "item_code", length = 100)
    private String itemCode;

    @Column(name = "item_desc", length = 512)
    private String itemDesc;

    @Column(name = "delivery_note_qty")
    private BigDecimal deliveryNoteQty;

    @Column(name = "accepted_qty")
    private BigDecimal acceptedQty;

    @Column(name = "rejected_qty")
    private BigDecimal rejectedQty;

    @Column(name = "moved_qty")
    private BigDecimal movedQty;

    @Column(name = "received_qty")
    private BigDecimal receivedQty;

    @Column(name = "po_unit_rate")
    private BigDecimal poUnitRate;

    @Column(name = "po_line_value")
    private BigDecimal poLineValue;

    @Column(name = "po_status", length = 100)
    private String poStatus;

    @Column(name = "grn_value")
    private BigDecimal grnValue;

    @Column(name = "delynoten", length = 255)
    private String delynoten;

    @Column(name = "item_type", length = 100)
    private String itemType;

    @Column(name = "ou_id", length = 100)
    private String ouId;

    @Column(name = "ou_name", length = 255)
    private String ouName;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    /**
     * Helper to generate a unique key string for in-memory UPSERT matching.
     * Matches the UNIQUE NULLS NOT DISTINCT constraint in the DB.
     */
    public String getUniqueKey() {
        return poNumber + "|" +
               (poAmendmentNo != null ? poAmendmentNo : "") + "|" +
               poLineNo + "|" +
               (grNo != null ? grNo : "") + "|" +
               (grLineNo != null ? grLineNo : -1);
    }
}
