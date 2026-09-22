package com.pjl.bills.repository;

import com.pjl.bills.entity.DocumentGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentGroupRepository extends JpaRepository<DocumentGroup, Long> {
}
