package com.example;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.progress.LoggingTransferListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletionException;

public class GetObjects {

    private static final int PREVIEW_LIMIT = 20;
    private static final Scanner SCANNER = new Scanner(System.in);

    private record DownloadItem(String key, Path localPath) {}

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String bucket = args[0];
        String prefix = null;
        List<String> keys = null;
        String destArg = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--prefix" -> prefix = args[++i];
                case "--dest" -> destArg = args[++i];
                case "--keys" -> {
                    keys = new ArrayList<>();
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        keys.add(args[++i]);
                    }
                }
                default -> {
                    System.err.println("Unrecognized argument: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        if (keys != null && prefix != null) {
            System.err.println("--keys and --prefix cannot be used together — choose one.");
            printUsage();
            System.exit(1);
        }

        Path destRoot = destArg != null ? Paths.get(destArg) : Paths.get(bucket);

        try (S3Client syncClient = S3Client.create();
             S3AsyncClient asyncClient = S3AsyncClient.builder().multipartEnabled(true).build();
             S3TransferManager tm = S3TransferManager.builder().s3Client(asyncClient).build()) {

            List<DownloadItem> items = (keys != null && !keys.isEmpty())
                    ? keys.stream().map(k -> new DownloadItem(k, destRoot.resolve(k))).toList()
                    : listByPrefix(syncClient, bucket, prefix == null ? "" : prefix, destRoot);

            if (items.isEmpty()) {
                System.out.println("Nothing to download.");
                return;
            }

            Set<String> skip = resolveOverwriteSkips(items);

            for (DownloadItem item : items) {
                if (skip.contains(item.key())) {
                    System.out.println("Skipped (already exists): " + item.key());
                    continue;
                }
                downloadOne(tm, bucket, item);
            }

        } catch (SdkException e) {
            System.err.println("AWS SDK error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("""
                Usage:
                  GetObjects <bucket>                                whole bucket
                  GetObjects <bucket> --prefix <prefix>               matching prefix
                  GetObjects <bucket> --keys <k1> <k2> ...            specific keys
                  (optional) --dest <local-dir>                       default: ./<bucket>/

                  --keys and --prefix are mutually exclusive.
                """);
    }

    private static List<DownloadItem> listByPrefix(S3Client s3, String bucket, String prefix, Path destRoot) {
        List<DownloadItem> items = new ArrayList<>();
        var pages = s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix));
        for (var page : pages) {
            for (S3Object o : page.contents()) {
                if (o.key().endsWith("/")) {
                    System.out.println("Skipping folder marker object: " + o.key());
                    continue;
                }
                items.add(new DownloadItem(o.key(), destRoot.resolve(o.key())));
            }
        }
        return items;
    }

    private static Set<String> resolveOverwriteSkips(List<DownloadItem> items) {
        List<DownloadItem> conflicts = items.stream()
                .filter(i -> Files.exists(i.localPath()))
                .toList();

        if (conflicts.isEmpty()) {
            return Set.of();
        }

        System.out.println("The following " + conflicts.size() + " local file(s) already exist and would be overwritten:");
        conflicts.stream().limit(PREVIEW_LIMIT).forEach(i -> System.out.println("  " + i.localPath()));
        if (conflicts.size() > PREVIEW_LIMIT) {
            System.out.println("  ... and " + (conflicts.size() - PREVIEW_LIMIT) + " more");
        }
        System.out.print("Overwrite these? [y/N]: ");

        String answer = SCANNER.hasNextLine() ? SCANNER.nextLine().trim().toLowerCase() : "";
        boolean overwrite = answer.equals("y") || answer.equals("yes");

        if (overwrite) {
            return Set.of();
        }
        Set<String> skip = new HashSet<>();
        conflicts.forEach(i -> skip.add(i.key()));
        return skip;
    }

    private static void downloadOne(S3TransferManager tm, String bucket, DownloadItem item) {
        try {
            if (item.localPath().getParent() != null) {
                Files.createDirectories(item.localPath().getParent());
            }

            DownloadFileRequest request = DownloadFileRequest.builder()
                    .getObjectRequest(g -> g.bucket(bucket).key(item.key()))
                    .destination(item.localPath())
                    .addTransferListener(LoggingTransferListener.create())
                    .build();

            CompletedFileDownload result = tm.downloadFile(request).completionFuture().join();
            System.out.println("Downloaded '" + item.key() + "' -> " + item.localPath()
                    + " (" + result.response().contentLength() + " bytes)");

        } catch (IOException e) {
            System.err.println("Could not create local directory for '" + item.key() + "': " + e.getMessage());
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof S3Exception s3e) {
                System.err.println("S3 error downloading '" + item.key() + "' [" + s3e.awsErrorDetails().errorCode() + "]: "
                        + s3e.awsErrorDetails().errorMessage());
            } else {
                System.err.println("Error downloading '" + item.key() + "': " + cause.getMessage());
            }
        }
    }
}