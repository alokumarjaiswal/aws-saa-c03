# S3 Scripts

Standalone Java scripts using AWS SDK for Java v2 for common S3 operations. Each script is a single class with its own `main` method, run individually via Maven — not a combined CLI app.

This README covers usage and behavior. For the reasoning behind specific design choices and trade-offs, see [`DECISIONS.md`](./DECISIONS.md).

## Setup

- Java 25, Maven (see `pom.xml` for the AWS SDK BOM version in use)
- AWS credentials and region resolved via the SDK's default provider chain — typically `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_REGION` environment variables in this project's devcontainer
- `slf4j-simple` is included for AWS SDK internal logging (see `DECISIONS.md` → ADR-027). Default threshold is INFO, so you likely won't see much without further configuration.

## Running a script

```
mvn -q compile exec:java -Dexec.mainClass=com.example.<ClassName> -Dexec.args="<args>"
```

---

## `ListRecentBuckets`

Lists the 5 most recently created buckets in the account, with creation time and region.

### Usage

```
mvn -q compile exec:java -Dexec.mainClass=com.example.ListRecentBuckets
```

### Behavior

- Fetches all buckets via a single unpaginated `ListBuckets` call, sorts by creation date, and takes the top 5.
- Resolves each bucket's region via a separate `GetBucketLocation` call per bucket — see ADR-008.
- Normalizes `us-east-1`'s empty-string location response to the literal string `us-east-1` — see ADR-009.
- Renders output as a plain, dependency-free table using `String.format`.

### Limitations

- `ListBuckets` is called without pagination. Fine for typical accounts; an account with a very large number of buckets (AWS's per-account quota is 10,000) would need a paginator loop instead.
- One `GetBucketLocation` call per bucket means N+1 API calls total — noticeably slower on accounts with many buckets, though only 5 buckets are ever resolved here regardless of total bucket count.
- Does **not** use `crossRegionAccessEnabled` — see "Open Questions" in `DECISIONS.md`. A bucket outside the client's configured region could behave unexpectedly here.

### Permissions required

`s3:ListAllMyBuckets`, `s3:GetBucketLocation`.

### Related decisions

ADR-007, ADR-008, ADR-009, ADR-010, ADR-027.

---

## `LatestBucket`

Like `ListRecentBuckets`, but returns just the single most recently created bucket.

### Usage

```
mvn -q compile exec:java -Dexec.mainClass=com.example.LatestBucket
```

### Behavior

- Uses a single-pass `Stream.max()` instead of sorting the full list and taking the first element — see ADR-007.
- Same region-resolution and `us-east-1` handling as `ListRecentBuckets` (ADR-008, ADR-009), including the same duplicated helper method (ADR-010).
- Plain key–value output, not a table — a table isn't justified for a single result row.

### Limitations

Same as `ListRecentBuckets`: no pagination on `ListBuckets`, no `crossRegionAccessEnabled`.

### Permissions required

`s3:ListAllMyBuckets`, `s3:GetBucketLocation`.

### Related decisions

ADR-007, ADR-008, ADR-009, ADR-010, ADR-027.

---

## `ListObjects`

Lists objects in a bucket (key, content type, size, last modified).

### Usage

```
mvn -q compile exec:java -Dexec.mainClass=com.example.ListObjects -Dexec.args="<bucket>"
```

### Behavior

- Fetches only the **first page** (up to 1000 objects) via `ListObjectsV2` — a deliberate choice for a read-only browsing script. Contrast with `DeleteObjects`, which pages through everything (ADR-002, ADR-011).
- Prints a note if the bucket has more objects than shown.
- Fetches content type per object via a separate `HeadObject` call, since `ListObjectsV2` doesn't return it — see ADR-012.
- Formats size in B / KiB / MiB / GiB.
- A `HeadObject` failure on one object (e.g. `AccessDenied`) doesn't stop the rest of the listing — shows `unknown (<code>)` for that row instead.

### Limitations

- N+1 API calls (one `ListObjectsV2` + one `HeadObject` per object) — slow and request-heavy on buckets with hundreds of objects.
- First-page-only — doesn't reflect the whole bucket if it has more than 1000 objects.

### Permissions required

`s3:ListBucket`, plus `s3:GetObject` or `s3:HeadObject` for the content-type lookups.

### Related decisions

ADR-011, ADR-012.

---

## `DeleteObjects`

Deletes objects from a bucket — either all objects, objects matching a prefix, or an explicit list of keys.

### Usage

```
mvn -q compile exec:java -Dexec.mainClass=com.example.DeleteObjects -Dexec.args="<bucket>"
mvn -q compile exec:java -Dexec.mainClass=com.example.DeleteObjects -Dexec.args="<bucket> --prefix <prefix>"
mvn -q compile exec:java -Dexec.mainClass=com.example.DeleteObjects -Dexec.args="<bucket> --keys <key1> <key2> ..."
```

### Behavior

- Pages through **every** matching object before doing anything — not just the first 1000. See ADR-002.
- Prints the full target list (up to a preview limit) and requires an interactive `y`/`N` confirmation before deleting anything. See ADR-001.
- Warns if the bucket has versioning enabled. See ADR-003.
- Deletes in batches of up to 1000 keys (S3's API limit per request) and reports per-object failures without aborting the remaining batches.

### Limitations

- Only adds delete markers on versioned buckets — does not purge old versions (ADR-003).
- No non-interactive/CI mode — no `--yes` flag. See "Open Questions."
- Does not use `crossRegionAccessEnabled`. See "Open Questions."

### Permissions required

`s3:ListBucket`, `s3:DeleteObject`, optionally `s3:GetBucketVersioningStatus`.

### Related decisions

ADR-001, ADR-002, ADR-003, ADR-006.

---

## `DeleteBuckets`

Deletes one or more S3 buckets, emptying each one first if needed (including all object versions and delete markers).

### Usage

```
mvn -q compile exec:java -Dexec.mainClass=com.example.DeleteBuckets -Dexec.args="<bucket1> [bucket2] ..."
```

### Behavior

- Checks existence per bucket first; a missing bucket is reported and skipped, not fatal.
- Lists every object version and delete marker via `ListObjectVersions` (works uniformly for versioned and non-versioned buckets) and asks for confirmation before emptying.
- Asks a **second, separate** confirmation before deleting the (now-empty) bucket. See ADR-005.
- Processes multiple buckets sequentially; one bucket's failure doesn't stop the rest. See ADR-016.
- Uses `crossRegionAccessEnabled` to find buckets outside the client's configured region. See ADR-004.

### Limitations

- Two confirmations per bucket, by design (ADR-005) — not a single combined prompt.
- Same `y`/`N` prompt style as `DeleteObjects`, not a stronger "type the exact name" safeguard.
- Cross-region access broadens what the script can reach — a mistyped bucket name could resolve somewhere unexpected.

### Permissions required

`s3:ListBucketVersions`, `s3:DeleteObject`, `s3:DeleteBucket`.

### Related decisions

ADR-001, ADR-004, ADR-005, ADR-006, ADR-016.

---

## `CreateBuckets`

Creates one or more buckets in a specified or resolved region.

### Usage

```
mvn -q compile exec:java -Dexec.mainClass=com.example.CreateBuckets -Dexec.args="<bucket1> [bucket2] ... [--region <region>]"
```

### Behavior

- Resolves the target region from `--region` if given, otherwise from the SDK's default provider chain — there is no separate "ultimate fallback" beyond that chain. See ADR-015.
- Omits the `LocationConstraint` entirely when creating in `us-east-1` (required by the S3 API) and sets it explicitly for every other region. See ADR-009.
- Distinguishes "you already own this bucket" (`BucketAlreadyOwnedByYouException`, and note this is *silently idempotent* in `us-east-1` specifically but throws elsewhere) from "someone else owns this name" (`BucketAlreadyExistsException`). See ADR-014.
- Multiple buckets processed sequentially; one failure doesn't stop the rest. See ADR-016.
- Does not validate bucket-naming rules client-side — relies on S3's own `InvalidBucketName` error. See ADR-013.

### Limitations

- `.crossRegionAccessEnabled(true)` is set on this script's client but is currently vestigial — see the cleanup note under "Open Questions" in `DECISIONS.md`.

### Permissions required

`s3:CreateBucket`.

### Related decisions

ADR-009, ADR-013, ADR-014, ADR-015, ADR-016.

---

## `PutObjects`

Uploads a single file, multiple files, and/or a directory (recursively) to a bucket, in any combination in one run.

### Usage

```
mvn -q compile exec:java -Dexec.mainClass=com.example.PutObjects -Dexec.args="<bucket> <path1> [path2] ... [--prefix <prefix>]"
```

### Behavior

- Each path argument is inspected independently — files and directories can be freely mixed in one invocation.
- Directory uploads include the source directory's own name as part of the S3 key, rather than flattening — see ADR-017.
- All uploads go through `S3TransferManager`, which automatically decides between a single `PutObject` and a full multipart upload based on file size — see ADR-019. (This replaced an earlier manual 5 GiB size-check-and-skip approach — see ADR-018, superseded.)
- Uses the pure-Java `multipartEnabled(true)` async client rather than the CRT-based one, to avoid a native-binary dependency. See ADR-019.
- Content type is best-effort via `Files.probeContentType` — a `null` result is left as S3's default rather than guessed from the file extension. See ADR-020.

### Limitations

- Always overwrites an existing object at the same key, silently — no conditional-put guard. See ADR-021.
- `--prefix` applies globally to every path in one run; no per-path prefix support.
- No client-side validation beyond existence checks.

### Permissions required

`s3:PutObject`.

### Related decisions

ADR-016, ADR-017, ADR-018 (superseded), ADR-019, ADR-020, ADR-021.

---

## `GetObjects`

Downloads a single key, multiple keys, or an entire prefix/bucket from S3 to local disk.

### Usage

```
mvn -q compile exec:java -Dexec.mainClass=com.example.GetObjects -Dexec.args="<bucket>"
mvn -q compile exec:java -Dexec.mainClass=com.example.GetObjects -Dexec.args="<bucket> --prefix <prefix>"
mvn -q compile exec:java -Dexec.mainClass=com.example.GetObjects -Dexec.args="<bucket> --keys <key1> <key2> ... [--dest <local-dir>]"
```

`--keys` and `--prefix` are mutually exclusive — passing both is a hard error. (An earlier version silently prioritized `--keys` without warning; see ADR-026.)

### Behavior

- Defaults to the whole bucket if neither `--keys` nor `--prefix` is given.
- Defaults the download destination to `./<bucket-name>/` rather than the current directory — same collision-avoidance reasoning as `PutObjects`' directory-prefix decision. See ADR-022.
- Skips S3 console "folder marker" objects (zero-byte keys ending in `/`) rather than attempting to download them as files. See ADR-023.
- Before downloading anything, checks which local files would be overwritten, shows the **full list once**, and asks a single confirmation. If declined, only the conflicting files are skipped — everything else still downloads. See ADR-024.
- Uses `S3TransferManager` (same pure-Java, non-CRT setup as `PutObjects`) for the actual downloads, handling large files automatically.
- Uses a manual list-then-download loop rather than `TransferManager`'s native `downloadDirectory()`, specifically to preserve the ability to inspect overwrite conflicts before writing anything. See ADR-025.

### Limitations

- One overwrite confirmation covers the whole run, not per-file — by design (ADR-024), but means you can't selectively approve some overwrites and reject others in a single invocation.

### Permissions required

`s3:ListBucket` (for whole-bucket/prefix mode), `s3:GetObject`.

### Related decisions

ADR-001, ADR-002, ADR-016, ADR-019, ADR-022, ADR-023, ADR-024, ADR-025, ADR-026.