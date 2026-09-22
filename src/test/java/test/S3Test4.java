package test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import java.net.URI;

public class S3Test4 {
    public static void main(String[] args) {
        String accessKey = "eab9a1e196222cf3895ba6ce9125a245";
        String secretKey = "3bd86bba78b502a803df7293e3d7a9955629a3a4043398b5c551ebfcfe5ccbec";
        String endpoint = "https://5e0e63c0ccca604674f07cff180b4e0e.r2.cloudflarestorage.com";
        
        try {
            S3Client client = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
            
            System.out.println("Objects in prism-jhonson-finance:");
            for (S3Object obj : client.listObjectsV2(ListObjectsV2Request.builder().bucket("prism-jhonson-finance").build()).contents()) {
                System.out.println(" - " + obj.key() + " (" + obj.size() + " bytes)");
            }
        } catch(Exception e) { System.out.println("ERROR: " + e.getMessage()); }
    }
}
