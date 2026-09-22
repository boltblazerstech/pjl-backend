package com.pjl.bills.repository;

import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findBySubmission(Submission submission);
}
