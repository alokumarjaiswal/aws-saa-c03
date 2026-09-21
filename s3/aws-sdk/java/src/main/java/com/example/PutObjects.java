package com.example;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;
import software.amazon.awssdk.transfer.s3.progress.LoggingTransferListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

public class PutObjects {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: PutObjects <bucket> <path1> [path2] ... [--prefix <prefix>]");
            System.exit(1);
        }

        String bucket = args[0];
        String prefix = "";
        List<Path> paths = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            if ("--prefix".equals(args[i]) && i + 1 < args.length) {
                prefix = args[++i];
                if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix += "/";
            } else {
                paths.add(Paths.get(args[i]));
            }
        }

        // multipartEnabled(true) is what lets a plain Java S3AsyncClient back a TransferManager
        // without needing the native aws-crt dependency.
        try (S3AsyncClient asyncClient = S3AsyncClient.builder()
                .multipartEnabled(true)
                .build();
             S3TransferManager tm = S3TransferManager.builder()
                     .s3Client(asyncClient)
                     .build()) {

            for (Path p : paths) {
                if (!Files.exists(p)) {
                    System.err.println("Skipping '" + p + "' — path does not exist.");
                    continue;
                }
                if (Files.isDirectory(p)) {
                    uploadDirectory(tm, bucket, p, prefix);
                } else {
                    uploadFile(tm, bucket, p, prefix + p.getFileName());
                }
            }
        } catch (SdkException e) {
            System.err.println("AWS SDK error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void uploadDirectory(S3TransferManager tm, String bucket, Path dir, String globalPrefix) {
        String dirName = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String relative = dir.relativize(file).toString().replace('\\', '/');
                String key = globalPrefix + dirName + "/" + relative;
                uploadFile(tm, bucket, file, key);
            });
        } catch (IOException e) {
            System.err.println("Error walking directory '" + dir + "': " + e.getMessage());
        }
    }

    private static void uploadFile(S3TransferManager tm, String bucket, Path file, String key) {
        String contentType;
        try {
            contentType = Files.probeContentType(file);
        } catch (IOException ignored) {
            contentType = null;
        }
        final String finalContentType = contentType;

        UploadFileRequest request = UploadFileRequest.builder()
                .source(file)
                .putObjectRequest(po -> {
                    po.bucket(bucket).key(key);
                    if (finalContentType != null) po.contentType(finalContentType);
                })
                .addTransferListener(LoggingTransferListener.create())
                .build();

        try {
            // .join() blocks, keeping this script's synchronous, sequential style like the rest of your scripts.
            CompletedFileUpload result = tm.uploadFile(request).completionFuture().join();
            System.out.println("Uploaded '" + file + "' -> s3://" + bucket + "/" + key
                    + " (" + result.response().eTag() + ")");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof S3Exception s3e) {
                System.err.println("S3 error uploading '" + file + "' [" + s3e.awsErrorDetails().errorCode() + "]: "
                        + s3e.awsErrorDetails().errorMessage());
            } else {
                System.err.println("Error uploading '" + file + "': " + cause.getMessage());
            }
        }
    }
}