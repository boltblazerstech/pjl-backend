package com.pjl.bills.entity;

import com.pjl.core.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Represents a single uploaded file (page) belonging to a logical {@link Document}.
 * <p>
 * A multi-page invoice uploaded as 3 separate image files becomes 3 DocumentPage
 * rows, all sharing the same parent Document.
 */
@Entity
@Table(name = "document_page")
@Getter
@Setter
public class DocumentPage extends AuditableEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "page_order", nullable = false)
    private int pageOrder;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "mime_type")
    private String mimeType;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_data")
    private byte[] fileData;
}
