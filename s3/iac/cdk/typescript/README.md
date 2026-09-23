# S3 IaC - AWS CDK (TypeScript)

Two opposite S3 bucket configurations as a CDK scaffolding reference:

- A public static website bucket.
- A private, locked-down bucket.

## Prerequisites

### Devcontainer

The devcontainer must include a Node.js feature because the CDK CLI is npm-based regardless of the infrastructure language:

```json
"features": {
	"ghcr.io/devcontainers/features/node:2": {},
}
```

### AWS CDK
```json
"postCreateCommand": "npm install -g aws-cdk"
```

## One-time setup

```bash
cdk bootstrap aws://$(aws sts get-caller-identity --query Account --output text)/$AWS_REGION
npx aws-cdk init app --language=typescript
```

Run `cdk bootstrap` once per account and region. The CDK project must be initialized in an empty directory.

## Project structure

```text
bin/typescript.ts          # entry point - instantiates the stack
lib/typescript-stack.ts    # resource definitions (the two buckets)
cdk.json                   # tells the CDK CLI how to run the app
```

## Commands

| Command | Effect |
| --- | --- |
| `cdk synth` | Compile TypeScript to a CloudFormation template. No AWS calls. Run this first, always. |
| `cdk diff` | Compare against the deployed stack. Safe and read-only. |
| `cdk deploy` | Apply changes. Prompts for approval on IAM or security-sensitive diffs. |
| `cdk destroy` | Tear down the stack. |

Additional local development commands:

```bash
npm run build  # type-check the project
npm run watch  # watch for changes and type-check
npm run test   # run Jest unit tests
```

## Teardown notes

- `cdk destroy` deletes the public bucket fully through its auto-delete Lambda.
- The private bucket is not deleted. `RemovalPolicy.RETAIN` leaves it in your account; delete it manually if needed:

	```bash
	aws s3api list-object-versions --bucket <name> # check for versions first
	aws s3 rb s3://<name>
	```

- The `CDKToolkit` bootstrap stack is separate and untouched by `cdk destroy`. It is reused across future CDK projects in that account and region.

## Finding bucket names and outputs after deployment

```bash
aws cloudformation describe-stacks \
	--stack-name TypescriptStack \
	--query "Stacks[0].Outputs"
```
