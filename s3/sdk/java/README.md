# S3 scripts with the AWS SDK for Java

Small Java programs that manage Amazon S3 buckets and objects using the [AWS SDK for Java 2.x](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html). Built to run in a GitHub Codespace, so there is nothing to install on your own machine.

## What's here

| Path | Purpose |
|---|---|
| `pom.xml` | Maven build file. Declares the AWS SDK (S3 module) as a dependency. |
| `src/main/java/` | One class per script. |
| `ListingBuckets.java` | Lists the S3 buckets in your AWS account. |

## Requirements

The repo's `devcontainer.json` installs these automatically when the Codespace is created:

- Java 25 (Microsoft OpenJDK)
- Maven 3.9
- AWS CLI

You also need AWS credentials. They are supplied as **Codespaces secrets** named `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, which the SDK reads from environment variables.

## One-time setup

1. Add the two secrets under GitHub → Settings → Codespaces → Secrets, and give them access to this repository.
2. Create (or rebuild) the Codespace.
3. Check that AWS accepts your credentials:
   ```bash
   aws sts get-caller-identity
   ```
   You should see your account ID and ARN.

## Run a script

From this folder:

```bash
mvn -q compile exec:java -Dexec.mainClass=ListingBuckets
```

Example output:

```
Found 1 bucket(s):
 - my-bucket-name
```

The first run downloads the SDK and takes a little longer. `SLF4J` warnings about logging are harmless.

## Add a new script

1. Create `src/main/java/<ClassName>.java` containing `public class <ClassName>` with a `main` method. The class name must match the file name.
2. Run it:
   ```bash
   mvn -q compile exec:java -Dexec.mainClass=<ClassName>
   ```

## How it works

- Maven downloads the SDK from Maven Central. Versions are managed by the AWS SDK BOM, set once via `aws.java.sdk.version` in `pom.xml`.
- The SDK finds credentials in the environment. The region (`ap-south-1`) is set in code with `Region.AP_SOUTH_1`.
- To use another AWS service, add its module to `pom.xml` (for example, `dynamodb`).
- `target/` is build output and is git-ignored.