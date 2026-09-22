package com.pjl.bills.entity;

import com.pjl.core.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "document_group")
@Getter
@Setter
public class DocumentGroup extends AuditableEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "bill_category_id", nullable = false)
    private BillCategory billCategory;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "invoice_file_ref")
    private String invoiceFileRef;

    @Column(name = "po_file_ref")
    private String poFileRef;

    @Column(name = "grn_file_ref")
    private String grnFileRef;
}
