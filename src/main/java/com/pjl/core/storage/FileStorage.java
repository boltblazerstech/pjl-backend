package com.pjl.core.storage;

import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.DocumentPage;

import java.util.List;

/**
 * Interface abstracting the storage of raw uploaded files.
 * <p>
 * This allows swapping from PostgreSQL-backed storage to cloud storage (e.g. S3, GCS)
 * in the future simply by providing a new implementation, without touching the upload endpoints.
 */
public interface FileStorage {

    /**
     * Stores a single file and associates it with the given document.
     * For backward compatibility with single-file uploads.
     */
    void store(Document document, byte[] fileBytes);

    /**
     * Stores multiple file pages for a single logical document.
     * Each entry contains (fileName, mimeType, fileBytes).
     */
    void storePages(Document document, List<PageUpload> pages);

    /**
     * Retrieves the raw file bytes for a single-file document.
     * Falls back to the first page if Document.fileData is null.
     */
    byte[] retrieve(Document document);

    /**
     * Retrieves all pages for a document, ordered by page number.
     */
    List<DocumentPage> retrievePages(Document document);

    /**
     * Uploads raw file bytes to storage without requiring an existing Document entity.
     * Useful for groups and other non-submission contexts.
     *
     * @param pathPrefix The prefix/folder in storage (e.g., "groups/123/")
     * @param fileName The base file name (e.g., "invoice.pdf")
     * @param contentType The MIME type
     * @param fileBytes The file content
     * @return The unique key/reference under which the file was stored
     */
    String uploadRawFile(String pathPrefix, String fileName, String contentType, byte[] fileBytes);

    /**
     * Simple record for passing upload data without coupling to MultipartFile.
     */
    record PageUpload(String fileName, String mimeType, byte[] fileBytes) {}
}
