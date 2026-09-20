package com.example;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.List;

public class CreateBuckets {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: CreateBuckets <bucket1> [bucket2] ... [--region <region>]");
            System.exit(1);
        }

        List<String> bucketNames = new ArrayList<>();
        String regionArg = null;

        for (int i = 0; i < args.length; i++) {
            if ("--region".equals(args[i]) && i + 1 < args.length) {
                regionArg = args[++i];
            } else {
                bucketNames.add(args[i]);
            }
        }

        if (bucketNames.isEmpty()) {
            System.err.println("No bucket names provided.");
            System.exit(1);
        }

        Region region = resolveRegion(regionArg);
        System.out.println("Using region: " + region.id()
                + (regionArg == null ? " (resolved from environment/config)" : " (explicitly requested)"));

        try (S3Client s3 = S3Client.builder()
                .region(region)
                .crossRegionAccessEnabled(true) // lets the pre-check below handle a same-named bucket you own elsewhere
                .build()) {

            for (String bucket : bucketNames) {
                System.out.println("=== " + bucket + " ===");
                try {
                    createOne(s3, bucket, region);
                } catch (S3Exception e) {
                    System.err.println("S3 error [" + e.awsErrorDetails().errorCode() + "]: "
                            + e.awsErrorDetails().errorMessage());
                }
            }
        } catch (SdkException e) {
            System.err.println("AWS SDK error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Region resolveRegion(String regionArg) {
        if (regionArg != null) {
            return Region.of(regionArg);
        }
        try {
            return new DefaultAwsRegionProviderChain().getRegion();
        } catch (SdkException e) {
            System.err.println("No region was specified and none could be resolved from the environment "
                    + "(AWS_REGION, ~/.aws/config, etc). Pass --region explicitly.");
            System.exit(1);
            return null; // unreachable
        }
    }

    private static void createOne(S3Client s3, String bucket, Region region) {
        try {
            s3.createBucket(r -> {
                r.bucket(bucket);
                // us-east-1 is the one region where LocationConstraint must be omitted entirely —
                // setting it explicitly to "us-east-1" is an API error, not a no-op.
                if (region != Region.US_EAST_1) {
                    r.createBucketConfiguration(c -> c.locationConstraint(region.id()));
                }
            });
            System.out.println("Created '" + bucket + "' in " + region.id() + ".");
        } catch (BucketAlreadyOwnedByYouException e) {
            System.out.println("You already own '" + bucket + "' — nothing to do.");
        } catch (BucketAlreadyExistsException e) {
            System.out.println("'" + bucket + "' is already taken by another AWS account (bucket names are globally unique).");
        }
    }
}