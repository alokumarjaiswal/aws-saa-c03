package com.example;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class DeleteBuckets {

    private static final int BATCH_SIZE = 1000; // S3 bulk-delete API limit per request
    private static final int PREVIEW_LIMIT = 20;
    private static final Scanner SCANNER = new Scanner(System.in);

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: DeleteBuckets <bucket1> [bucket2] [bucket3] ...");
            System.exit(1);
        }

        try (S3Client s3 = S3Client.builder()
        .crossRegionAccessEnabled(true)
        .build()) {
            for (String bucket : args) {
                System.out.println("=== " + bucket + " ===");
                try {
                    processBucket(s3, bucket);
                } catch (S3Exception e) {
                    System.err.println("S3 error [" + e.awsErrorDetails().errorCode() + "]: "
                            + e.awsErrorDetails().errorMessage());
                }
                System.out.println();
            }
        } catch (SdkException e) {
            System.err.println("AWS SDK error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void processBucket(S3Client s3, String bucket) {
        if (!bucketExists(s3, bucket)) {
            System.out.println("Bucket '" + bucket + "' does not exist or is not accessible — skipping.");
            return;
        }

        List<ObjectIdentifier> toDelete = collectAllVersionsAndMarkers(s3, bucket);

        if (!toDelete.isEmpty()) {
            if (!confirmEmpty(bucket, toDelete)) {
                System.out.println("Skipped emptying '" + bucket + "' — bucket left as-is, not deleted.");
                return;
            }
            emptyBucket(s3, bucket, toDelete);
        } else {
            System.out.println("Bucket '" + bucket + "' is already empty.");
        }

        if (!confirmDelete(bucket)) {
            System.out.println("Skipped deleting '" + bucket + "'.");
            return;
        }

        s3.deleteBucket(r -> r.bucket(bucket));
        System.out.println("Bucket '" + bucket + "' deleted.");
    }

    private static boolean bucketExists(S3Client s3, String bucket) {
        try {
            s3.headBucket(r -> r.bucket(bucket));
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e; // e.g. 403 Forbidden — a real problem, don't silently treat as "doesn't exist"
        }
    }

    private static List<ObjectIdentifier> collectAllVersionsAndMarkers(S3Client s3, String bucket) {
        List<ObjectIdentifier> ids = new ArrayList<>();
        ListObjectVersionsIterable pages = s3.listObjectVersionsPaginator(r -> r.bucket(bucket));

        for (var page : pages) {
            for (ObjectVersion v : page.versions()) {
                ids.add(ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build());
            }
            for (DeleteMarkerEntry m : page.deleteMarkers()) {
                ids.add(ObjectIdentifier.builder().key(m.key()).versionId(m.versionId()).build());
            }
        }
        return ids;
    }

    private static void emptyBucket(S3Client s3, String bucket, List<ObjectIdentifier> ids) {
        int deleted = 0;
        List<S3Error> errors = new ArrayList<>();

        for (List<ObjectIdentifier> batch : chunk(ids, BATCH_SIZE)) {
            DeleteObjectsResponse resp = s3.deleteObjects(r -> r
                    .bucket(bucket)
                    .delete(d -> d.objects(batch).quiet(false)));
            deleted += resp.deleted().size();
            errors.addAll(resp.errors());
        }

        System.out.println("Emptied " + deleted + " version(s)/marker(s) from '" + bucket + "'.");
        if (!errors.isEmpty()) {
            System.out.println(errors.size() + " item(s) failed to delete:");
            for (S3Error e : errors) {
                System.out.println("  " + e.key() + " (" + e.versionId() + ") — " + e.code() + ": " + e.message());
            }
        }
    }

    private static boolean confirmEmpty(String bucket, List<ObjectIdentifier> ids) {
        System.out.println("Bucket '" + bucket + "' contains " + ids.size()
                + " object version(s)/delete marker(s) that must be removed before it can be deleted:");
        ids.stream().limit(PREVIEW_LIMIT)
                .forEach(id -> System.out.println("  " + id.key() + " (" + id.versionId() + ")"));
        if (ids.size() > PREVIEW_LIMIT) {
            System.out.println("  ... and " + (ids.size() - PREVIEW_LIMIT) + " more");
        }
        System.out.print("Empty this bucket now? [y/N]: ");
        return readYes();
    }

    private static boolean confirmDelete(String bucket) {
        System.out.print("Delete bucket '" + bucket + "' now? This cannot be undone. [y/N]: ");
        return readYes();
    }

    private static boolean readYes() {
        String answer = SCANNER.hasNextLine() ? SCANNER.nextLine().trim().toLowerCase() : "";
        return answer.equals("y") || answer.equals("yes");
    }

    private static List<List<ObjectIdentifier>> chunk(List<ObjectIdentifier> list, int size) {
        List<List<ObjectIdentifier>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}