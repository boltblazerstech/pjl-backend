package com.pjl.bills.entity;

import com.pjl.core.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "submission")
@Getter
@Setter
public class Submission extends AuditableEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "bill_category_id", nullable = false)
    private BillCategory billCategory;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "reviewer_notes", columnDefinition = "TEXT")
    private String reviewerNotes;

    /**
     * Informational-only warranty label derived from the invoice's warranty_text at verification time.
     * One of: NOT_MENTIONED / STATED / DENIED. Never affects GREEN/AMBER/RED status.
     */
    @Column(name = "warranty_status", length = 20)
    private String warrantyStatus;
}
