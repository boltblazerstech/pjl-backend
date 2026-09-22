package com.pjl.core.storage;

import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.DocumentPage;
import com.pjl.bills.repository.DocumentPageRepository;
import com.pjl.bills.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Primary
public class R2FileStorage implements FileStorage {

    private final S3Client s3Client;
    private final String bucketName;
    private final DocumentRepository documentRepository;
    private final DocumentPageRepository documentPageRepository;

    public R2FileStorage(
            @Value("${R2_BUCKET_NAME:prism-jhonson-finance}") String bucketName,
            @Value("${R2_ACCESS_KEY_ID:eab9a1e196222cf3895ba6ce9125a245}") String accessKey,
            @Value("${R2_SECRET_ACCESS_KEY:3bd86bba78b502a803df7293e3d7a9955629a3a4043398b5c551ebfcfe5ccbec}") String secretKey,
            @Value("${R2_ENDPOINT:https://5e0e63c0ccca604674f07cff180b4e0e.r2.cloudflarestorage.com}") String endpoint,
            DocumentRepository documentRepository,
            DocumentPageRepository documentPageRepository) {
        this.bucketName = bucketName;
        this.documentRepository = documentRepository;
        this.documentPageRepository = documentPageRepository;
        
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

        this.s3Client = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Override
    @Transactional
    public void store(Document document, byte[] fileBytes) {
        String key = String.format("submissions/%d/%s-%d.pdf", 
                document.getSubmission().getId(), 
                document.getDocType(), 
                System.currentTimeMillis());
                
        log.info("Uploading file to R2 bucket: {}, key: {}", bucketName, key);
        
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("application/pdf")
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(fileBytes));
        
        document.setFileRef(key);
        // Leave file_data null for new R2 uploads
        documentRepository.save(document);
    }

    @Override
    @Transactional
    public void storePages(Document document, List<PageUpload> pages) {
        log.info("Storing {} pages in R2 for Document ID {}", pages.size(), document.getId());
        for (int i = 0; i < pages.size(); i++) {
            PageUpload page = pages.get(i);
            
            String key = String.format("submissions/%d/%s-page-%d-%s",
                    document.getSubmission().getId(),
                    document.getDocType(),
                    i + 1,
                    UUID.randomUUID().toString());
                    
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(page.mimeType() != null ? page.mimeType() : "application/pdf")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(page.fileBytes()));
            
            DocumentPage dp = new DocumentPage();
            dp.setDocument(document);
            dp.setPageOrder(i);
            dp.setFileName(key); // Storing the R2 key in the fileName column
            dp.setMimeType(page.mimeType());
            documentPageRepository.save(dp);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] retrieve(Document document) {
        if (document.getFileRef() == null || !document.getFileRef().contains("/")) {
            log.debug("No valid R2 key in fileRef, trying legacy Postgres storage for Document ID {}", document.getId());
            byte[] data = documentRepository.findById(document.getId())
                    .map(Document::getFileData)
                    .orElse(null);
            if (data != null && data.length > 0) return data;
            
            List<DocumentPage> pages = documentPageRepository.findByDocumentOrderByPageOrderAsc(document);
            if (!pages.isEmpty()) {
                return pages.getFirst().getFileData();
            }
            return null;
        }

        log.debug("Retrieving file from R2 for Document ID {}, key: {}", document.getId(), document.getFileRef());
        
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(document.getFileRef())
                .build();
                
        return s3Client.getObjectAsBytes(getObjectRequest).asByteArray();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentPage> retrievePages(Document document) {
        List<DocumentPage> pages = documentPageRepository.findByDocumentOrderByPageOrderAsc(document);
        for (DocumentPage page : pages) {
            if (page.getFileData() == null || page.getFileData().length == 0) {
                String key = page.getFileName();
                if (key != null && key.contains("/")) {
                    log.debug("Hydrating page from R2, key: {}", key);
                    GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build();
                    byte[] data = s3Client.getObjectAsBytes(getObjectRequest).asByteArray();
                    page.setFileData(data);
                }
            }
        }
        return pages;
    }

    @Override
    public String uploadRawFile(String pathPrefix, String fileName, String contentType, byte[] fileBytes) {
        // Ensure pathPrefix ends with /
        String prefix = pathPrefix != null ? pathPrefix : "";
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        
        String key = prefix + System.currentTimeMillis() + "-" + fileName;
        log.info("Uploading raw file to R2 bucket: {}, key: {}", bucketName, key);
        
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(fileBytes));
        
        return key;
    }

    @Override
    public void deleteFile(String key) {
        if (key == null || key.isBlank()) return;
        log.info("Deleting file from R2 bucket: {}, key: {}", bucketName, key);
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build());
    }
}
