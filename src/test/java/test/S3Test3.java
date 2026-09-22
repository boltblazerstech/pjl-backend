package test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import java.net.URI;

public class S3Test3 {
    public static void main(String[] args) {
        String accessKey = "cdcfee3478f731363cf7f1a5ad4a0d7f";
        String secretKey = "516f1c742746c00b5592719922a27d7639b6862e4a57cbd91b5d2ac947f068db";
        String endpoint = "https://5e0e63c0ccca604674f07cff180b4e0e.r2.cloudflarestorage.com";
        
        S3Client client = S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
        
        try {
            System.out.println("Testing bucket: prism-jhonson-finance");
            client.listObjectsV2(ListObjectsV2Request.builder().bucket("prism-jhonson-finance").build());
            System.out.println("SUCCESS prism-jhonson-finance");
        } catch(Exception e) { System.out.println("ERROR: " + e.getMessage()); }
        
        try {
            System.out.println("Testing bucket: prism-johnson-finance");
            client.listObjectsV2(ListObjectsV2Request.builder().bucket("prism-johnson-finance").build());
            System.out.println("SUCCESS prism-johnson-finance");
        } catch(Exception e) { System.out.println("ERROR: " + e.getMessage()); }
    }
}
