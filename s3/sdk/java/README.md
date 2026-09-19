# S3 scripts with the AWS SDK for Java

Small Java programs that inspect and manage Amazon S3 resources using the [AWS SDK for Java 2.x](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html). Built to run in a GitHub Codespace, so there is nothing to install on your own machine.

## What's here

| Path | Purpose |
|---|---|
| `pom.xml` | Maven build file. Declares the AWS SDK S3 module, console logging, and the Maven exec plugin. |
| `src/main/java/com/example/` | Java entry points. |
| `ListRecentBuckets.java` | Lists the five most recently created buckets, including creation time and region. |

## Requirements

The repo's `devcontainer.json` installs these automatically when the Codespace is created:

- Java 25 (Microsoft OpenJDK)
- Maven 3.9
- AWS CLI

You also need AWS credentials. They are supplied as **Codespaces secrets** named `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, which the SDK reads from environment variables. The Codespace sets `AWS_REGION` and `AWS_DEFAULT_REGION` to `ap-south-1`.

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
mvn -q compile exec:java -Dexec.mainClass=com.example.ListRecentBuckets
```

Example output:

```
Bucket Name       Created (UTC)          Region
----------------  --------------------  -----------
my-bucket-name    2026-09-18T12:34:56Z  ap-south-1
```

The command sorts buckets by creation time, limits the result to five, and calls S3 to resolve each bucket's region. The first run downloads the SDK and takes a little longer.

## Add a new script

1. Create `src/main/java/com/example/<ClassName>.java` containing `package com.example;` and a public class with a `main` method. The class name must match the file name.
2. Run it:
   ```bash
   mvn -q compile exec:java -Dexec.mainClass=com.example.<ClassName>
   ```

## How it works

- Maven downloads the SDK from Maven Central. Versions are managed by the AWS SDK BOM (`2.54.14`), set once via `aws.java.sdk.version` in `pom.xml`.
- The `exec-maven-plugin` runs the selected main class, and `slf4j-simple` provides console logging.
- The SDK finds credentials and the default region in the environment. The Codespace configuration supplies `ap-south-1` through `AWS_REGION` and `AWS_DEFAULT_REGION`.
- To use another AWS service, add its module to `pom.xml` (for example, `dynamodb`).
- `target/` is build output and is git-ignored.