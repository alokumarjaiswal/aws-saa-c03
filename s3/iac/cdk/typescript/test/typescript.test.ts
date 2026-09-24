import * as cdk from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { TypescriptStack } from '../lib/typescript-stack';

test('creates the public website and private locked-down buckets', () => {
	const app = new cdk.App();
	const stack = new TypescriptStack(app, 'MyTestStack');
	const template = Template.fromStack(stack);

	template.resourceCountIs('AWS::S3::Bucket', 2);

	template.hasResourceProperties('AWS::S3::Bucket', {
		WebsiteConfiguration: {
			ErrorDocument: 'error.html',
			IndexDocument: 'index.html',
		},
		PublicAccessBlockConfiguration: {
			BlockPublicAcls: true,
			BlockPublicPolicy: false,
			IgnorePublicAcls: true,
			RestrictPublicBuckets: false,
		},
	});

	template.hasResourceProperties('AWS::S3::Bucket', {
		BucketEncryption: {
			ServerSideEncryptionConfiguration: [
				{
					ServerSideEncryptionByDefault: {
						SSEAlgorithm: 'AES256',
					},
				},
			],
		},
		VersioningConfiguration: {
			Status: 'Enabled',
		},
	});

	template.hasOutput('PublicWebsiteBucketName', {});
	template.hasOutput('PublicWebsiteBucketUrl', {});
	template.hasOutput('PrivateLockedBucketName', {});
	template.hasOutput('PrivateLockedBucketArn', {});
});
