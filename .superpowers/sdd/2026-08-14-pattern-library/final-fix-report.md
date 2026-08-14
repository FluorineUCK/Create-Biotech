# Pattern Library — Final Fix Report

## Status

**DONE** — all four Critical findings, all four Important findings, and every intersecting low-risk Minor from `final-review.md` are addressed in one final-fix wave based on `9fb33368`. The implementation preserves the existing no-chunk-load, member-local persistence, entity-conservation, client-projection, `isMoving`, and no-double-add rulings.

## Finding disposition

### Critical 1 — owned nested NBT and framework metadata

- `PatternStorageCoreBlockEntity` now owns one strict `PatternLibrary` compound. Only that compound and its `ServerState` child use exact-key validation.
- Server writes always call `SmartBlockEntity.write` before adding owned state. Server reads always call `SmartBlockEntity.read` before inspecting owned state, so vanilla `id/x/y/z`, `keepPacked`, NeoForge persistent data, and serializable attachments remain framework-owned root metadata.
- Client update tags remain exactly `LibraryId` plus bounded `ClientState`; no server state was added to the client projection.
- `PatternStorageCoreBlockEntityTest.fullMetadataAndNeoForgeStateRoundTripThroughRealBlockEntityApis` exercises real `saveWithFullMetadata -> loadWithComponents`, vanilla metadata, `keepPacked`, `NeoForgeData`, and a registered serializable NeoForge attachment.
- `PatternLibraryGameTests.fullMetadataReloadRetainsLibrarianSnapshot` exercises `saveWithFullMetadata`, registered `BlockEntity.loadStatic`, BE replacement in a real `ServerLevel`, exact snapshot retention, and both core halves.

### Critical 2 — budgeted linear/logarithmic page work

- Removed per-page `rebuildRecordView()`. An incomplete generation appends at most one immutable record snapshot per fingerprint budget unit, then publishes the completed builder in O(1); a stable sweep keeps the prior immutable generation until an actual change atomically restarts it.
- Cached-page and error updates are O(1)/O(log P). Errors are maintained in a sorted `TreeMap` instead of rebuilt from the full cache.
- Persisted load builds a `HashSet` once for page membership and visits the ordered record prefix once. The prior per-cache-entry linear `pageOrder.contains` path is gone.
- The 20-tick shelf inspection stores a six-bit writable-slot topology per chiseled shelf. Page keys are rebuilt only when that topology/membership changes (or when clearing a stale nonempty order), not for book-body edits or ordinary-bookshelf/queue-only structure changes. Zero-page shelves avoid address/key construction entirely.
- Operation-count regressions in `PatternLibraryIndexTest` prove:
  - default legal maximum: 63 chiseled shelves × 6 slots × 100 pages = **37,800 reads and exactly 37,800 record-maintenance units**;
  - persisted 6,000-page library: **6,000 set-membership checks and 6,000 ordered record visits**;
  - 200 errors: O(1) total count with only the sorted first **128** materialized;
  - an ordinary-shelf queue-count change leaves the index generation/page topology unchanged.

### Critical 3 — same-block lower/upper transitions

- `PatternStorageCoreLifecycle.sameLogicalPart` skips only updates that retain both block type and logical `HALF`. A lower-to-upper or upper-to-lower same-block replacement now enters the normal forced-removal transaction.
- `PatternLibraryGameTests.sameBlockLowerUpperTransitionsConserveBothLibrarians` performs both real state transitions and asserts exactly the two original villager UUIDs, no recovery `ItemEntity`, and no custom `CardboardBoxEntity`.

### Critical 4 — Sable entity coordinates

- Block lookup/mutation remains in local/plot coordinates.
- Every librarian safe-release target, collision AABB target, recovery item position, player-break loot position, and entity-related world-border check now uses existing `SubLevelCompat.toWorld`; `PatternStorageCoreBlock.hasSpaceForUpperHalf` uses the same projection for its world-border point.
- No projection formula or pose math was duplicated in production.
- `PatternStorageCoreLifecycleTest.entityCoordinatesPassThroughNormallyAndUseSubLevelCompatProjection` covers ordinary pass-through and a real `Pose3d` translation `(100, 50, -20)` plus 90-degree Y rotation, mapping local `(2, 3, 4)` to world `(104, 53, -22)`.

### Important 1 — VALID/READY gates and SLP retention

- Public enqueue, index advancement, and public drain all require `StructureState.VALID`, a valid non-conflicting binding, and `BindingAccess.READY`.
- Invalid structure, partial/unformed state, authority conflict, and authority offline return without mutating queued queries or ready replies.
- `PatternLibraryGameTests.foundationBindReconcileAndAccessGate` creates both a queued query and a completed reply, proves `CORE_CONFLICT` freezes both despite READY authority, restores VALID, removes the authority, and proves public plus natural ticks remain byte-identical while offline.
- `PatternStorageCoreBlockEntityTest.everyQueryOperationRequiresValidStructureAndReadyAuthority` covers the policy truth table.

### Important 2 — corrupt raw librarian recovery

- The BE exposes a defensive package-local raw recovery snapshot and an exact-copy restore method to the lifecycle transaction.
- Forced removal of undecodable raw librarian state does not guess an entity or delete the data. It restores both core halves and copies the raw compound byte-for-byte into the new lower BE with pending safe release.
- `PatternLibraryGameTests.corruptRawSnapshotSurvivesForcedRemoval` covers corrupt server load, real forced replacement, exact raw recovery, both restored halves, and zero villager/item/custom-box duplication.

### Important 3 — routable replies and tick allowance

- `PatternReply` now persists `requesterComputerId` and `logisticsId`; `PatternReplyStatus` is a public enum in its own file.
- Query completion, snapshots, and strict NBT codecs preserve both routing identities.
- The public core drain is requester-filtered. It removes only matching replies, so a different or unknown requester cannot destructively steal another requester's result.
- `ReplyDispatchAllowance` holds one allowance per game tick inside the core. Repeated drains cannot reset it; a requester with no matching reply does not consume someone else's allowance.
- Routing, persistence, non-stealing, and repeated-call allowance are covered in `PatternLibraryIndexTest`, `PatternJsonParserTest`, and `PatternStorageCoreBlockEntityTest`.

### Important 4 — bounded public Pattern summary

- Added public immutable `PatternLibrarySummary`, public `PatternErrorReason`, and `PatternStorageCoreBlockEntity.summary()`.
- The summary exposes reason, capacity, page count, indexed-page progress, indexed pattern count, total error pages, queued pages, scanning state, and errors.
- Errors are maintained sorted and are capped server-side at 128 before the immutable DTO is constructed. Counts and progress are O(1); creating the DTO never traverses full Pattern NBT or the full page/cache collection.
- `PatternLibraryIndexTest.publicSummaryIsBoundedSortedAndReportsProgressWithoutNbtTraversal` covers incomplete and complete progress, sorting, cap, totals, and immutability. `PatternStorageCoreBlockEntityTest.coreExposesBoundedPanelSummaryFromRuntimeCounters` covers the public BE boundary.

## Intersecting Minor findings

- `PatternCoreClientState` rejects noncanonical boolean bytes and villager type IDs absent from the registered vanilla villager-type registry.
- Failed controlled wrench removal no longer plays the remove sound; sound occurs only after lifecycle success.
- Invalid/unformed structure clears Efficiency contribution and exposes zero search budget; partial state retains the prior valid snapshot without advancing work.
- Steady-state structure scans no longer unconditionally dirty or sync. Index ticks dirty only when a budget unit actually advances persisted state; client sync occurs only when the bounded DTO changes.
- Both requested existing GameTests now explicitly assert zero `CardboardBoxEntity` outputs.
- The empty GameTest fixture moved from `data/minecraft/structure/empty.nbt` to `data/create_biotech/structure/empty.nbt`; all Pattern tests use `templateNamespace = "create_biotech"` and verify that runtime resource.

## TDD evidence

### Baseline

```powershell
.\gradlew.bat test --tests "*pattern*" --offline
```

Result at fix base `9fb33368`: passed before final-review regressions were added.

### RED — NBT/framework metadata and canonical DTO

```powershell
.\gradlew.bat test --tests "*PatternStorageCoreBlockEntityTest" --offline --no-daemon --console=plain
```

The first focused run produced three intended failures: the old root-exact server schema rejected full metadata, NeoForge state did not round-trip through the real API, and noncanonical/unknown client DTO values were accepted. The owned nested schema and registry-aware validation made the focused suite green.

### RED — page complexity, topology, and summary API

```powershell
.\gradlew.bat test --tests "*PatternLibraryIndexTest" --tests "*PatternStorageCoreBlockEntityTest" --offline --no-daemon --console=plain
```

Before implementation, `compileTestJava` reported the expected missing operation counters, topology, and summary contracts (16 missing-symbol errors). After the incremental builder/set/tree/topology implementation, the large operation-count tests passed.

An additional exact topology RED was captured against the old `structureChanged` rebuild condition:

```powershell
.\gradlew.bat test --tests "*PatternStorageCoreBlockEntityTest.ordinaryShelfChangesDoNotRebuildUnchangedPageTopology" --offline --no-daemon --console=plain
```

Result before the final condition change: 1 test, 1 assertion failure at the generation check. Result after restricting rebuilds to topology change/stale clearing: `BUILD SUCCESSFUL`.

### RED — routing, gates, allowance, and public status

```powershell
.\gradlew.bat test --tests "*PatternLibraryIndexTest" --tests "*PatternStorageCoreBlockEntityTest" --tests "*PatternJsonParserTest" --offline --no-daemon --console=plain
```

Before implementation, `compileTestJava` reported the expected missing requester-aware reply, public status, requester drain, gate, and allowance contracts (15 missing-symbol errors). The persisted routing and bounded drain implementation made the focused suites green.

### RED — lifecycle half/projection seams

```powershell
.\gradlew.bat test --tests "*PatternStorageCoreLifecycleTest" --offline --no-daemon --console=plain
```

The regression source initially failed compilation because `sameLogicalPart` and the centralized `entityWorldPosition` seam did not exist. The test uses reflection only to construct Sable's runtime `Pose3d`, because JOML is not directly present on the isolated test compile classpath; production calls only `SubLevelCompat`.

### RED — steady-state sync

```powershell
.\gradlew.bat test --tests "*PatternStorageCoreBlockEntityTest.repeatedIdenticalStructureScanDoesNotResyncSteadyState" --offline --no-daemon --console=plain
```

The regression failed while `applyStructureScan` unconditionally called `setChanged/sendData`; it passed after mutation and client DTO comparisons became explicit.

## Final verification

All Java/Gradle commands below used a D-drive temporary directory to avoid the host C-drive free-space limit:

```powershell
$tmp='D:\mymod\.gradle-tmp'
$env:TEMP=$tmp
$env:TMP=$tmp
$env:GRADLE_OPTS="-Djava.io.tmpdir=$tmp"
```

### Focused Pattern tests

```powershell
.\gradlew.bat test --tests "*pattern*" --offline --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 38s`; parsed JUnit XML reports **106 tests, 0 failures, 0 errors, 0 skipped**.

### Pattern GameTests

```powershell
$env:JAVA_TOOL_OPTIONS='-Dneoforge.enabledGameTestNamespaces=create_biotech'
.\gradlew.bat runGameTestServer --offline --no-daemon
```

Result: **13 GAME TESTS COMPLETE; all 13 required tests passed; BUILD SUCCESSFUL**. This is a command-local namespace selection only; no permanent GameTest filter was added.

### Full offline unit suite

```powershell
.\gradlew.bat test --offline --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 39s`; parsed JUnit XML reports **157 tests, 0 failures, 0 errors, 0 skipped**.

### Full offline build

```powershell
.\gradlew.bat build --offline --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 26s`.

### Static checks

- `git diff --check`: passed with no whitespace errors (only the repository's Windows CRLF conversion notices).
- No `SavedData`, custom packet, chunk ticket/forced load, duplicate Sable projection formula, or permanent GameTest filtering was added.
- No change touches the unrelated `LaunchedItemForBeltMixin` blocker.
- The Task 4 `isMoving` short-circuit and single production `addFreshEntity` path remain intact.

## Files changed

### Production

- `PatternCoreClientState.java`
- `PatternErrorReason.java`
- `PatternLibraryIndex.java`
- `PatternLibrarySummary.java`
- `PatternPageError.java`
- `PatternReply.java`
- `PatternReplyStatus.java`
- `PatternStorageCoreBlock.java`
- `PatternStorageCoreBlockEntity.java`
- `PatternStorageCoreLifecycle.java`
- `PatternValueCodecs.java`
- `ReplyDispatchAllowance.java`

### Tests and fixture

- `PatternJsonParserTest.java`
- `PatternLibraryIndexTest.java`
- `PatternStorageCoreBlockEntityTest.java`
- `PatternStorageCoreLifecycleTest.java`
- `PatternLibraryGameTests.java`
- `data/create_biotech/structure/empty.nbt` (moved from the `minecraft` namespace)

## Concerns

- The host C drive had insufficient temporary space during one Gradle invocation. Redirecting Java/Gradle temporary files to `D:\mymod\.gradle-tmp` made every rerun deterministic; this is an environment concern, not a product failure.
- Headless Create initialization logs the existing non-failing `Payload create:sync_edge_group may not be sent to client` event-listener noise. All 13 required Pattern GameTests still pass.
- The unrelated unfiltered 74-GameTest `LaunchedItemForBeltMixin` failure was intentionally neither changed nor hidden, per the final-review ruling.
