package test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import java.net.URI;

public class S3Test {
    public static void main(String[] args) {
        String accessKey = "cdcfee3478f731363cf7f1a5ad4a0d7f";
        String secretKey = "516f1c742746c00b5592719922a27d7639b6862e4a57cbd91b5d2ac947f068db";
        String endpoint = "https://5e0e63c0ccca604674f07cff180b4e0e.r2.cloudflarestorage.com";
        
        try {
            S3Client clientAutoPath = S3Client.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
            System.out.println("Testing Auto + PathStyle:");
            clientAutoPath.listObjectsV2(ListObjectsV2Request.builder().bucket("prism-jhonson-finance").build());
            System.out.println("Auto + PathStyle SUCCESS");
        } catch(Exception e) { System.out.println("Auto + PathStyle ERROR: " + e.getMessage()); }
        
        try {
            S3Client clientUsEastPath = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
            System.out.println("Testing US_EAST_1 + PathStyle:");
            clientUsEastPath.listObjectsV2(ListObjectsV2Request.builder().bucket("prism-jhonson-finance").build());
            System.out.println("US_EAST_1 + PathStyle SUCCESS");
        } catch(Exception e) { System.out.println("US_EAST_1 + PathStyle ERROR: " + e.getMessage()); }
        
        try {
            S3Client clientAutoVirtual = S3Client.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
            System.out.println("Testing Auto + VirtualHosted:");
            clientAutoVirtual.listObjectsV2(ListObjectsV2Request.builder().bucket("prism-jhonson-finance").build());
            System.out.println("Auto + VirtualHosted SUCCESS");
        } catch(Exception e) { System.out.println("Auto + VirtualHosted ERROR: " + e.getMessage()); }
        
        try {
            S3Client clientUsEastVirtual = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
            System.out.println("Testing US_EAST_1 + VirtualHosted:");
            clientUsEastVirtual.listObjectsV2(ListObjectsV2Request.builder().bucket("prism-jhonson-finance").build());
            System.out.println("US_EAST_1 + VirtualHosted SUCCESS");
        } catch(Exception e) { System.out.println("US_EAST_1 + VirtualHosted ERROR: " + e.getMessage()); }
    }
}
