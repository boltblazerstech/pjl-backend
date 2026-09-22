package com.pjl.bills.entity;

import com.pjl.core.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "document")
@Getter
@Setter
public class Document extends AuditableEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @Column(name = "doc_type", nullable = false)
    private String docType;

    @Column(name = "file_ref")
    private String fileRef;

    /** The original uploaded file name (e.g. "Invoice-9.pdf"). */
    @Column(name = "original_filename", length = 512)
    private String originalFilename;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_extraction", columnDefinition = "jsonb")
    private Map<String, Object> rawExtraction;

    @jakarta.persistence.Basic(fetch = jakarta.persistence.FetchType.LAZY)
    @Column(name = "file_data")
    private byte[] fileData;
}
