""" A simple Pulumi program to create two S3 buckets: one public, one private. """

import json
import pulumi
import pulumi_aws as aws

# ── Bucket A: fully public static website ──────────────────────────────
public_bucket = aws.s3.Bucket("public-site-bucket")

public_access_block = aws.s3.BucketPublicAccessBlock("public-site-bucket-pab",
    bucket=public_bucket.id,
    block_public_acls=False,
    block_public_policy=False,
    ignore_public_acls=False,
    restrict_public_buckets=False)

website_config = aws.s3.BucketWebsiteConfiguration("public-site-bucket-website",
    bucket=public_bucket.id,
    index_document={"suffix": "index.html"},
    error_document={"key": "error.html"})

public_bucket_policy = aws.s3.BucketPolicy("public-site-bucket-policy",
    bucket=public_bucket.id,
    policy=public_bucket.arn.apply(lambda arn: json.dumps({
        "Version": "2012-10-17",
        "Statement": [{
            "Sid": "PublicReadGetObject",
            "Effect": "Allow",
            "Principal": "*",
            "Action": "s3:GetObject",
            "Resource": f"{arn}/*",
        }],
    })),
    opts=pulumi.ResourceOptions(depends_on=[public_access_block]))


# ── Bucket B: fully private, encrypted, versioned ───────────────────────
private_bucket = aws.s3.Bucket("private-data-bucket")

private_versioning = aws.s3.BucketVersioning("private-data-bucket-versioning",
    bucket=private_bucket.id,
    versioning_configuration={"status": "Enabled"})

private_encryption = aws.s3.BucketServerSideEncryptionConfiguration(
    "private-data-bucket-encryption",
    bucket=private_bucket.id,
    rules=[{
        "apply_server_side_encryption_by_default": {"sse_algorithm": "AES256"},
    }])

private_access_block = aws.s3.BucketPublicAccessBlock("private-data-bucket-pab",
    bucket=private_bucket.id,
    block_public_acls=True,
    block_public_policy=True,
    ignore_public_acls=True,
    restrict_public_buckets=True)

pulumi.export("public_bucket_website_url", website_config.website_endpoint)
pulumi.export("private_bucket_name", private_bucket.id)
