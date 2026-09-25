# S3 Bucket Configurations with Pulumi (Python)

A minimal Pulumi (Python) project provisioning two S3 buckets with **opposite security postures**, to illustrate how Pulumi's declarative model handles contrasting configurations side by side.

## What this deploys

| Resource | Bucket | Purpose |
|---|---|---|
| `aws.s3.Bucket` | `public-site-bucket` | Public static website bucket |
| `aws.s3.BucketPublicAccessBlock` | `public-site-bucket-pab` | All 4 public-access gates **opened** |
| `aws.s3.BucketWebsiteConfiguration` | `public-site-bucket-website` | Website hosting config (`index.html` / `error.html`) |
| `aws.s3.BucketPolicy` | `public-site-bucket-policy` | Public `s3:GetObject` policy |
| `aws.s3.Bucket` | `private-data-bucket` | Private, locked-down bucket |
| `aws.s3.BucketPublicAccessBlock` | `private-data-bucket-pab` | All 4 public-access gates **closed** |
| `aws.s3.BucketVersioning` | `private-data-bucket-versioning` | Versioning enabled |
| `aws.s3.BucketServerSideEncryptionConfiguration` | `private-data-bucket-encryption` | AES256 encryption at rest |

Everything lives in a single `__main__.py` — at this scale (two resources) there's no need for a `templates/` or `components/` folder; that becomes worth introducing once you have several buckets sharing a repeated shape.

## Prerequisites

- Pulumi CLI installed (`pulumi version` to check)
- Python 3 + a project-local `venv` (created automatically by `pulumi new`)
- AWS credentials available as environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` — Pulumi follows the standard AWS credential chain, same as the AWS CLI)
- Local state backend (`pulumi login --local`) — state is stored as an encrypted file in this project, not in Pulumi Cloud. This means:
  - No external account needed.
  - You'll be prompted for a **passphrase** on every `pulumi` command (set once, remember it — losing it makes the state file unreadable). Set `PULUMI_CONFIG_PASSPHRASE` as an env var to skip the prompt.
  - State lives only on this machine/Codespace — if the Codespace is deleted before `pulumi destroy`, the AWS resources keep running but Pulumi loses track of them.

## Project layout

```
├── __main__.py          # Both bucket definitions live here
├── Pulumi.yaml           # Project metadata (name, runtime: python)
├── Pulumi.dev.yaml        # Stack config (region, etc.) for the "dev" stack
├── requirements.txt        # pulumi, pulumi_aws
└── venv/                    # Project-local virtualenv (gitignore this)
```

## Commands: preview → deploy → destroy

```bash
pulumi preview     # Show planned changes without touching AWS
pulumi up          # Apply changes (prompts for confirmation)
pulumi destroy     # Tear everything down when you're done
```

**Always run `preview` before `up`**, and actually read the plan — this is what makes Pulumi IaC rather than an imperative script. In particular, watch for `+/-` (replace) on a bucket resource, since that means delete-then-recreate, not an in-place update.

## Outputs

```bash
pulumi stack output public_bucket_website_url   # e.g. bucket.s3-website.<region>.amazonaws.com
pulumi stack output private_bucket_name          # the actual generated bucket ID
```

## Useful commands beyond the basics

```bash
pulumi config get aws:region           # check the deployed region
pulumi config set aws:region <region>   # change region for this stack (before first `up`)
pulumi stack ls                          # list all stacks in this project
pulumi refresh                           # reconcile state with what's actually in AWS (detect drift)
pulumi stack export                      # dump raw state as JSON (debugging)
pulumi logs                              # view provider logs from the last operation
```

## Notes on resource naming

`pulumi_aws` v7 renamed several S3 resources — `BucketV2` → `Bucket`, `BucketWebsiteConfigurationV2` → `BucketWebsiteConfiguration`, `BucketVersioningV2` → `BucketVersioning`, `BucketServerSideEncryptionConfigurationV2` → `BucketServerSideEncryptionConfiguration`. This project uses the current (non-`V2`) names. If `pulumi preview` ever shows a deprecation warning, check the [AWS provider changelog](https://www.pulumi.com/registry/packages/aws/) before assuming the old name is still correct.