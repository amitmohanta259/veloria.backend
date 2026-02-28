package com.app.master.service.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AwsService extends AppService {

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    private final S3Client s3Client;

    private final S3Presigner s3Presigner;

    private String uploadToS3AndGetKey(String bucketName, String avtarKey, byte[] decodedBytes) {
        if (org.apache.commons.lang3.StringUtils.isNotBlank(avtarKey)) {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(avtarKey)
                    .build();
            PutObjectResponse response = s3Client.putObject(request, RequestBody.fromBytes(decodedBytes));
            System.out.println("Response from s3: " + response);

            if (response != null && response.sdkHttpResponse().isSuccessful()) {
                System.out.println("avtar key:" + avtarKey);
                return avtarKey;
            }
        }
        return null;
    }

    public String uploadDocumentMultipart(MultipartFile file, String pathOfFile) throws IOException {

        byte[] bytes = file.getBytes();

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(pathOfFile)
                .build();
        PutObjectResponse response = s3Client.putObject(request, RequestBody.fromBytes(bytes));
        if (response != null && response.sdkHttpResponse().isSuccessful()) {
            return pathOfFile;
        }
        throw new IOException("Failed to upload image to S3");
    }

    public String generatePresignedUrl(String filePath) {
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(filePath)
                    .responseContentDisposition("inline")
                    .responseContentType("application/pdf")
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate pre-signed URL", e);
        }
    }

    public String getViewablePreSignedUrl(String key) throws IOException {
        try {
            //Determining content type based on file extension
            String contentType = determineContentType(key);

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .responseContentDisposition("inline")
                    .responseContentType(contentType)
                    .responseCacheControl("no-store")
                    .build();

            GetObjectPresignRequest getObjectPresignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(30))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedGetObjectRequest = s3Presigner.presignGetObject(getObjectPresignRequest);
            return presignedGetObjectRequest.url().toString();
        } catch (S3Exception e) {
            throw new IOException("Error retrieving object from S3: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("An error occurred: " + e.getMessage(), e);
        }
    }

    //Utility method for determining content type
    private String determineContentType(String key) {
        Map<String, String> mimeTypes = Map.of(
                ".pdf", "application/pdf",
                ".png", "image/png",
                ".jpg", "image/jpeg",
                ".jpeg", "image/jpeg"
        );

        return mimeTypes.entrySet().stream()
                .filter(entry -> key.toLowerCase().endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("application/octet-stream"); //Fallback MIME type
    }

    public String deleteKey(String key) {
        if (Objects.nonNull(key)) {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            DeleteObjectResponse response = s3Client.deleteObject(deleteObjectRequest);
            if (response != null && response.sdkHttpResponse().isSuccessful()) {
                return key;
            }
        }
        return null;
    }

    public String getProductImagePath(String subCategory, String fileName) {
        return subCategory.toLowerCase() + "/product/images/" + UUID.randomUUID() + "_" + fileName;
    }

    public String getPreSignedUrlForBase64(String key) throws IOException {
        if (StringUtils.isBlank(key)) return null;
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            GetObjectPresignRequest getObjectPresignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofDays(1))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedGetObjectRequest = s3Presigner.presignGetObject(getObjectPresignRequest);
            return presignedGetObjectRequest.url().toString();
        } catch (S3Exception e) {
            throw new IOException("Error retrieving object from S3: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("An error occurred: " + e.getMessage(), e);
        }
    }

    public String uploadAiNotes(MultipartFile file, String pathOfFile) throws IOException {
        byte[] bytes = file.getBytes();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(pathOfFile)
                .build();
        PutObjectResponse response = s3Client.putObject(request, RequestBody.fromBytes(bytes));
        if (response != null && response.sdkHttpResponse().isSuccessful()) {
            return pathOfFile;
        }
        throw new IOException("Failed to upload image to S3");
    }

    public static String saveAudio(UUID patientId, String random, String fileName) {
        return "patient/encounter/audio/" + patientId + "/" + random + "/" + UUID.randomUUID() + "/" + fileName;
    }

    public String generatePreSignedUrlTranscription(String fileKey) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket("aws-transcribe-us-east-1-prod")
                .key(fileKey)
                .build();

        // Create a PreSignRequest with expiration time
        GetObjectPresignRequest preSignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(getObjectRequest)
                .build();

        // Generate the pre-signed URL
        return s3Presigner.presignGetObject(preSignRequest).url().toString();
    }

}