import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import { Construct } from 'constructs';

export class TypescriptStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // ── Opposite #1: PUBLIC static website bucket ──────────────
    const publicWebsiteBucket = new s3.Bucket(this, 'PublicWebsiteBucket', {
      websiteIndexDocument: 'index.html',
      websiteErrorDocument: 'error.html',
      publicReadAccess: true,
      blockPublicAccess: new s3.BlockPublicAccess({
        blockPublicAcls: true,
        ignorePublicAcls: true,
        blockPublicPolicy: false,
        restrictPublicBuckets: false,
      }),
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });

    // ── Opposite #2: PRIVATE, locked-down bucket ────────────────
    const privateLockedBucket = new s3.Bucket(this, 'PrivateLockedBucket', {
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // ── Outputs: so bucket identity doesn't require grepping `aws s3 ls` ──
    new cdk.CfnOutput(this, 'PublicWebsiteBucketName', {
      value: publicWebsiteBucket.bucketName,
      description: 'Name of the public static website bucket',
    });

    new cdk.CfnOutput(this, 'PublicWebsiteBucketUrl', {
      value: publicWebsiteBucket.bucketWebsiteUrl,
      description: 'Website endpoint URL for the public bucket',
    });

    new cdk.CfnOutput(this, 'PrivateLockedBucketName', {
      value: privateLockedBucket.bucketName,
      description: 'Name of the private locked-down bucket',
    });

    new cdk.CfnOutput(this, 'PrivateLockedBucketArn', {
      value: privateLockedBucket.bucketArn,
      description: 'ARN of the private locked-down bucket',
    });
  }
}