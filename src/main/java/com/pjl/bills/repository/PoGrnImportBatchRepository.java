package com.pjl.bills.repository;

import com.pjl.bills.entity.PoGrnImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PoGrnImportBatchRepository extends JpaRepository<PoGrnImportBatch, Long> {
    
    List<PoGrnImportBatch> findAllByOrderByImportedAtDesc();
}
