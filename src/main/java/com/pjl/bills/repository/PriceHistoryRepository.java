package com.pjl.bills.repository;

import com.pjl.bills.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    /**
     * Returns the most recent purchase record for the given item code (by purchase date).
     */
    Optional<PriceHistory> findFirstByItemCodeOrderByPurchaseDateDesc(String itemCode);

    /**
     * Returns the lowest rate ever recorded for the given item code.
     */
    @Query("SELECT MIN(p.rate) FROM PriceHistory p WHERE p.itemCode = :itemCode")
    Optional<BigDecimal> findMinRateByItemCode(@Param("itemCode") String itemCode);
}
