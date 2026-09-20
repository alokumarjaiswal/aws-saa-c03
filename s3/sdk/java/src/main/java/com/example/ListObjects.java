package com.example;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ListObjects {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private record ObjectInfo(String key, String contentType, String size, String lastModified) {}

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: mvn -q compile exec:java -Dexec.mainClass=com.example.ListObjects -Dexec.args=\"<bucket-name>\"");
            System.exit(1);
        }

        try (S3Client s3 = S3Client.create()) {
            run(s3, args[0]);
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

    private static void run(S3Client s3, String bucket) {
        ListObjectsV2Response resp = s3.listObjectsV2(r -> r.bucket(bucket).maxKeys(1000));
        List<S3Object> objects = resp.contents();

        if (objects.isEmpty()) {
            System.out.println("Bucket '" + bucket + "' is empty.");
            return;
        }

        if (resp.isTruncated()) {
            System.out.println("Note: bucket has more than " + objects.size()
                    + " objects — showing first page only.\n");
        }

        List<ObjectInfo> rows = objects.stream()
                .map(o -> new ObjectInfo(
                        o.key(),
                        resolveContentType(s3, bucket, o.key()),
                        formatSize(o.size()),
                        FMT.format(o.lastModified())))
                .toList();

        printTable(rows);
    }

    private static String resolveContentType(S3Client s3, String bucket, String key) {
        try {
            HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key(key));
            String ct = head.contentType();
            return (ct == null || ct.isBlank()) ? "-" : ct;
        } catch (S3Exception e) {
            return "unknown (" + e.awsErrorDetails().errorCode() + ")";
        }
    }

    private static String formatSize(Long bytes) {
        if (bytes == null) return "-";
        double b = bytes;
        if (b < 1024) return String.format("%d B", bytes);
        double kib = b / 1024;
        if (kib < 1024) return String.format("%.2f KiB", kib);
        double mib = kib / 1024;
        if (mib < 1024) return String.format("%.2f MiB", mib);
        double gib = mib / 1024;
        return String.format("%.2f GiB", gib);
    }

    private static void printTable(List<ObjectInfo> rows) {
        int keyW = Math.max(3, rows.stream().mapToInt(r -> r.key().length()).max().orElse(0));
        int typeW = Math.max(12, rows.stream().mapToInt(r -> r.contentType().length()).max().orElse(0));
        int sizeW = Math.max(10, rows.stream().mapToInt(r -> r.size().length()).max().orElse(0));
        int modW = 20; // fixed: ISO instant format is a constant length

        String fmt = "%-" + keyW + "s  %-" + typeW + "s  %-" + sizeW + "s  %-" + modW + "s%n";
        String sep = "-".repeat(keyW + typeW + sizeW + modW + 6);

        System.out.printf(fmt, "Key", "Content Type", "Size", "Last Modified (UTC)");
        System.out.println(sep);
        for (ObjectInfo r : rows) {
            System.out.printf(fmt, r.key(), r.contentType(), r.size(), r.lastModified());
        }
    }
}