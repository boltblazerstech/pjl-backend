package com.pjl.bills.entity;

import com.pjl.core.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "po_grn_import_batch")
@Getter
@Setter
public class PoGrnImportBatch extends AuditableEntity {

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "rows_read", nullable = false)
    private int rowsRead = 0;

    @Column(name = "rows_inserted", nullable = false)
    private int rowsInserted = 0;

    @Column(name = "rows_updated", nullable = false)
    private int rowsUpdated = 0;

    @Column(name = "rows_skipped", nullable = false)
    private int rowsSkipped = 0;

    @Column(name = "distinct_pos", nullable = false)
    private int distinctPos = 0;

    @Column(name = "submissions_resolved", nullable = false)
    private int submissionsResolved = 0;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;
}
