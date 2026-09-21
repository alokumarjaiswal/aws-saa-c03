package com.example;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class DeleteObjects {

    private static final int BATCH_SIZE = 1000; // S3 bulk-delete API limit per request
    private static final int PREVIEW_LIMIT = 20; // don't flood the terminal for huge lists

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        try (S3Client s3 = S3Client.create()) {
            run(s3, args);
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

    private static void printUsage() {
        System.err.println("""
                Usage:
                  DeleteObjects <bucket>                    delete ALL objects
                  DeleteObjects <bucket> --prefix <prefix>   delete objects matching prefix
                  DeleteObjects <bucket> --keys <k1> <k2> ...delete specific keys
                """);
    }

    private static void run(S3Client s3, String[] args) {
        String bucket = args[0];
        List<String> targetKeys;

        if (args.length == 1) {
            targetKeys = listAllKeys(s3, bucket, null);
        } else if ("--prefix".equals(args[1]) && args.length >= 3) {
            targetKeys = listAllKeys(s3, bucket, args[2]);
        } else if ("--keys".equals(args[1]) && args.length >= 3) {
            targetKeys = Arrays.asList(args).subList(2, args.length);
        } else {
            printUsage();
            System.exit(1);
            return;
        }

        if (targetKeys.isEmpty()) {
            System.out.println("Nothing to delete — no matching objects found.");
            return;
        }

        warnIfVersioned(s3, bucket);

        if (!confirm(bucket, targetKeys)) {
            System.out.println("Aborted. No objects were deleted.");
            return;
        }

        int deleted = 0;
        List<S3Error> errors = new ArrayList<>();

        for (List<String> batch : chunk(targetKeys, BATCH_SIZE)) {
            List<ObjectIdentifier> ids = batch.stream()
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .toList();

            DeleteObjectsResponse resp = s3.deleteObjects(r -> r
                    .bucket(bucket)
                    .delete(d -> d.objects(ids).quiet(false)));

            deleted += resp.deleted().size();
            errors.addAll(resp.errors());
        }

        System.out.println("Deleted " + deleted + " object(s) from '" + bucket + "'.");
        if (!errors.isEmpty()) {
            System.out.println(errors.size() + " object(s) failed to delete:");
            for (S3Error e : errors) {
                System.out.println("  " + e.key() + " — " + e.code() + ": " + e.message());
            }
        }
    }

    private static List<String> listAllKeys(S3Client s3, String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        ListObjectsV2Iterable pages = s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix));
        for (var page : pages) {
            page.contents().forEach(o -> keys.add(o.key()));
        }
        return keys;
    }

    private static void warnIfVersioned(S3Client s3, String bucket) {
        try {
            BucketVersioningStatus status = s3.getBucketVersioning(r -> r.bucket(bucket)).status();
            if (status == BucketVersioningStatus.ENABLED || status == BucketVersioningStatus.SUSPENDED) {
                System.out.println("WARNING: versioning is " + status + " on '" + bucket + "'.");
                System.out.println("This delete will only add delete markers / remove the current version.");
                System.out.println("Older versions will remain in the bucket and continue to incur storage cost.\n");
            }
        } catch (S3Exception e) {
            System.out.println("Note: could not check versioning status (" + e.awsErrorDetails().errorCode()
                    + ") — proceeding without that information.\n");
        }
    }

    private static boolean confirm(String bucket, List<String> keys) {
        System.out.println("The following " + keys.size() + " object(s) in '" + bucket + "' will be PERMANENTLY DELETED:");
        keys.stream().limit(PREVIEW_LIMIT).forEach(k -> System.out.println("  " + k));
        if (keys.size() > PREVIEW_LIMIT) {
            System.out.println("  ... and " + (keys.size() - PREVIEW_LIMIT) + " more");
        }
        System.out.print("\nProceed? [y/N]: ");
        try (Scanner scanner = new Scanner(System.in)) {
            String answer = scanner.hasNextLine() ? scanner.nextLine().trim().toLowerCase() : "";
            return answer.equals("y") || answer.equals("yes");
        }
    }

    private static List<List<String>> chunk(List<String> list, int size) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}