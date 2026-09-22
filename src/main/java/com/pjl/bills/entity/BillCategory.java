package com.pjl.bills.entity;

import com.pjl.core.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "bill_category")
@Getter
@Setter
public class BillCategory extends AuditableEntity {

    @Column(name = "name", unique = true, nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_document_types", columnDefinition = "jsonb")
    private List<String> requiredDocumentTypes;

    @Column(name = "verification_strategy_key", nullable = false)
    private String verificationStrategyKey;
}
