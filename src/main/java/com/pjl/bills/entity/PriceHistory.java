package com.pjl.bills.entity;

import com.pjl.core.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "price_history", indexes = {
    @Index(name = "idx_price_history_item_code", columnList = "item_code")
})
@Getter
@Setter
public class PriceHistory extends AuditableEntity {

    @Column(name = "item_code", nullable = false)
    private String itemCode;

    @Column(name = "vendor")
    private String vendor;

    @Column(name = "rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal rate;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "source")
    private String source;
}
