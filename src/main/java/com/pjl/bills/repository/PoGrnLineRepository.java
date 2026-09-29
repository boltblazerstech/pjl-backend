package com.pjl.bills.repository;

import com.pjl.bills.entity.PoGrnLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface PoGrnLineRepository extends JpaRepository<PoGrnLine, Long> {
    
    List<PoGrnLine> findByPoNumberIn(Set<String> poNumbers);

    List<PoGrnLine> findByPoNumber(String poNumber);

    org.springframework.data.domain.Page<PoGrnLine> findByImportBatch_Id(Long importBatchId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<PoGrnLine> findByPoNumberContainingIgnoreCase(String poNumber, org.springframework.data.domain.Pageable pageable);
}
