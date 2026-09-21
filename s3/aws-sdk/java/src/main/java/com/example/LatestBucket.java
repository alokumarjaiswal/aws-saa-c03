package com.example;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;

public class LatestBucket {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    public static void main(String[] args) {
        try (S3Client s3 = S3Client.create()) {
            run(s3);
        } catch (S3Exception e) {
            System.err.println("S3 error [" + e.awsErrorDetails().errorCode() + "]: "
                    + e.awsErrorDetails().errorMessage());
            System.exit(1);
        } catch (SdkException e) {
            System.err.println("AWS SDK error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(S3Client s3) {
        Optional<Bucket> latest = s3.listBuckets().buckets().stream()
                .max(Comparator.comparing(Bucket::creationDate));

        if (latest.isEmpty()) {
            System.out.println("No buckets found in this account.");
            return;
        }

        Bucket b = latest.get();
        String region = resolveRegion(s3, b.name());

        System.out.println("Latest bucket:");
        System.out.println("  Name:    " + b.name());
        System.out.println("  Created: " + FMT.format(b.creationDate()));
        System.out.println("  Region:  " + region);
    }

    private static String resolveRegion(S3Client s3, String bucketName) {
        try {
            String loc = s3.getBucketLocation(r -> r.bucket(bucketName)).locationConstraintAsString();
            return (loc == null || loc.isBlank()) ? "us-east-1" : loc;
        } catch (S3Exception e) {
            return "unknown (" + e.awsErrorDetails().errorCode() + ")";
        }
    }
}