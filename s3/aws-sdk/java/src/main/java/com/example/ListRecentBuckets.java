package com.example;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public class ListRecentBuckets {

    private static final int TOP_N = 5;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private record BucketInfo(String name, String created, String region) {}

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
        List<Bucket> recent = s3.listBuckets().buckets().stream()
                .sorted(Comparator.comparing(Bucket::creationDate).reversed())
                .limit(TOP_N)
                .toList();

        if (recent.isEmpty()) {
            System.out.println("No buckets found in this account.");
            return;
        }

        List<BucketInfo> rows = recent.stream()
                .map(b -> new BucketInfo(b.name(), FMT.format(b.creationDate()), resolveRegion(s3, b.name())))
                .toList();

        printTable(rows);
    }

    private static String resolveRegion(S3Client s3, String bucketName) {
        try {
            String loc = s3.getBucketLocation(r -> r.bucket(bucketName)).locationConstraintAsString();
            // GetBucketLocation returns "" for us-east-1 — a documented AWS API quirk, not a null-check bug.
            return (loc == null || loc.isBlank()) ? "us-east-1" : loc;
        } catch (S3Exception e) {
            return "unknown (" + e.awsErrorDetails().errorCode() + ")";
        }
    }

    private static void printTable(List<BucketInfo> rows) {
        int nameW = Math.max(11, rows.stream().mapToInt(r -> r.name().length()).max().orElse(0));
        int createdW = 20; // fixed: ISO instant format is a constant length
        int regionW = Math.max(6, rows.stream().mapToInt(r -> r.region().length()).max().orElse(0));

        String fmt = "%-" + nameW + "s  %-" + createdW + "s  %-" + regionW + "s%n";
        String sep = "-".repeat(nameW + createdW + regionW + 4);

        System.out.printf(fmt, "Bucket Name", "Created (UTC)", "Region");
        System.out.println(sep);
        for (BucketInfo r : rows) {
            System.out.printf(fmt, r.name(), r.created(), r.region());
        }
    }
}