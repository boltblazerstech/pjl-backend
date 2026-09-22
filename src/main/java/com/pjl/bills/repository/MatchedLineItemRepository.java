package com.pjl.bills.repository;

import com.pjl.bills.entity.MatchedLineItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchedLineItemRepository extends JpaRepository<MatchedLineItemEntity, Long> {

    List<MatchedLineItemEntity> findBySubmissionIdOrderByIdAsc(Long submissionId);
}
