package com.pjl.core.storage;

import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.DocumentPage;
import com.pjl.bills.repository.DocumentPageRepository;
import com.pjl.bills.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Default implementation of {@link FileStorage} that stores file bytes directly
 * in PostgreSQL using BYTEA columns.
 * <p>
 * Single-file uploads go into {@code document.file_data} (backward compatible).
 * Multi-file uploads go into the {@code document_page} table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostgresFileStorage implements FileStorage {

    private final DocumentRepository documentRepository;
    private final DocumentPageRepository documentPageRepository;

    @Override
    @Transactional
    public void store(Document document, byte[] fileBytes) {
        log.debug("Storing {} bytes in PostgreSQL for Document ID {}", fileBytes.length, document.getId());
        document.setFileData(fileBytes);
        documentRepository.save(document);
    }

    @Override
    @Transactional
    public void storePages(Document document, List<PageUpload> pages) {
        log.debug("Storing {} pages in PostgreSQL for Document ID {}", pages.size(), document.getId());
        for (int i = 0; i < pages.size(); i++) {
            PageUpload page = pages.get(i);
            DocumentPage dp = new DocumentPage();
            dp.setDocument(document);
            dp.setPageOrder(i);
            dp.setFileName(page.fileName());
            dp.setMimeType(page.mimeType());
            dp.setFileData(page.fileBytes());
            documentPageRepository.save(dp);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] retrieve(Document document) {
        log.debug("Retrieving bytes from PostgreSQL for Document ID {}", document.getId());
        // Try document.file_data first (single-file legacy)
        byte[] data = documentRepository.findById(document.getId())
                .map(Document::getFileData)
                .orElse(null);
        if (data != null && data.length > 0) return data;

        // Fall back to first page
        List<DocumentPage> pages = documentPageRepository.findByDocumentOrderByPageOrderAsc(document);
        if (!pages.isEmpty()) {
            return pages.getFirst().getFileData();
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentPage> retrievePages(Document document) {
        return documentPageRepository.findByDocumentOrderByPageOrderAsc(document);
    }

    @Override
    public String uploadRawFile(String pathPrefix, String fileName, String contentType, byte[] fileBytes) {
        throw new UnsupportedOperationException("Raw file uploads are only supported on modern cloud storage (R2).");
    }
}
