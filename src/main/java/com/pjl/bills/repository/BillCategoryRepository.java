package com.pjl.bills.repository;

import com.pjl.bills.entity.BillCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BillCategoryRepository extends JpaRepository<BillCategory, Long> {
}
