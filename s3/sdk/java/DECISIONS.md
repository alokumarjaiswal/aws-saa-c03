# Design Decisions & Trade-offs

A consolidated log of notable design decisions across the S3 scripts project, recorded as lightweight ADRs (Architecture Decision Records).

**Format note:** these are intentionally leaner than a full ADR (no Complexity/Cost/Scalability/Team-familiarity scoring table) — that level of ceremony fits infrastructure-scale choices better than a set of single-file scripts. Structure kept: Context → Decision → Alternatives Considered → Consequences.

**Current scope:** all 8 scripts (`ListRecentBuckets`, `LatestBucket`, `ListObjects`, `DeleteObjects`, `DeleteBuckets`, `CreateBuckets`, `PutObjects`, `GetObjects`). Numbering is chronological by when each decision was made, not grouped by script — cross-references in the README point back here. **Don't renumber existing entries** when adding new ones; append instead.

---

## ADR-001: Confirmation mechanism for destructive operations

**Status:** Accepted
**Applies to:** `DeleteObjects`, `DeleteBuckets`

**Context:** Both scripts perform irreversible deletions. No confirmation risks accidental data loss; a heavier mechanism (e.g. typing an exact name) adds friction for routine use.

**Decision:** An interactive `y`/`N` prompt that first lists exactly what will be affected (bucket name and/or keys, up to a preview limit), then asks to proceed.

**Alternatives considered:**
- *No confirmation* — rejected: too risky for an irreversible action.
- *Dry-run flag + separate `--confirm` flag* — rejected for interactive use, since the prompt already shows the same preview before acting.
- *Type-the-exact-name confirmation* — considered for `DeleteBuckets` given its higher severity, but consistency with `DeleteObjects`' prompt style was chosen instead.

**Consequences:**
- Neither script can run unattended (e.g. in CI) as currently written.
- Consistent confirmation UX across the project's destructive scripts.
- Later reused in `GetObjects` for overwrite confirmation (ADR-024), extending this pattern beyond deletion.

---

## ADR-002: Full pagination before acting, even though the read-only listing script doesn't

**Status:** Accepted
**Applies to:** `DeleteObjects`

**Context:** The read-only `ListObjects` script deliberately shows only the first page (up to 1000 objects) for speed and simplicity when browsing. `DeleteObjects` has the same underlying `ListObjectsV2` API available to it.

**Decision:** `DeleteObjects` pages through the *entire* bucket or prefix match before presenting anything to the user, rather than reusing `ListObjects`' first-page-only behavior.

**Alternatives considered:**
- *Match `ListObjects`' first-page-only behavior* — rejected: silently acting on only a subset of "all objects," while the user believes the full set was targeted, would leave the bucket in a state that contradicts stated intent.

**Consequences:** Slower time-to-confirmation-prompt on very large buckets. Same "list everything before confirming" philosophy reused in `GetObjects` (ADR-024).

---

## ADR-003: Versioned buckets — delete markers vs. permanent purge

**Status:** Accepted
**Applies to:** `DeleteObjects`

**Context:** On a versioned S3 bucket, a normal delete only adds a delete marker — it does not remove the underlying version data, which continues to incur storage cost.

**Decision:** `DeleteObjects` checks the bucket's versioning status and prints a warning when it is `ENABLED` or `SUSPENDED`, but does not attempt to purge old versions itself.

**Alternatives considered:**
- *Automatically purge all versions as part of "delete objects"* — rejected as out of scope: permanent version purge is a materially more dangerous, distinct operation.

**Consequences:** Permanent version purge remains an unaddressed gap in the project (see "Open Questions").

---

## ADR-004: Cross-region bucket access

**Status:** Accepted
**Applies to:** `DeleteBuckets`

**Context:** `HeadBucket` (and other S3 calls) return HTTP `301 Moved Permanently` with no usable error body when a bucket exists in a different region than the client's configured region — HEAD responses cannot carry a body per the HTTP spec, so the SDK's `awsErrorDetails().errorCode()` comes back `null`. This surfaced in practice as a confusing `S3 error [null]: Moved Permanently`.

**Decision:** Build the `S3Client` with `.crossRegionAccessEnabled(true)`, letting the SDK auto-detect a bucket's real region and transparently redirect. Available since AWS SDK for Java v2 `2.20.111`; confirmed compatible with this project's pinned SDK version (`2.54.14`, via the BOM).

**Alternatives considered:**
- *Manually catch the 301, re-resolve the region via `GetBucketLocation`, and reissue with a region-specific client* — rejected: functionally equivalent to what the SDK now does natively, more code to maintain.

**Consequences:** The script can now discover and act on buckets in *any* region — a deliberate broadening of scope. This was **not** retrofitted into every other script that touches existing buckets (see "Open Questions").

---

## ADR-005: Two separate confirmations for empty-then-delete

**Status:** Accepted
**Applies to:** `DeleteBuckets`

**Context:** S3 requires a bucket to be completely empty (zero object versions, zero delete markers) before `DeleteBucket` will succeed. Emptying and deleting are two distinct irreversible actions.

**Decision:** Prompt for confirmation twice per bucket — once before emptying, once before deleting the now-empty bucket.

**Alternatives considered:**
- *Single combined "empty and delete?" prompt* — rejected: would hide that emptying alone is already irreversible, even if the user then declines to delete the bucket.

**Consequences:** More prompts per bucket than a single-confirmation design. Judged acceptable given severity.

---

## ADR-006: Shared error-handling and client-lifecycle pattern

**Status:** Accepted
**Applies to:** All scripts

**Context:** S3 operations fail in several distinguishable ways — S3-specific service errors with an AWS error code, broader SDK-level failures (no network, no credentials) that aren't S3-specific, and non-AWS failures (bad arguments, local file I/O). A single generic catch block loses information useful for diagnosis.

**Decision:**
- `S3Client` (and `S3AsyncClient` / `S3TransferManager` where used) are always constructed via try-with-resources.
- Exceptions caught in three tiers: `S3Exception` (has an AWS error code), then `SdkException` (broader SDK-level failures like `SdkClientException`), then generic `Exception`.

**Alternatives considered:**
- *Single generic `Exception` catch* — rejected: loses the AWS error code and misses the `SdkException` tier's broader coverage.

**Consequences:** Slightly more verbose in exchange for more actionable error messages. Note: when `TransferManager`'s async calls are `.join()`ed synchronously (`PutObjects`, `GetObjects`), exceptions arrive wrapped in `CompletionException` and must be unwrapped via `getCause()` before this same three-tier logic applies — see ADR-019.

---

## ADR-007: Selecting recent bucket(s) — sort+limit vs. single-pass max

**Status:** Accepted
**Applies to:** `ListRecentBuckets`, `LatestBucket`

**Context:** `ListRecentBuckets` needs the top 5 most recent buckets; `LatestBucket` needs only the single most recent.

**Decision:** `ListRecentBuckets` sorts the full bucket list (descending by creation date) and takes the first 5, since it genuinely needs an ordered top-N. `LatestBucket` uses `Stream.max(Comparator.comparing(...))` instead — a single pass that never sorts the whole list.

**Alternatives considered:**
- *Use sort+limit(1) in `LatestBucket` too, for consistency with its sibling script* — rejected: sorting the entire list just to discard everything but the first element does unnecessary work when `.max()` expresses the same intent more directly and more efficiently.

**Consequences:** The two sibling scripts intentionally use different idioms for what looks like a similar problem — this is a deliberate efficiency/clarity choice, not an inconsistency to "fix."

---

## ADR-008: Bucket region lookup — GetBucketLocation vs. HeadBucket header

**Status:** Accepted
**Applies to:** `ListRecentBuckets`, `LatestBucket`

**Context:** Neither `ListBuckets` nor a bucket's basic metadata includes its region directly. Two ways to get it: `GetBucketLocation` (SDK parses the response into a typed field) or `HeadBucket` (region is present only in a raw HTTP response header, `x-amz-bucket-region`).

**Decision:** Use `GetBucketLocation`.

**Alternatives considered:**
- *`HeadBucket` header inspection* — rejected: requires digging into `sdkHttpResponse().headers()` manually for no real benefit over a typed field, and `GetBucketLocation` requires its own specific permission (`s3:GetBucketLocation`) that's easier to reason about than "some information happens to be in a header of a different call."

**Consequences:** One extra API call per bucket whose region is needed (N+1 total across both scripts, though capped at 5 buckets for `ListRecentBuckets` and 1 for `LatestBucket`). Requires the `s3:GetBucketLocation` permission specifically, separate from `s3:ListAllMyBuckets`.

---

## ADR-009: us-east-1 special-casing across the S3 API surface

**Status:** Accepted
**Applies to:** `ListRecentBuckets`, `LatestBucket` (read side), `CreateBuckets` (write side)

**Context:** `us-east-1` is S3's original/legacy region and is special-cased throughout the API, in two different directions relevant to this project:
- **Read side:** `GetBucketLocation` returns an **empty string** for a bucket in `us-east-1`, not the literal string `"us-east-1"` — long-standing, documented AWS API behavior.
- **Write side:** `CreateBucket` requires the `LocationConstraint` to be **omitted entirely** when creating in `us-east-1` — explicitly passing `"us-east-1"` throws `InvalidLocationConstraint`. Every other region requires the constraint to be set.

**Decision:** Normalize the empty-string case to `"us-east-1"` when displaying bucket region (read side). Conditionally omit `.createBucketConfiguration(...)` only when the target region is `us-east-1` (write side).

**Alternatives considered:** None meaningful — both behaviors are hard API requirements, not stylistic choices. The only "alternative" would be not handling the case, which would produce either a misleading blank region display or a hard `InvalidLocationConstraint` failure.

**Consequences:** Two separate pieces of `us-east-1`-specific conditional logic exist in the codebase, in different scripts, for what is ultimately the same underlying historical quirk. Worth remembering if a future script also touches bucket regions.

---

## ADR-010: Accepted duplication of the region-resolution helper

**Status:** Accepted (with revisit trigger)
**Applies to:** `ListRecentBuckets`, `LatestBucket`

**Context:** Both scripts need an identical `resolveRegion(s3, bucketName)` helper (calls `GetBucketLocation`, normalizes the `us-east-1` empty-string case, catches `S3Exception`).

**Decision:** Leave the method copy-pasted in both files rather than extracting a shared utility class.

**Alternatives considered:**
- *Extract to a shared `S3Utils` class now* — rejected as premature at just two call sites.

**Consequences:** Explicit revisit trigger: if a third script needs the same region-resolution logic, extract it then. As of this documentation pass, no third script has needed it yet.

---

## ADR-011: First-page-only object listing

**Status:** Accepted
**Applies to:** `ListObjects`

**Context:** `ListObjectsV2` truncates at 1000 objects per page. `ListObjects` is a read-only browsing tool, unlike `DeleteObjects` which needs completeness for correctness (ADR-002).

**Decision:** Show only the first page, with a note if the bucket has more.

**Alternatives considered:**
- *Full pagination, matching `DeleteObjects`* — rejected: for browsing, "fast and simple" was explicitly preferred over completeness, and the risk profile of a read-only listing is very different from a destructive operation.

**Consequences:** Doesn't reflect the whole bucket on large buckets. Explicit contrast with ADR-002's reasoning for the destructive counterpart.

---

## ADR-012: Fetching content type despite N+1 cost

**Status:** Accepted
**Applies to:** `ListObjects`

**Context:** `ListObjectsV2` does not return content type in its response — only `HeadObject` does, per object.

**Decision:** Call `HeadObject` for every listed object to retrieve content type, as explicitly requested.

**Alternatives considered:**
- *Guess content type from file extension* — rejected: not authoritative: S3 doesn't guarantee an object's actual stored content type matches its key's extension.
- *Drop content type from the output* — rejected: explicitly requested.

**Consequences:** Up to 1000 extra API calls on a large bucket listing — noticeably slower and more request-heavy than a plain listing. A `HeadObject` failure on one object doesn't abort the rest.

---

## ADR-013: Client-side input validation generally omitted

**Status:** Accepted
**Applies to:** `CreateBuckets` (bucket naming rules)

**Context:** S3 bucket names have well-defined rules (lowercase, 3–63 characters, no underscores, etc.), and S3 already validates and rejects invalid names with a clear `InvalidBucketName` error.

**Decision:** Don't duplicate this validation client-side; let the existing `S3Exception` handling surface S3's own error.

**Alternatives considered:**
- *Fail-fast client-side validation before any network call* — rejected: would duplicate a check the server already performs correctly, for marginal UX benefit (failing slightly faster, at the cost of more code to keep in sync with S3's rules if they ever change).

**Consequences:** Applicable more broadly as a project-wide default: where S3 already returns a clear, typed error for bad input, this project generally doesn't duplicate that validation client-side.

---

## ADR-014: Distinguishing bucket-already-exists exceptions

**Status:** Accepted
**Applies to:** `CreateBuckets`

**Context:** Bucket names are globally unique across all AWS accounts. Two different exceptions can occur when a name is already taken: `BucketAlreadyOwnedByYouException` (you already own it) and `BucketAlreadyExistsException` (someone else owns it). Additionally, re-creating a bucket you already own is **silently idempotent** in `us-east-1` but throws `BucketAlreadyOwnedByYouException` in every other region — a genuine, well-documented AWS behavior asymmetry, not a bug.

**Decision:** Catch both exceptions separately and print a distinct, accurate message for each, rather than lumping them into the generic `S3Exception` handler.

**Alternatives considered:**
- *Generic `S3Exception` handling only* — rejected: would produce a technically-correct-but-unhelpful error code dump instead of a clear "you already own this" or "someone else has this name" message.

**Consequences:** More exception types to handle explicitly, in exchange for clearer feedback. The region-dependent idempotency difference is not otherwise surfaced to the user — worth remembering if debugging inconsistent behavior across regions.

---

## ADR-015: Bucket-creation region resolution

**Status:** Accepted
**Applies to:** `CreateBuckets`

**Context:** The user wanted region either explicitly specified or defaulted from "what's configured in the environment, or the SDK's fallback." There is no separate "ultimate SDK default region" distinct from `DefaultAwsRegionProviderChain` (system property → `AWS_REGION` env var → `~/.aws/config` → EC2 metadata) — if none of those resolve, the SDK throws rather than silently picking something like `us-east-1`.

**Decision:** Resolve the region explicitly in application code (via `--region` flag or `DefaultAwsRegionProviderChain` directly), rather than letting `S3Client.create()` resolve it invisibly, because the code needs to *know* the resolved value to decide whether to omit the `LocationConstraint` (ADR-009).

**Alternatives considered:**
- *Let `S3Client.create()` handle region resolution invisibly* — rejected: the `us-east-1` omission logic requires knowing the resolved region value in application code, which an invisible resolution wouldn't expose.

**Consequences:** One line of extra region-resolution code, in exchange for correct `us-east-1` handling.

---

## ADR-016: Sequential multi-target processing, continuing past per-item failure

**Status:** Accepted
**Applies to:** `DeleteBuckets`, `CreateBuckets`, `PutObjects`, `GetObjects`

**Context:** All four scripts can act on multiple targets (buckets, files, or keys) in a single invocation. A failure on one target (e.g. a typo'd bucket name, a missing local file) has no logical bearing on whether the other targets are valid.

**Decision:** Process targets sequentially in a loop; catch and report failures per-item; continue to the next item rather than aborting the whole run.

**Alternatives considered:**
- *Abort the entire run on the first failure* — rejected: one bad argument shouldn't block otherwise-valid work in the same invocation.
- *Parallelize processing* — not implemented; sequential was simpler and safer for a first version. Noted as a reasonable future iteration if performance on many targets becomes a real concern.

**Consequences:** A single invocation can end with a mix of successes and failures, each reported individually — the user must read the full output rather than relying on a single pass/fail exit code to mean "everything succeeded."

---

## ADR-017: Directory-upload key-prefix convention

**Status:** Accepted
**Applies to:** `PutObjects`

**Context:** When uploading a local directory recursively, the resulting S3 keys need some relationship to the directory's contents.

**Decision:** Include the source directory's own base name as part of the S3 key (e.g. local `photos/cat.jpg` → `s3://bucket/photos/cat.jpg`), rather than flattening to just the relative path inside the directory.

**Alternatives considered:**
- *Flatten to relative-path-only (no directory name prefix)* — rejected: passing two directories with overlapping filenames (e.g. `docs/report.txt` and `archive/report.txt`) would silently collide and overwrite each other in S3 without the directory name acting as a namespace.

**Consequences:** Slightly deeper key paths in S3 than a flattened approach. Same underlying collision-avoidance reasoning reused for `GetObjects`' default destination directory (ADR-022).

---

## ADR-018: Manual 5 GiB single-PutObject size check — SUPERSEDED

**Status:** Superseded by ADR-019
**Applies to:** `PutObjects` (original version)

**Context:** A single `PutObject` request is capped at 5 GiB by the S3 API itself (not an SDK-imposed limit) — attempting to upload a larger file in one request fails server-side with `EntityTooLarge`.

**Original decision:** Check file size client-side before uploading; skip with a clear message if over 5 GiB, rather than letting the request fail confusingly server-side.

**Why superseded:** Once `S3TransferManager` was introduced (ADR-019), it handles files of any size automatically — deciding internally whether to issue a single `PutObject` or a full multipart upload — making the manual size check and skip-logic unnecessary. The check was removed entirely rather than kept as a redundant guard.

---

## ADR-019: Unifying uploads/downloads via S3TransferManager, pure-Java over CRT

**Status:** Accepted
**Applies to:** `PutObjects`, `GetObjects`

**Context:** `S3TransferManager` provides a higher-level API that automatically chooses between a single request and multipart transfer based on file size, with built-in parallelism and progress tracking. It can run on either (a) AWS's native CRT-based client, requiring a separate `aws-crt` dependency with platform-specific native binaries, or (b) a pure-Java `S3AsyncClient` with `.multipartEnabled(true)`, requiring only the `s3-transfer-manager` Maven artifact (already covered by the existing SDK BOM) and no native dependency.

**Decision:** Use the pure-Java, non-CRT flavor for both `PutObjects` and `GetObjects`.

**Alternatives considered:**
- *CRT-based client* — rejected: adds a native-binary dependency, which is one more thing that can go wrong or be incompatible in a devcontainer environment, for a throughput benefit not needed at this project's scale.
- *Keep raw `PutObject`/`GetObject` calls and add manual multipart logic only for large files (hybrid dispatch)* — rejected in favor of unifying everything through `TransferManager`, for a single code path instead of two to maintain. (This was an explicit trade-off; the hybrid approach remains a reasonable alternative if `TransferManager`'s async setup ever proves to be more overhead than it's worth for small, frequent transfers.)

**Consequences:**
- Removed the need for the manual 5 GiB check entirely (ADR-018, superseded).
- Exceptions from `TransferManager` calls arrive wrapped in `CompletionException` (since `.join()` is called on an async future to keep the scripts' synchronous style) and must be unwrapped via `getCause()` before the project's normal three-tier exception handling (ADR-006) can apply.
- Added `LoggingTransferListener` for progress visibility on large transfers — a small addition beyond what was originally requested, easy to remove if noisy.

---

## ADR-020: Content-type detection is best-effort only

**Status:** Accepted
**Applies to:** `PutObjects`

**Context:** `Files.probeContentType()` is known to be unreliable on minimal Linux installs, since it depends on OS-level MIME-type registries that a bare devcontainer image may not have fully populated.

**Decision:** Accept a `null` result and let S3 default to `binary/octet-stream` rather than attempting to guess content type from the file extension.

**Alternatives considered:**
- *Guess from file extension when `probeContentType` returns null* — rejected: a guessed content type could be silently wrong in a way that's harder to notice than an honest generic fallback, especially since it would only kick in inconsistently (exactly when the unreliable detection already failed).

**Consequences:** Some uploaded objects may end up with `binary/octet-stream` instead of their true content type, particularly for less common file types on this OS setup. This is usually fine for storage but can matter if objects are later served directly over HTTP (e.g. a browser won't render a `.mp4` inline correctly without the right content type).

---

## ADR-021: No conditional-put / overwrite guard

**Status:** Accepted
**Applies to:** `PutObjects`

**Context:** Both raw `PutObject` and `TransferManager`'s upload path overwrite an existing object at the same key by default, with no warning.

**Decision:** Leave this default behavior as-is; do not add a conditional-put check (e.g. checking for existing object first, or using conditional request headers) before uploading.

**Alternatives considered:** Not deeply explored — this was accepted as S3's standard behavior rather than treated as a design choice requiring an alternative. Flagged here specifically so it's documented rather than silently assumed.

**Consequences:** Re-running `PutObjects` on the same source files will silently replace the corresponding S3 objects. No safeguard exists against this; users must be aware.

---

## ADR-022: Default download destination

**Status:** Accepted
**Applies to:** `GetObjects`

**Context:** With no `--dest` specified, downloaded files need somewhere to land locally.

**Decision:** Default to `./<bucket-name>/` (created if needed), not directly into the current working directory.

**Alternatives considered:**
- *Download directly into the current directory* — rejected: risks silently colliding with unrelated files already present in the user's working directory, especially on a whole-bucket download with many files.

**Consequences:** Same collision-avoidance philosophy as the directory-upload prefixing decision (ADR-017), applied to the download side.

---

## ADR-023: Skipping S3 console folder-marker objects

**Status:** Accepted
**Applies to:** `GetObjects`

**Context:** The S3 console UI creates a real, zero-byte object with a key ending in `/` (e.g. `assets/`) to visually fake a folder, since S3 has no true directory concept. Attempting to download such an object as a file would mean writing to a nonsensical trailing-slash filename.

**Decision:** Detect keys ending in `/` during listing, skip them, and report them separately rather than attempting to download them.

**Alternatives considered:** None seriously considered — attempting to "download" a folder marker as a file isn't a meaningful operation.

**Consequences:** Folder marker objects are silently excluded from download counts; the user sees them called out separately in the output instead.

---

## ADR-024: Batched overwrite confirmation for downloads

**Status:** Accepted
**Applies to:** `GetObjects`

**Context:** A whole-bucket or whole-prefix download could encounter many pre-existing local files. Asking about each conflict individually, one prompt per file, doesn't scale.

**Decision:** Determine all conflicts up front, show the full list once (up to a preview limit), and ask a single "overwrite these?" confirmation. If declined, only the conflicting files are skipped — every non-conflicting file still downloads normally.

**Alternatives considered:**
- *Per-file interactive prompt* — rejected: could mean hundreds of individual prompts on a large download, which stops being a meaningful confirmation and becomes noise.

**Consequences:** A single "no" doesn't abort the whole download — it's more like "skip only what would collide." Reuses the same "show everything, then ask once" philosophy as ADR-001/ADR-002.

---

## ADR-025: Manual download loop over TransferManager's native downloadDirectory()

**Status:** Accepted
**Applies to:** `GetObjects`

**Context:** `S3TransferManager` has a built-in `downloadDirectory()` method that could replace the manual list-then-loop approach, potentially with better parallelism.

**Decision:** Keep the manual approach: list matching objects first, resolve overwrite conflicts (ADR-024), then download each item individually via `TransferManager.downloadFile()`.

**Alternatives considered:**
- *Use `downloadDirectory()`* — rejected: it does not expose a hook to inspect which files would be overwritten before it starts writing, which is required to support the interactive overwrite confirmation the user specifically asked for. Using it would mean giving up that visibility.

**Consequences:** More manual code than the native method would require, in exchange for the ability to preview and control overwrites. Worth reconsidering if a future SDK version exposes conflict-checking hooks on `downloadDirectory()`.

---

## ADR-026: Making --keys and --prefix explicitly mutually exclusive

**Status:** Accepted (bug fix)
**Applies to:** `GetObjects`

**Context:** The first version of `GetObjects` parsed both `--keys` and `--prefix` without checking whether both were supplied in the same invocation. If both were present, the code silently preferred `--keys` and discarded the parsed `--prefix` value with no warning — a genuine bug, not an intentional design choice.

**Decision:** Add an explicit check immediately after argument parsing: if both `keys` and `prefix` are non-null, print a clear error and exit, rather than silently picking one.

**Alternatives considered:**
- *Support some combined behavior (e.g. treat `--keys` as a filter within `--prefix`)* — not pursued; not what was originally asked for, and would add complexity for a use case that hasn't been requested.

**Consequences:** The three download modes (whole bucket / prefix / explicit keys) are now properly mutually exclusive at the CLI level, matching user expectation. Documented here explicitly as a caught-and-corrected mistake, per the "keep it current" documentation principle — not presented as original design intent.

---

## ADR-027: SLF4J-simple for AWS SDK internal logging

**Status:** Accepted
**Applies to:** Project-wide (relevant once `S3AsyncClient`/`TransferManager` were introduced)

**Context:** The AWS SDK logs internally via SLF4J. Without any SLF4J binding on the classpath, this produces a "Failed to load class StaticLoggerBinder... defaulting to no-operation logger" warning and all SDK-internal logs go nowhere.

**Decision:** Add `slf4j-simple` as a lightweight binding.

**Alternatives considered:**
- *A full logging framework (e.g. Logback)* — rejected as disproportionate for a set of single-file scripts; `slf4j-simple` needs no separate configuration file to produce basic console output.
- *Leave the warning as-is, ignore SDK-internal logging entirely* — considered acceptable at the time, but adding the binding was low-cost and keeps the option open for future debugging.

**Consequences:** `slf4j-simple`'s default threshold is INFO, and the AWS SDK logs relatively little at INFO (most detail is at DEBUG) — so this addition currently has limited visible effect without further configuration (e.g. `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug` or a `simplelogger.properties` file).

---

## Open Questions / Not Yet Decided

These are known gaps or pending items raised during development but not yet acted on. Listed here rather than as ADRs, since no decision has actually been made.

- **Cross-region access retrofit:** `crossRegionAccessEnabled` (ADR-004) was only added to `DeleteBuckets`. Whether to retrofit it into `ListRecentBuckets`, `LatestBucket`, `ListObjects`, and `DeleteObjects` was explicitly raised and never confirmed. All four remain limited to the client's configured region until this is decided.
- **Non-interactive mode for destructive scripts:** `DeleteObjects` and `DeleteBuckets` have no `--yes`/`-y` flag to skip the confirmation prompt, so neither can run unattended (e.g. in CI). Raised as future work in ADR-001, not designed.
- **Permanent version purge:** No script exists for permanently removing old object versions from a versioned bucket (as opposed to `DeleteObjects`' delete-marker-only behavior, ADR-003). Would need its own script and its own confirmation design given the severity.
- **Per-path `--prefix` in `PutObjects`:** Currently one global prefix applies to every path in a single invocation. Per-path prefixes were noted as a reasonable want but not requested or built.
- **`CreateBuckets`' vestigial `crossRegionAccessEnabled`:** discovered during this documentation pass — the setting is present on `CreateBuckets`' client builder with a comment referencing a "pre-check" that was removed when an unused `NoSuchBucketException` import was cleaned up for a linter warning. The setting is harmless (not currently exercised by any cross-region logic in this script) but the accompanying comment is stale, and the setting itself may be unnecessary here. Flagged as a cleanup candidate, not a decision either way.