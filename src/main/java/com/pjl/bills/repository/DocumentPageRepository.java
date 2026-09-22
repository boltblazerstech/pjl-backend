package com.pjl.bills.repository;

import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.DocumentPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentPageRepository extends JpaRepository<DocumentPage, Long> {

    List<DocumentPage> findByDocumentOrderByPageOrderAsc(Document document);
}
