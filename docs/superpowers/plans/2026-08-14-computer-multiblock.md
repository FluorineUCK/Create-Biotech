# Computer Multiblock Corrected Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add villager-powered Computer blocks and dedicated Computer Casing, validate bounded sealed casing-bus cuboids, conserve installed residents losslessly, and persist a stable Foundation authority plus frozen epoch topology without centralizing future task scheduling.

**Architecture:** A Computer freezes a strict biological `ComputerProfile` at installation and retains the original filled-box snapshot. A bounded candidate enumerator validates complete cuboids around a seed Computer without following arbitrary casing outside a candidate. Every member redundantly stores a stable structure-member UUID, structure revision/snapshot, binding, epoch, and monotonic epoch faults; only an adapter owned by the elected Computer is published as the Foundation `COMPUTER_COORDINATOR`.

**Tech Stack:** Java 21, NeoForge 1.21.1 blocks/BEs and GameTest, Create connected-texture casing, existing captured-entity box helpers, JUnit Jupiter, `ClusterBindingService`/`ClusterMemberIndex`, `SpaceAddress`, `CBMultiBlockLifecycle`, and `SubLevelCompat`.

## Global Constraints

- Execute only from a clean descendant of committed Pattern-recovery/API-lock baseline `fac73ecd` (which is itself a descendant of reviewed Foundation baseline `b72b099b`).
- Default Computer dimensions are `3..7` per axis, default safe volume is `7^3 = 343`, and the default Computer limit is `32`; runtime values come only from `CBConfigs.SERVER.factoryCluster.computerMinSize`, `computerMaxSize`, and `computerMaxNodes`.
- The effective maximum is clamped to `3..16`, the effective minimum to `3..effectiveMax`, the node limit to `1..256`, and `maxVolume` is exactly `Math.toIntExact(Math.multiplyExact(Math.multiplyExact((long) max, max), max))`. There is no fourth Computer-volume config.
- Every outer coordinate is `create_biotech:computer_casing`; a Computer never substitutes for shell casing.
- Interior allows air, Computer, Computer Casing, or a block in `create_biotech:computer_internal_components`. Every Computer must touch casing connected by six-way casing-only flood fill to the outer shell.
- One structure is wholly within one root dimension and one exact Sable space. Every world/BE read is non-loading and preceded by `isLoaded` and same-space validation.
- Installed residents cannot be removed, changed, healed, or re-profiled until block destruction. Passenger-bearing captured entities are rejected at installation so the release path cannot silently lose passengers.
- Resident snapshots are not `Clearable` content. `isMoving=true` suppresses release and preserves all BE NBT for Sable movement.
- Normal villagers use `1/1, 2/1, 3/2, 4/2, 4/2`; librarians use `4/2, 6/2, 8/3, 12/3, 16/4`; nitwits are exactly `1/0` plus loop abort; wandering traders are exactly `2/1` plus the frozen configured range bonus; zombie villagers are exactly `0/0`.
- The approved future Pattern query range remains `min(configuredMax, configuredBase + epoch.totalPatternRangeBonus())`, measured between externally transformed centers; the approved defaults are base `64`, per-trader frozen bonus `64`, and max `512`. The total includes only trader profiles frozen into that epoch; pending/current extras never change it.
- `computerId` identifies one physical Computer. `computerStructureMemberId` identifies the structure's stable Foundation member and never aliases the elected coordinator's `computerId`.
- Active-epoch membership is a frozen subset: current extra Computers are pending; an empty pending Computer may receive its one permanent resident without joining or changing the epoch; missing frozen non-coordinators remain frozen/offline and are never substituted; missing/mismatched coordinator makes the epoch globally offline and never triggers election.
- `WIDTH` and `DEPTH` are monotonic, persisted epoch faults. Hot-add, ordinary scans, reload/restart, and cancelling one root cannot clear them. Only `STOP_ALL_AND_REFORM` can clear them: it first terminates/clears every root, frame, and mailbox, then requires a fully loaded same-space valid scan. A frozen member may be absent only when that complete scan positively proves its block/ID was permanently destroyed; an unloaded or space-uncertain member keeps the old epoch and latches offline.
- This plan introduces no task frame, mailbox, Runtime class, menu, packet, or protocol-version change. Future Runtime supplies only the narrow `EpochQuiescence` and `EpochReformControl` seams defined here; the Computer code never imports a future Runtime implementation type.
- A fault-free active epoch has a separate expected-ID-guarded close transaction. It may close only from a fully loaded, same-space, identity-consistent snapshot after all roots are stopped and every frozen/current node is idle, root-free, and mailbox-empty. Every frozen member must be profiled; `VALID_NOT_READY` is allowed only for empty pending extras and leaves the resulting inactive structure not ready. `WIDTH`/`DEPTH` requires explicit `STOP_ALL_AND_REFORM`; normal close never stops or clears Runtime work and never abandons a missing frozen member.
- Every entity-space position produced by Computer removal -- resident candidates, moved collision boxes, block-center fallback, recovery-box output, and prepared empty-Computer loot -- is projected exactly once with `SubLevelCompat.toWorld(level, computerPos, localCenter)` before collision or spawn.
- Server-save NBT and client update NBT are disjoint. Client update NBT is an exact bounded allowlist and never contains resident/entity data, binding/cluster identity, epoch membership/addresses, structure identity/revision, frames, or mailboxes.
- The authoritative custom internal-component tag is the committed static JSON in `src/main/resources`; `CBBlockTagsProvider` does not also generate that tag.
- Every commit stages only the exact paths named in its task. Never use `git add src/main/java/.../computer`, `git add src/main/resources`, `git add .`, or another directory-wide add.

---

## Pre-execution Gate: Clean Baseline and Post-Pattern API Lock

Pattern recovery and its retained-schema correction are committed at clean `fac73ecd` (`fix(pattern): close retained schema ambiguity`). The requester-filtered public signatures below were rerun against that exact HEAD and passed. Do not start Task 1 if Pattern working changes reappear; execution must begin from a clean descendant of both `b72b099b` and `fac73ecd`.

- [ ] **Gate 1: Require a clean descendant of the reviewed Foundation baseline**

```powershell
$dirty = @(git status --short)
if ($dirty.Count -ne 0) { throw "Worktree is not clean:`n$($dirty -join "`n")" }
git merge-base --is-ancestor b72b099b HEAD
if ($LASTEXITCODE -ne 0) { throw 'HEAD is not a descendant of b72b099b' }
git merge-base --is-ancestor fac73ecd HEAD
if ($LASTEXITCODE -ne 0) { throw 'Final Pattern API baseline fac73ecd is not present' }
```

Expected: the dirty check does not throw and both ancestry commands exit `0`.

- [ ] **Gate 2: Recheck only Pattern's requester-filtered public contract after recovery**

```powershell
$be = 'src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlockEntity.java'
function Require-GitGrep([string]$pattern) {
  & git grep -n -F -e $pattern HEAD -- $be
  if ($LASTEXITCODE -ne 0) { throw "Pattern public API drift: $pattern" }
}
Require-GitGrep 'drainReplies(UUID requesterComputerId, int maxReplies)'
Require-GitGrep 'public boolean enqueueQuery(PatternQuery query)'
Require-GitGrep 'public PatternLibraryScanner.StructureState structureState()'
Require-GitGrep 'PatternStructureSnapshot structureSnapshot()'
Require-GitGrep 'public PatternLibrarySummary summary()'
Require-GitGrep 'public SpaceAddress memberAddress()'
$record = ((git show HEAD:src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternRecord.java) -join ' ') -replace '\s+', ' '
$expected = 'public record PatternRecord(UUID patternId, PatternPageKey source, List<PatternIngredient> inputs, List<PatternOutput> outputs, String recipeAddress)'
if (-not $record.Contains($expected)) { throw 'PatternRecord component order drifted' }
```

Expected public lock:

```java
PatternLibraryScanner.StructureState structureState();
@Nullable PatternStructureSnapshot structureSnapshot();
PatternLibrarySummary summary();
boolean enqueueQuery(PatternQuery query);
List<PatternReply> drainReplies(UUID requesterComputerId, int maxReplies);
SpaceAddress memberAddress();

record PatternRecord(UUID patternId, PatternPageKey source,
    List<PatternIngredient> inputs, List<PatternOutput> outputs,
    String recipeAddress) {}
```

If recovery deliberately changes one of those signatures, stop before Task 1 and update the downstream Runtime/UI interface-lock documents; do not infer an overload from Pattern internals. The Computer implementation in this plan must not import a Pattern implementation class. Future Runtime must call `drainReplies(frozenComputerId, maxReplies)`, never `drainReplies(maxReplies)`.

- [ ] **Gate 3: Prove the recovered baseline is green**

```powershell
./gradlew.bat test --tests "*Pattern*" --offline
./gradlew.bat test --tests "*ClusterBinding*" --tests "*ClusterMemberIndex*" --offline
```

Expected: both commands end in `BUILD SUCCESSFUL`.

---

## Locked Production Interfaces

These signatures are the final Task-5 public deliverable and must not be replaced by alternate overloads. To keep commits independently compiling, Task 4 implements only the first five accessors whose return types already exist (`computerId` through `epoch`) plus raw-safe persistence internals; Task 5 creates the reason/view types and adds the remaining accessors, result enums, and narrow mutators exactly as locked below.

```java
public record ComputerProfile(NodeKind kind, int slots, int depth,
    boolean loopAbort, int patternRangeBonus) {
    public static Optional<ComputerProfile> fromEntity(Entity entity, int traderRangeBonus);
    public static Optional<ComputerProfile> forKind(NodeKind kind, int level, int traderRangeBonus);
    public CompoundTag save();
    public static Optional<ComputerProfile> load(CompoundTag tag);
}

public record ComputerStructureNode(UUID computerId, SpaceAddress address,
    @Nullable ComputerProfile profile) {}

public final class ComputerStructureSnapshot {
    public BoundingBox bounds();
    public List<ComputerStructureNode> nodes();              // UUID-sorted, immutable
    public Set<BlockPos> casingPositions();                  // shell plus interior bus
    public Set<ChunkPos> containingChunks();                 // every chunk intersecting bounds
    public Optional<ComputerStructureNode> node(UUID computerId);
    public Set<UUID> computerIds();
    public CompoundTag save();
    public static Optional<ComputerStructureSnapshot> load(CompoundTag tag);
    @Override public boolean equals(Object other);          // structural value equality
    @Override public int hashCode();                        // same structural components
}

public record ClusterEpoch(UUID epochId, UUID clusterId,
    UUID computerStructureMemberId, UUID coordinatorId,
    SpaceAddress coordinatorAddress, BoundingBox bounds,
    List<EpochNode> nodes) {
    public static ClusterEpoch freeze(UUID clusterId, UUID structureMemberId,
        ComputerStructureSnapshot snapshot);
    public ClusterEpoch relocate(ComputerStructureSnapshot current);
    public int totalPatternRangeBonus();
    public CompoundTag save();
    public static Optional<ClusterEpoch> load(CompoundTag tag);
}

public interface EpochQuiescence {
    boolean allRootsStopped();
    boolean nodeIdle(UUID computerId);
    boolean nodeRootFree(UUID computerId);
    boolean nodeMailboxEmpty(UUID computerId);
}

public interface EpochReformControl extends EpochQuiescence {
    boolean stopAllAndClear();
}
```

`ClusterEpoch.relocate` accepts a new fully validated snapshot, preserves epoch ID, frozen membership, UUID order, profiles, and structure member ID, and updates both bounds and coordinator address. It accepts only a translation or one of the 24 proper axis rotations of the old bounds/frozen-node layout within the same root dimension and same nullable Sable space ID. It rejects a missing frozen ID, a changed frozen profile, a reflection/non-rigid layout, changed dimension/space, or changed bound dimensions except axis permutation. Extra current IDs are ignored by the epoch and exposed as pending.

`BoundingBox` is mutable in the Minecraft API. Both snapshot and epoch copy its six coordinates on construction/load and override `bounds()` to return a fresh defensive `BoundingBox`; no caller receives the stored instance. All collection accessors use `List.copyOf`/`Set.copyOf`, and every stored `BlockPos` is immutable. Snapshot equality/hash are structural over the six copied coordinates plus canonical nodes/casing/chunk sets, so independently decoded replicas compare equal without trusting object identity.

The server-only access surface on `ComputerBlockEntity` is:

```java
public Optional<UUID> computerId();
public Optional<UUID> computerStructureMemberId();
public Optional<ComputerProfile> installedProfile();
public Optional<ComputerStructureSnapshot> currentStructureSnapshot();
public Optional<ClusterEpoch> epoch();
public ComputerAvailabilityReason availabilityReason();
public boolean structureOnline();                 // derived, never independently mutable
public boolean coordinatorOnline();               // derived, never independently mutable
public List<ComputerNodeView> currentNodes();      // current snapshot UUID order
public List<ComputerNodeView> frozenNodes();       // epoch UUID order
public List<ComputerNodeView> pendingNodes();      // current IDs minus frozen IDs, UUID order
public Set<EpochFault> latchedEpochFaults();       // immutable copy
public boolean requiresReform();
public ComputerClusterView clusterView();          // bounded immutable UI DTO
public EpochStartResult startEpoch(UUID clusterId);
public FaultLatchResult latchEpochFault(UUID expectedEpochId, EpochFault fault);
public EpochCloseResult closeIdleEpoch(UUID expectedEpochId,
    EpochQuiescence quiescence);
public ReformResult stopAllAndReform(UUID expectedEpochId,
    EpochReformControl control);
```

The four result enums are nested in `ComputerBlockEntity` so they add no unowned files and have these exact values:

```java
public enum EpochStartResult {
    STARTED, NOT_COORDINATOR, NOT_READY, BINDING_UNAVAILABLE,
    ALREADY_ACTIVE, REQUIRES_REFORM
}
public enum FaultLatchResult {
    LATCHED, ALREADY_LATCHED, NO_EPOCH, EPOCH_MISMATCH, IDENTITY_INVALID
}
public enum EpochCloseResult {
    CLOSED, NO_EPOCH, EPOCH_MISMATCH, REQUIRES_REFORM, ROOTS_REMAIN,
    RUNTIME_NOT_QUIESCENT, PARTIAL_UNLOADED, SPACE_UNCERTAIN,
    STRUCTURE_INVALID, FROZEN_MEMBER_MISSING, IDENTITY_CONFLICT,
    PROFILE_NOT_READY
}
public enum ReformResult {
    REFORMED, NO_EPOCH, EPOCH_MISMATCH, STOP_FAILED, ROOTS_REMAIN,
    RUNTIME_NOT_QUIESCENT,
    PARTIAL_UNLOADED, SPACE_UNCERTAIN, STRUCTURE_INVALID,
    IDENTITY_CONFLICT, PROFILE_NOT_READY
}
```

```java
public record ComputerNodeView(UUID computerId,
    @Nullable ComputerProfile profile, boolean online,
    boolean pending, boolean coordinator) {}

public record ComputerClusterView(ComputerAvailabilityReason reason,
    boolean structureOnline, boolean coordinatorOnline, boolean epochActive,
    boolean requiresReform, List<ComputerNodeView> current,
    List<ComputerNodeView> frozen,
    List<ComputerNodeView> pending, Set<EpochFault> faults) {}
```

`ComputerClusterView` contains no resident box, entity UUID/data, `clusterId`, logistics IDs, binding authority/revision, `SpaceAddress`, or raw NBT. With no epoch, `current` contains the valid current nodes while `frozen`/`pending` are empty. With an epoch, `current` is the current snapshot, `frozen` retains every epoch member (including offline entries), and `pending` is exactly current IDs minus frozen IDs. Runtime uses `currentStructureSnapshot().node(computerId).address()` on the server; UI uses the bounded DTO.

---

## File Ownership Map

### Task 1 owns

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/NodeKind.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerProfile.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerProfileTest.java`

### Task 2 owns

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureNode.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureSnapshot.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureScanner.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureScannerTest.java`

### Task 3 owns

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/EpochNode.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/EpochFault.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/EpochQuiescence.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/EpochReformControl.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ClusterEpoch.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ClusterEpochTest.java`

### Task 4 owns

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockTags.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerDisplayState.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerClientState.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerInstallResult.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureRecord.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlock.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBreakHandler.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerCasingBlock.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockEntity.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerResidentLifecycle.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockEntityTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerResidentLifecycleTest.java`
- the exact registry/client/data/resource files enumerated in Task 4

### Task 5 owns

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerAvailabilityReason.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerNodeView.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerClusterView.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerWorldView.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerTopologyController.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureLocator.java`
- modifications to `ComputerBlockEntity.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerTopologyControllerTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureLocatorTest.java`

### Task 6 owns

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerCoordinatorMember.java`
- modifications to `ComputerBlockEntity.java`, `ComputerCasingBlock.java`, `ComputerTopologyController.java`, and `ComputerStructureLocator.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerCoordinatorMemberTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/ComputerCoordinatorBindingTest.java`

### Task 7 owns

- `src/main/java/com/nobodiiiii/createbiotech/gametest/ComputerClusterGameTests.java`

---

## Task 1: Freeze and Strictly Decode the Approved Biological Hardware Table

**Files:** Task 1 ownership set only.

**Produces:** the exact `ComputerProfile` interface above. No world, BE, Pattern, or Runtime dependency.

- [ ] **Step 1: RED — write complete valid-table and invalid-cross-combination tests**

Use a parameterized valid table and explicit corrupt NBT cases:

```java
@ParameterizedTest
@CsvSource({
    "VILLAGER,1,1,1", "VILLAGER,2,2,1", "VILLAGER,3,3,2",
    "VILLAGER,4,4,2", "VILLAGER,5,4,2",
    "LIBRARIAN,1,4,2", "LIBRARIAN,2,6,2", "LIBRARIAN,3,8,3",
    "LIBRARIAN,4,12,3", "LIBRARIAN,5,16,4",
    "NITWIT,1,1,0", "WANDERING_TRADER,1,2,1",
    "ZOMBIE_VILLAGER,1,0,0"
})
void approvedTable(NodeKind kind, int level, int slots, int depth) {
    ComputerProfile profile = ComputerProfile.forKind(kind, level, 64).orElseThrow();
    assertEquals(slots, profile.slots());
    assertEquals(depth, profile.depth());
    assertEquals(kind == NodeKind.NITWIT, profile.loopAbort());
    assertEquals(kind == NodeKind.WANDERING_TRADER ? 64 : 0,
        profile.patternRangeBonus());
    assertEquals(profile, ComputerProfile.load(profile.save()).orElseThrow());
}

@Test
void codecRejectsEveryIllegalKindCombination() {
    assertFalse(load("VILLAGER", 16, 4, false, 0).isPresent());
    assertFalse(load("LIBRARIAN", 4, 2, true, 0).isPresent());
    assertFalse(load("NITWIT", 1, 0, false, 0).isPresent());
    assertFalse(load("NITWIT", 1, 0, true, 1).isPresent());
    assertFalse(load("WANDERING_TRADER", 2, 1, false, 4097).isPresent());
    assertFalse(load("ZOMBIE_VILLAGER", 1, 0, false, 0).isPresent());
    assertFalse(load("ZOMBIE_VILLAGER", 0, 1, false, 0).isPresent());
}

@Test
void constructorsRejectInvalidLevelAndBonusInsteadOfClamping() {
    assertTrue(ComputerProfile.forKind(NodeKind.VILLAGER, 0, 64).isEmpty());
    assertTrue(ComputerProfile.forKind(NodeKind.LIBRARIAN, 6, 64).isEmpty());
    assertTrue(ComputerProfile.forKind(NodeKind.NITWIT, 0, 64).isEmpty());
    assertTrue(ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 2, 64).isEmpty());
    assertTrue(ComputerProfile.forKind(NodeKind.ZOMBIE_VILLAGER, 2, 64).isEmpty());
    assertTrue(ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 1, -1).isEmpty());
    assertTrue(ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 1, 4097).isEmpty());
}
```

Also build real adult `Villager` fixtures for every level as ordinary/librarian, a nitwit, a `WanderingTrader`, and a non-baby `ZombieVillager`, and assert `fromEntity` yields the same 13 approved rows. Wrong NBT type, missing/extra key, unknown kind, non-canonical boolean, negative value, unsupported entity, and baby entity all return empty without mutation.

- [ ] **Step 2: Verify RED**

Run:

```powershell
./gradlew.bat test --tests "*ComputerProfileTest" --offline
```

Expected: compilation fails because `NodeKind`/`ComputerProfile` do not exist.

- [ ] **Step 3: GREEN — implement exact table and strict version-1 codec**

`NodeKind` contains only:

```java
VILLAGER, LIBRARIAN, NITWIT, WANDERING_TRADER, ZOMBIE_VILLAGER
```

`ComputerProfile` uses exact NBT keys `Version`, `Kind`, `Slots`, `Depth`, `LoopAbort`, and `PatternRangeBonus`, requires exactly those keys and exact tag types, requires `Version == 1`, and validates the whole kind-specific invariant rather than numeric maxima alone. Ordinary/librarian/nitwit villager input levels must be `1..5` (nitwit output is level-independent inside that valid domain); trader/zombie use only sentinel level `1`; no clamp upgrades malformed data. Trader bonus must be `0..4096`; every other kind requires zero. `fromEntity` accepts only adult `Villager`, `WanderingTrader`, and non-baby `ZombieVillager`; it classifies librarian/nitwit from `VillagerData`, rejects villager levels outside `1..5`, and returns `Optional.empty()` for every unsupported case.

- [ ] **Step 4: Verify GREEN**

```powershell
./gradlew.bat test --tests "*ComputerProfileTest" --offline
./gradlew.bat compileJava --offline
```

Expected: both commands end in `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit exact files**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/NodeKind.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerProfile.java
git add src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerProfileTest.java
git commit -m "feat: define strict biological computer profiles"
```

---

## Task 2: Implement Bounded Candidate Enumeration and Strict Structure Snapshots

**Files:** Task 2 ownership set only.

**Produces:** pure scanner/snapshot types. It does not refer to registered blocks; `ComputerWorldView` maps real states in Task 5.

- [ ] **Step 1: RED — write candidate, limit, identity, and non-loading tests**

Define fake cells and exact node observations. Required tests and assertions:

```java
@Test void acceptsThreeByFiveBySevenAndReturnsExactBoundsNodesBusAndChunks();
@Test void acceptsConfiguredSixteenCubedAndUsesSafeVolume4096();
@Test void rejectsConfiguredMinimumMinusOne();
@Test void rejectsConfiguredMaximumPlusOneWithoutReadingPastTheBoundedWindow();
@Test void externalCasingSpurDoesNotExpandTheValidCandidate();
@Test void twoCasingConnectedStructuresAreNotMergedIntoOneComponent();
@Test void nestedCompleteShellsReturnAmbiguousWithNoSnapshot();
@Test void shellHoleReturnsOpenShell();
@Test void computerOnShellIsRejectedBecauseShellRequiresCasing();
@Test void taggedComponentIsAcceptedOnlyInTheInterior();
@Test void illegalInteriorReturnsIllegalInternal();
@Test void isolatedInternalBusReturnsDisconnectedBus();
@Test void thirtyThirdComputerReturnsNodeLimitAtDefaultLimits();
@Test void emptyComputerCountsTowardLimitAndReturnsValidNotReady();
@Test void missingOrDuplicateComputerIdReturnsIdentityInvalidWithoutCoordinator();
@Test void conflictingStructureMemberIdsReturnsStructureIdentityConflict();
@Test void corruptPersistedProfileReturnsIdentityInvalidRatherThanEmpty();
@Test void sameSpaceFailureReturnsSpace();
@Test void independentlyDecodedSnapshotsAreEqualAndHaveEqualHashes();
@Test void changingAnyBoundsNodeCasingOrChunkComponentBreaksEquality();
```

The non-loading test must throw on an illegal call, not merely count it:

```java
@Test
void neverReadsStateOrBlockEntityAfterIsLoadedReturnsFalse() {
    ThrowingView view = sealedCubeWithOneUnloadedRequiredCell();
    ComputerStructureScanner.ScanResult result =
        ComputerStructureScanner.scan(view, SEED, LIMITS);
    assertEquals(ComputerStructureScanner.State.PARTIAL_UNLOADED, result.state());
    assertEquals(0, view.readsAfterUnloaded());
    assertTrue(view.cellReads() <= LIMITS.observationWindowVolume());
}
```

For touching structures, join their shells with one casing spur, scan once from each interior seed, and assert each returned bounds equals its own cuboid. For ambiguity, place a complete `3^3` shell inside a complete `7^3` shell around the same seed **and add one interior casing bridge across the gap from the inner shell to the outer shell**. The bridge leaves the inner candidate's bounds and is ignored there, while it makes the seed Computer's inner casing reachable from the outer shell, so both candidates independently satisfy the bus rule; assert `AMBIGUOUS` rather than scan-order selection.

- [ ] **Step 2: Verify RED**

```powershell
./gradlew.bat test --tests "*ComputerStructureScannerTest" --offline
```

Expected: compilation fails because scanner/snapshot types do not exist.

- [ ] **Step 3: GREEN — define the exact bounded view and state contract**

```java
public interface View {
    boolean isLoaded(BlockPos pos);
    boolean sameSpace(BlockPos origin, BlockPos pos);
    CellKind cellAt(BlockPos pos);
    @Nullable ComputerObservation computerAt(BlockPos pos);
}

public enum CellKind { AIR, CASING, COMPUTER, ALLOWED_INTERNAL, ILLEGAL }
public enum ProfileLoadState { EMPTY, VALID, CORRUPT }

public record ComputerObservation(@Nullable UUID computerId,
    @Nullable UUID computerStructureMemberId, ProfileLoadState profileState,
    @Nullable ComputerProfile profile, @Nullable SpaceAddress address) {}

public enum State {
    UNFORMED, VALID, VALID_NOT_READY, PARTIAL_UNLOADED, SIZE, VOLUME,
    OPEN_SHELL, ILLEGAL_INTERNAL, DISCONNECTED_BUS, NODE_LIMIT, SPACE,
    IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT, AMBIGUOUS
}

public record Limits(int minSize, int maxSize, int maxVolume, int maxNodes) {
    public static Limits fromConfig();
    static Limits fromValues(int configuredMin, int configuredMax, int configuredNodes);
    int observationWindowVolume();
}

public record ScanResult(State state,
    @Nullable ComputerStructureSnapshot snapshot,
    @Nullable UUID observedStructureMemberId) {}
```

`Limits.fromConfig()` calls `fromValues` with the three Foundation config values. `fromValues` performs the clamps in Global Constraints and derives `maxVolume` with checked `long` multiplication. The canonical record constructor also rejects any value outside `3 <= minSize <= maxSize <= 16`, `1 <= maxNodes <= 256`, or any `maxVolume` not exactly equal to the checked cube of `maxSize`, so direct construction cannot smuggle in a fourth volume policy. `observationWindowVolume()` is the checked cube of side `2 * (maxSize - 2) + 1`; it is an algorithmic read-bound, not a config or an accepted candidate volume.

- [ ] **Step 4: GREEN — implement candidate enumeration, never component-derived bounds**

For each axis, enumerate negative/positive shell distances whose resulting inclusive dimension is within the candidate range. Boundary candidates come only from capped axis rays around the seed; no casing-connected-component walk determines min/max. Cross the three axis-interval lists in deterministic `(volume, minX, minY, minZ, maxX, maxY, maxZ)` order.

The candidate set is mathematically capped: one axis has at most `(maxSize - 2) * (maxSize - 1) / 2` interval pairs (`105` at configured maximum `16`), so the cross-product has at most `1,157,625` entries. Build checked prefix-sum volumes over the memoized observation window for unloaded, wrong-space, casing, illegal, and Computer counts. Reject a candidate's six faces/interior/node count with constant-time range sums; only candidates that pass those coarse checks iterate exact cells and run casing flood/identity validation. Stop on the second fully valid candidate. Thus world reads are bounded by the observation window, ordinary candidates are cheap, and casing-dense adversarial input reaches explicit ambiguity rather than an unbounded traversal.

Use a memoized observation window. Each first access follows this exact order:

```java
if (!view.isLoaded(pos)) return Observation.UNLOADED;
if (!view.sameSpace(seed, pos)) return Observation.WRONG_SPACE;
CellKind cell = view.cellAt(pos);
ComputerObservation computer = cell == CellKind.COMPUTER
    ? view.computerAt(pos) : null;
```

Never call `cellAt`/`computerAt` for unloaded or wrong-space coordinates. A candidate's volume must be `<= limits.maxVolume()`. Validate every outer coordinate as `CASING`, then every interior coordinate as one allowed kind. Count every Computer, including empty ones. A Computer cell with missing BE/address/ID, duplicate ID, or `CORRUPT` profile is `IDENTITY_INVALID`; multiple non-null structure member IDs are `STRUCTURE_IDENTITY_CONFLICT`. A legitimate `EMPTY` profile yields a snapshot but final state `VALID_NOT_READY`.

The seed itself must be a loaded same-space interior `COMPUTER` with one valid `ComputerObservation`; otherwise return `UNFORMED` or the exact identity/space reason before enumerating bounds.

After shell/interior validation, flood only `CASING` coordinates inside that exact candidate, seeded by one shell corner. Every Computer must have a six-neighbor in that reached set. Do not enqueue any coordinate outside `bounds`.

Keep at most one fully valid candidate; return `AMBIGUOUS` immediately on the second. A fully valid candidate wins over unrelated incomplete candidates outside its bounds. If no candidate is fully valid and at least one otherwise plausible candidate contains an unloaded required coordinate, return `PARTIAL_UNLOADED`. This prevents an unloaded neighbor outside a complete valid cuboid from pausing it.

The snapshot constructor validates UUID sort/uniqueness, address position within bounds, single dimension/space, exact shell membership in `casingPositions`, node count `<=256`, checked volume `<=4096`, and exact `containingChunks` for the full cuboid. Its NBT compound has exactly `Version`, `Bounds`, `Nodes`, `CasingPositions`, and `ContainingChunks`; version is exact int `1`, bounds is the exact six-int child used by epochs, casing positions are a duplicate-free long array bounded by volume, and containing chunks are a duplicate-free long array equal to the chunks derived from bounds. Each node child has exactly `ComputerId`, `Address`, and optional `Profile`; wrapped `SpaceAddress` accepts only `Dimension`, `Pos`, and optional `SubLevel` before calling the Foundation decoder. Lists are bounded, UUIDs/positions/chunks cannot duplicate, node order must already be canonical, and nullable profiles round-trip without inventing one.

`ComputerStructureSnapshot` is a value object, not an identity object. Override `equals` and `hashCode` over exactly the six bound coordinates, the canonical UUID-sorted `nodes` list, the structural `casingPositions` set, and the structural `containingChunks` set. Never compare the mutable `BoundingBox` instance, serialized NBT bytes, iteration order of either set, or object identity. The test must construct one snapshot, `load(first.save()).orElseThrow()` twice, assert the three instances are distinct references but mutually equal with identical hashes, and then prove that changing each component family independently makes equality false. Because `ComputerStructureRecord` is a record, this contract also makes record equality structural across independently decoded replicas.

- [ ] **Step 5: Verify GREEN and scanner read bounds**

```powershell
./gradlew.bat test --tests "*ComputerStructureScannerTest" --offline
./gradlew.bat test --tests "*ComputerProfileTest" --offline
```

Expected: all cases pass; the throwing fake proves zero reads after `isLoaded=false` and no unique world-state reads beyond the bounded observation window.

- [ ] **Step 6: Commit exact files**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureNode.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureSnapshot.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureScanner.java
git add src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureScannerTest.java
git commit -m "feat: scan bounded computer cuboid candidates"
```

---

## Task 3: Freeze Relocatable Epochs and Monotonic Topology Faults

**Files:** Task 3 ownership set only.

**Consumes:** only strict snapshots/profiles and `SpaceAddress`.

**Produces:** immutable epoch values plus two Runtime-independent seams: read-only quiescence for natural epoch close, and an explicitly stronger stop/clear control for reform.

- [ ] **Step 1: RED — write complete epoch/codec/rigid-move tests**

```java
@Test
void freezeUsesUuidOrderAndRequiresEveryPhysicalNodeProfile() {
    ClusterEpoch epoch = ClusterEpoch.freeze(CLUSTER, STRUCTURE_MEMBER,
        snapshot(node(HIGH, LOW_POS, villager()), node(LOW, HIGH_POS, librarian())));
    assertEquals(LOW, epoch.coordinatorId());
    assertEquals(List.of(LOW, HIGH), epoch.nodes().stream()
        .map(EpochNode::computerId).toList());
    assertThrows(IllegalArgumentException.class, () ->
        ClusterEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshot(emptyNode(EMPTY))));
}

@Test
void rigidRotationUpdatesBoundsCoordinatorAndAddressesWithoutChangingEpoch() {
    ClusterEpoch moved = epoch.relocate(rotatedAndTranslatedSnapshot(epoch));
    assertEquals(epoch.epochId(), moved.epochId());
    assertEquals(epoch.computerStructureMemberId(), moved.computerStructureMemberId());
    assertNotEquals(epoch.bounds(), moved.bounds());
    assertEquals(moved.nodes().stream().filter(n -> n.computerId().equals(LOW))
        .findFirst().orElseThrow().address(), moved.coordinatorAddress());
    assertEquals(epoch.nodes().stream().map(EpochNode::profile).toList(),
        moved.nodes().stream().map(EpochNode::profile).toList());
}
```

Also assert translation succeeds; extra ID is ignored; missing frozen ID, changed profile, reflection, non-rigid displacement, changed dimension, and changed nullable Sable UUID throw; epoch codec rejects duplicate IDs, missing coordinator, mismatched coordinator address, bad bounds, bad profile, wrong keys/types/version; `totalPatternRangeBonus` uses checked addition and sums only frozen trader profiles; and the downstream approved formula gives `min(configuredMax, configuredBase + total)` (defaults `512/64`) without counting pending extras.

- [ ] **Step 2: Verify RED**

```powershell
./gradlew.bat test --tests "*ClusterEpochTest" --offline
```

Expected: compilation fails because epoch types do not exist.

- [ ] **Step 3: GREEN — implement immutable epoch values**

```java
public record EpochNode(UUID computerId, SpaceAddress address,
    ComputerProfile profile) {}

public enum EpochFault { WIDTH, DEPTH }
```

`ClusterEpoch.freeze` rejects an empty snapshot, any null profile, duplicate/missing identity, mixed dimension/space, and a snapshot whose coordinator cannot be the minimum UUID. It generates one random `epochId`, copies nodes in UUID order, and stores the snapshot bounds and minimum-UUID coordinator address.

`relocate` enumerates the 24 determinant-`+1` signed axis permutations. For each, transform the old bounding-box corners, derive the one integer translation aligning the transformed minimum with the new minimum, require transformed bounds equality, and require every frozen ID's old local position to map to its current address. Then require exact dimension/sublevel identity and unchanged frozen profiles. On success copy the current bounds/addresses while preserving all frozen values; on no match throw `IllegalArgumentException`. Extra current IDs are never copied into `nodes`.

The codec uses exact `Version`, `EpochId`, `ClusterId`, `StructureMemberId`, `CoordinatorId`, `CoordinatorAddress`, `Bounds`, and `Nodes` keys with version `1`. Bounds use exactly `MinX`, `MinY`, `MinZ`, `MaxX`, `MaxY`, and `MaxZ`; each node uses exactly `ComputerId`, `Address`, and `Profile`, with the same strict wrapped address/profile codecs as the snapshot. Node lists are `1..256`, already UUID-sorted, and all children are strict.

`EpochQuiescence` contains only the four read-only locked methods above and imports no future Runtime type. `nodeIdle` means no active/local frame or dispatch. It cannot stop or delete work, so `closeIdleEpoch` can be called by the natural last-root completion path without accidentally performing `STOP_ALL_AND_REFORM`. `EpochReformControl` adds only `stopAllAndClear()`; that stronger ownership-transfer seam is the sole route through which future Runtime cancels all roots and removes every frame/mailbox before topology reform. A false probe result leaves the old epoch/latches intact.

- [ ] **Step 4: Verify GREEN**

```powershell
./gradlew.bat test --tests "*ClusterEpochTest" --offline
./gradlew.bat compileJava --offline
```

Expected: all epoch, rotation, rejection, and codec tests pass.

- [ ] **Step 5: Commit exact files**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/EpochNode.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/EpochFault.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/EpochQuiescence.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/EpochReformControl.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ClusterEpoch.java
git add src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ClusterEpochTest.java
git commit -m "feat: freeze rigid computer epochs"
```

---

## Task 4: Register Blocks, Add Atomic Installation, Lossless Removal, and Disjoint NBT

**Files:** Task 4 ownership set and the exact paths listed in the commit step.

**Produces:** registered blocks/BE, public block interaction, lossless resident lifecycle, strict server persistence, and safe client projection. It does not publish a Foundation member yet.

`ComputerInstallResult` contains exactly `SUCCESS`, `NOT_BOX`, `EMPTY_BOX`, `OCCUPIED`, `UNSUPPORTED_ENTITY`, `PASSENGERS_UNSUPPORTED`, `INVALID_IDENTITY`, and `ACTIVE_TOPOLOGY`.

- [ ] **Step 1: RED — write lifecycle transaction tests before production code**

`ComputerResidentLifecycleTest` uses package-private injected `SpawnOps` and `RestoreOps`, not a live world, and asserts exact call counts/state:

```java
@Test void movingRemovalDoesNotDecodeSpawnRecoverOrClear();
@Test void successfulSpawnClearsSourceExactlyOnceAndSetsHealthToOne();
@Test void exactUuidAndTypeCollisionCountsAsConfirmedReleaseWithoutDuplicateSpawn();
@Test void sameUuidDifferentTypeFallsBackToUntouchedRecoveryBox();
@Test void rejectedSpawnProducesOneByteIdenticalRecoveryBox();
@Test void spawnAndRecoveryFailureRestoresByteIdenticalFullServerNbt();
@Test void duplicateCallbackDoesNotSpawnRecoverOrRestoreTwice();
@Test void wanderingTraderDespawnDelayIsUnchangedExceptHealth();
@Test void controlledBreakRollsBackPreparedComputerLootOnDoubleOutputFailure();
@Test void controlledBreakCommitsOnlyAfterLootAndOneResidentOutputAreConfirmed();
@Test void translatedRotatedSubLevelProjectsReleaseRecoveryCollisionFallbackAndLoot();
```

`runRemovalEntry(true, ...)` must prove the moving branch directly and assert zero decode, projection, collision, loot preparation, resident spawn, recovery spawn, restoration, or clear calls. No GameTest claims a normal `setBlock` passes `isMoving=true`.

The projection test copies the established Pattern lifecycle fixture shape: a proxy `SubLevelAccess` with translation `(100, 50, -20)` and a positive 90-degree Y rotation must map local `(2, 3, 4)` to external `(104, 53, -22)`, while a null/main-world sublevel passes the coordinate through. The injected operations capture every target used for resident `moveTo`, moved-`AABB` collision, block-center fallback, recovery `ItemEntity`, and prepared Computer-loot `ItemEntity`; every captured value must be the corresponding projected external coordinate, never the raw local coordinate.

- [ ] **Step 2: RED — write BE installation and NBT projection tests**

Required `ComputerBlockEntityTest` cases:

- wrong item, empty box, baby, unsupported entity, passenger-bearing capture, occupied Computer, a frozen active-epoch member, and corrupt/uncertain identity all return a non-success result and leave both held stack and serialized BE byte-identical;
- an empty current Computer absent from the frozen epoch (a pending hot-add) accepts its one resident even when the epoch has `WIDTH/DEPTH`; installation changes neither epoch membership/order nor latches, marks topology dirty, and a second installation is rejected byte-identically;
- valid install commits exact filled box plus strict profile before `clearCapturedEntity`, empties the same held box only after commit, and never recomputes profile on save/load;
- new BE gets one `computerId`; malformed persisted identity yields `computerId().isEmpty()` and is re-saved as invalid raw data, never replaced by constructor randomness;
- strict round-trip covers computer ID, raw/valid resident, profile, `ComputerStructureRecord` (stable structure member ID + revision + coordinator + snapshot), binding/validity, epoch, and `WIDTH/DEPTH` faults;
- a structure record whose coordinator is absent decodes only beside an exact epoch naming that frozen coordinator; the same record without/mismatching that epoch is persistence-invalid, while remaining valid children/raw data survive;
- one corrupt child does not erase valid siblings; corrupt resident/raw profile is retained exactly and marks the BE unavailable; corrupt profile is never rebuilt from resident NBT;
- server write contains `ComputerData` and no `ComputerClientState`; client write contains exactly `ComputerClientState` and no `ComputerData`;
- malformed client projection is ignored atomically and cannot alter any server field;
- recursive client leak scan rejects `CapturedEntity`, entity `UUID`, `ComputerId`, `CoordinatorId`, `EpochId`, `Offers`, `Recipes`, `Brain`, `Inventory`, `NoAI`, `DespawnDelay`, `ClusterId`, `LogisticsBindings`, `Authority`, `Revision`, `StructureMemberId`, `Epoch`, `Nodes`, `Address`, `Frames`, and `Mailboxes`, plus a unique sensitive string embedded in a trade/custom name.

- [ ] **Step 3: Verify RED**

```powershell
./gradlew.bat test --tests "*ComputerResidentLifecycleTest" --tests "*ComputerBlockEntityTest" --offline
```

Expected: compilation fails because block/BE/lifecycle types do not exist.

- [ ] **Step 4: GREEN — register `computer`, `computer_casing`, items, and BE**

Follow existing `CBBlocks`/`CBItems`/`CBBlockEntityTypes` style:

```java
public static final DeferredHolder<Block, ComputerBlock> COMPUTER =
    BLOCKS.register("computer", () -> new ComputerBlock(
        CBSharedProperties.createSoftMetal().noOcclusion()
            .mapColor(MapColor.COLOR_LIGHT_BLUE)));

public static final DeferredHolder<Block, ComputerCasingBlock> COMPUTER_CASING =
    BLOCKS.register("computer_casing", () -> new ComputerCasingBlock(
        CBSharedProperties.createStone().sound(SoundType.WOOD)
            .mapColor(MapColor.COLOR_LIGHT_BLUE)));
```

Register ordinary `BlockItem`s, one `BlockEntityType<ComputerBlockEntity>`, both creative-tab entries, and pickaxe tags. `ComputerBlockTags.INTERNAL_COMPONENTS` is:

```java
TagKey.create(Registries.BLOCK,
    CreateBiotech.asResource("computer_internal_components"));
```

`ComputerBlock` implements `IBE<ComputerBlockEntity>`, returns the exact registered class/type, and exposes a server-only ticker through `IBE.super.getTicker`; Task 4's BE tick is initially topology-neutral and Task 5 fills it with the 20-tick scan/controller call. The committed JSON is exactly `{"replace":false,"values":[]}` and is the sole source for that custom tag. `CBBlockTagsProvider` only adds Computer and Casing to the existing pickaxe provider.

- [ ] **Step 5: GREEN — implement the actual server-side block interaction**

Override the 1.21.1 block method:

```java
@Override
protected ItemInteractionResult useItemOn(ItemStack ignored, BlockState state,
    Level level, BlockPos pos, Player player, InteractionHand hand,
    BlockHitResult hit) {
    ItemStack held = player.getItemInHand(hand);
    if (!CapturedEntityBoxItem.isBox(held))
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    if (!CapturedEntityBoxHelper.hasCapturedEntity(held))
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    if (level.isClientSide) return ItemInteractionResult.sidedSuccess(true);
    if (!(player instanceof ServerPlayer serverPlayer)
        || !(level.getBlockEntity(pos) instanceof ComputerBlockEntity computer))
        return ItemInteractionResult.FAIL;
    return computer.installResident(serverPlayer, hand) == ComputerInstallResult.SUCCESS
        ? ItemInteractionResult.sidedSuccess(false) : ItemInteractionResult.FAIL;
}
```

`installResident` obtains the stack again from `ServerPlayer + InteractionHand`; requires both `CapturedEntityBoxItem.isBox` and `CapturedEntityBoxHelper.hasCapturedEntity`; and copies the exact filled stack. Before entity creation, inspect `CBItemData.getOrEmpty(copy).get("CapturedEntity")`: it must be a compound with an exact UUID int-array and a registry-resolvable `id` string. A present `Passengers` child must be a list and empty; a wrong type is invalid and a non-empty list returns `PASSENGERS_UNSUPPORTED`. Then call `createCapturedEntityPreservingUuid`, require the decoded UUID/type to equal the raw identity, and call `ComputerProfile.fromEntity(entity, CBConfigs.SERVER.factoryCluster.wanderingTraderRangeBonus.get())`; a cross-level UUID collision or any mismatch is `INVALID_IDENTITY`. It requires an empty, persistence-valid BE. With no epoch it may install normally. With an epoch it may install only when the accepted current structure record contains the local `computerId` but `ClusterEpoch.nodes()` does not: that is the explicit pending hot-add case. A frozen member, an unresolved/stale record, or an identity conflict returns `ACTIVE_TOPOLOGY`; latches do not prohibit pending installation and are not mutated by it. On success assign snapshot/profile together, mark topology dirty for Task 5's next scan/revision, call `setChanged()`/`sendData()`, and only then call `clearCapturedEntity` on the held stack. Any failure serializes identically before/after.

- [ ] **Step 6: GREEN — implement controlled player destruction plus the idempotent forced-removal fallback**

Add `ComputerBreakHandler`, following the committed Pattern-core precedent rather than relying on `onRemove` to influence later vanilla loot:

```java
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class ComputerBreakHandler {
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onBreak(BlockEvent.BreakEvent event) { /* exact flow below */ }
}
```

The handler ignores non-Computer blocks and empty Computers. For an occupied or opaque-resident Computer on the server, it snapshots the state/BE/player main-hand tool, cancels the `BreakEvent` **before** vanilla removal or `playerDestroy`, and calls `ComputerResidentLifecycle.controlledPlayerBreak`. The cancellation is unconditional once ownership is established: success is committed manually under the lifecycle removal guard, while failure retains/restores the occupied Computer. Thus `ServerPlayerGameMode.destroyBlock` has no later vanilla loot path that can duplicate an empty Computer.

The controlled transaction is exact:

1. Copy the untouched filled box or raw resident child and the complete `saveWithoutMetadata` server tag before attempting output. For a survival player, derive the normal empty-Computer loot with `Block.getDrops(oldState, serverLevel, pos, oldBe, player, copiedTool)`; for creative use an empty list. Reject any loot result containing BE/resident data. Wrap every non-empty stack in an `ItemEntity` at the projected Computer center and emit the complete list first. A partial emission is rolled back with `discard()` before returning failure.
2. Attempt resident release with the shared stationary algorithm below. An already-loaded exact UUID/type is confirmed without duplicate spawn; a different type with that UUID cannot count as success. If release is not confirmed, attempt exactly one dedicated recovery `ItemEntity` containing `originalSnapshot.copy()` so the captured-box custom-entity hook cannot silently substitute another entity. Confirm the exact recovery entity was accepted before continuing.
3. If resident release or recovery succeeds, enter the `(level identity, BlockPos)` guard, clear the resident/raw source exactly once, and remove the Computer with `Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS`. Guarded `onRemove` delegates only to `super`; the already-canceled break event cannot call vanilla `playerDestroy`. The previously emitted block loot is therefore the only empty-Computer output.
4. If both resident and recovery outputs fail (or required block loot could not be emitted), discard every prepared/emitted empty-Computer loot entity, call the shared `restoreOccupiedComputer` routine with the copied state/tag, and verify the resident child and complete authoritative Computer data are byte-identical. Usually the pre-remove block is still present and restoration is a verified no-op; if another callback changed it, reconstruct it with `UPDATE_ALL | UPDATE_SUPPRESS_DROPS`. Do not clear resident data. The event remains canceled, so neither world nor player inventory receives empty Computer loot.

`ComputerBlock.onRemove` remains the non-player/command/explosion/replacement fallback. It obtains the old BE before `super`, then delegates to package-private `ComputerResidentLifecycle.onRemove`. The lifecycle copies the untouched resident box/raw resident and full server tag, uses the same thread-local guard, and returns `CALL_SUPER` or `RESTORED`; `ComputerBlock` calls `super.onRemove` only for `CALL_SUPER`. A guarded controlled-success callback never starts a second transaction.

The shared stationary release/recovery algorithm is exact:

1. If no resident/raw snapshot, clear nothing and call super. If only opaque corrupt raw resident data exists, do not invent an entity or recovery `ItemStack`; retain/reconstruct the full occupied Computer.
2. Otherwise read expected UUID and registry `EntityType` from the copied box before entity creation. Resolve that UUID across every loaded server level first. Accept only an exact UUID/type collision as an already-confirmed release, setting that living entity to `1.0F`; a same-UUID/different-type collision skips to recovery.
3. With no collision, decode via `createCapturedEntityPreservingUuid`, require exact identity, and try local centers in deterministic order `UP, NORTH, SOUTH, WEST, EAST, DOWN`. Each local coordinate is checked loaded and same-space. Convert it using the one helper `entityWorldPosition(level, computerPos, localCenter)`, compute the moved collision `AABB` against that **external** target, and only then call `moveTo` with the same target. If no neighbor qualifies, the local Computer center is the fallback target and is projected through the same helper before collision/spawn. Set living health to `1.0F`; preserve all other data including wandering-trader despawn delay. Confirm the exact UUID/type after the sink accepts it; discard an unconfirmed temporary entity before recovery.
4. If release is not confirmed, construct one dedicated recovery `ItemEntity` at `entityWorldPosition(level, computerPos, Vec3.atCenterOf(computerPos))` containing `originalSnapshot.copy()`. Clear only after that exact entity is accepted. If both outputs fail, `restoreOccupiedComputer` reconstructs and reloads the byte-identical full server tag and suppresses ordinary drops.

The only entity-coordinate helper is:

```java
private static Vec3 entityWorldPosition(Level level, BlockPos computerPos,
    Position localCenter) {
    return SubLevelCompat.toWorld(level, computerPos, localCenter);
}

// Package-private only for the focused translated/rotated SubLevelAccess test.
static Vec3 entityWorldPosition(@Nullable SubLevelAccess subLevel,
    Position localCenter) {
    return SubLevelCompat.toWorld(subLevel, localCenter);
}
```

No resident, recovery, collision, fallback, or prepared-loot path may construct/spawn/test at a raw local center. Snapshot/raw state is cleared only after confirmed resident/recovery output. A second callback observes the guard or cleared source and emits nothing. The loot table drops exactly one empty Computer and has no copy-NBT function.

- [ ] **Step 7: GREEN — implement strict server/client NBT**

`ComputerStructureRecord` is versioned and strict:

```java
public record ComputerStructureRecord(UUID computerStructureMemberId,
    long revision, UUID coordinatorId, ComputerStructureSnapshot snapshot) {}
```

It requires `revision >= 0`, non-null identities, exact key/type set, and strict child decode. The record codec deliberately permits `coordinatorId` to be absent from its current snapshot because that is the persisted active-epoch coordinator-loss state; the containing BE cross-child validator permits that absence only when an attached epoch names the same frozen coordinator. Without such an epoch, absence is invalid.

Its exact version-1 key set is `Version`, `StructureMemberId`, `Revision`, `CoordinatorId`, and `Snapshot`; revision is an exact non-negative long and all UUIDs use the exact int-array representation.

`ComputerBlockEntity.write/read(..., boolean clientPacket)` branches before any Computer field is written. The server calls `super.write/read(..., false)` and owns one `ComputerData` compound. Its only allowed child names are `Version`, `ComputerId`, `Resident`, `Profile`, `Structure`, `Binding`, `BindingInvalid`, `Epoch`, and `Faults`; `Version`, `ComputerId`, `BindingInvalid`, and `Faults` are required, while the other five are optional. Version is exact int `1`, a valid ID is the exact vanilla UUID int-array form, `BindingInvalid` is a canonical byte boolean, and `Faults` is a duplicate-free list containing only `WIDTH`/`DEPTH`. `Resident`, `Profile`, `Structure`, `Binding`, and `Epoch` must be compounds when valid. The Computer wrapper first rejects extra/mistyped nested keys around the existing Foundation binding decoder, so permissive `ClusterBinding.tryLoad` cannot make Computer persistence permissive.

On a corrupt server child, keep that raw `Tag` under its original allowed key when re-saving, mark the corresponding decoded field/persistence state invalid, and continue decoding independent valid siblings. On an unknown root key, retain the entire raw `ComputerData` in memory for exact server re-save and expose no decoded topology; never normalize it into a valid record. Thus malformed identity remains absent and is never replaced by constructor randomness, while opaque resident data remains available to the reconstruction path. Cross-child validation rejects inconsistent resident/profile presence, epoch/structure-member or epoch/binding-cluster mismatch, local `computerId` absent from a claimed snapshot, record-coordinator absence without an exact epoch naming that coordinator, and fault-without-epoch as unavailable without deleting recoverable children. A binding authority of type `COMPUTER_COORDINATOR` must name this stable `computerStructureMemberId`; `PANEL`/`PATTERN_CORE` authorities remain legal and are not falsely compared with the Computer identity.

Client write emits only strict `ComputerClientState`:

```java
public record ComputerClientState(boolean renderResident,
    @Nullable NodeKind kind, int slots, int depth,
    ComputerDisplayState displayState) {}
```

The client root contains exactly one `ComputerClientState` compound and does not call the server/super write branch. Its only keys are required `Version`, `RenderResident`, `Slots`, `Depth`, `DisplayState` and conditional `Kind`; `Version` is exact int `1`, the boolean byte is canonical, `Kind` is present exactly when a strict rendered profile is present, and unknown/mistyped keys reject the whole projection. It accepts only `IDLE`, `RUN`, or `SLP` and bounds numeric fields by the approved table. `read(clientPacket=true)` changes only this projection and leaves all server fields byte-identical. `sendData()` therefore cannot leak server data.

- [ ] **Step 8: Add exact resources and connected-texture registration**

Create the following exact resources:

- blockstates: `computer.json`, `computer_casing.json`;
- block models: Computer parent `create:block/stock_ticker`; Casing `cube_all` using `create_biotech:block/biotech_casing`;
- item models parent their block models;
- both loot tables drop self, with Computer containing no copy-NBT function;
- static empty internal-component tag;
- English/Chinese block and item translations.

In `CreateBiotechClient`, register `COMPUTER_CASING` with `CreateClient.CASING_CONNECTIVITY.makeCasing(..., CBSpriteShifts.BIOTECH_CASING)` and the custom block model with `new CTModel(model, new EncasedCTBehaviour(CBSpriteShifts.BIOTECH_CASING))` before the initial model bake, matching existing biotech casing registration.

- [ ] **Step 9: Verify GREEN, resources, and generated mining tags**

```powershell
./gradlew.bat test --tests "*ComputerResidentLifecycleTest" --tests "*ComputerBlockEntityTest" --offline
./gradlew.bat runData --offline
./gradlew.bat build --offline
$jsonPaths = @(
  'src/main/resources/assets/create_biotech/blockstates/computer.json',
  'src/main/resources/assets/create_biotech/blockstates/computer_casing.json',
  'src/main/resources/assets/create_biotech/models/block/computer.json',
  'src/main/resources/assets/create_biotech/models/block/computer_casing.json',
  'src/main/resources/assets/create_biotech/models/item/computer.json',
  'src/main/resources/assets/create_biotech/models/item/computer_casing.json',
  'src/main/resources/data/create_biotech/loot_table/blocks/computer.json',
  'src/main/resources/data/create_biotech/loot_table/blocks/computer_casing.json',
  'src/main/resources/data/create_biotech/tags/block/computer_internal_components.json',
  'src/main/resources/assets/create_biotech/lang/en_us.json',
  'src/main/resources/assets/create_biotech/lang/zh_cn.json'
)
foreach ($path in $jsonPaths) {
  if (-not (Test-Path -LiteralPath $path)) { throw "Missing resource: $path" }
  Get-Content -Raw -LiteralPath $path | ConvertFrom-Json | Out-Null
}
$computerModel = Get-Content -Raw src/main/resources/assets/create_biotech/models/block/computer.json | ConvertFrom-Json
if ($computerModel.parent -ne 'create:block/stock_ticker') { throw 'Wrong Computer model parent' }
$casingModel = Get-Content -Raw src/main/resources/assets/create_biotech/models/block/computer_casing.json | ConvertFrom-Json
if ($casingModel.textures.all -ne 'create_biotech:block/biotech_casing') { throw 'Wrong casing texture' }
if (-not (Test-Path src/main/resources/assets/create_biotech/textures/block/biotech_casing.png)) { throw 'Missing casing texture' }
$createVersion = ((Select-String '^create_version=' gradle.properties).Line -split '=', 2)[1]
$createJar = Get-ChildItem "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.simibubi.create/create-1.21.1/$createVersion" -Recurse -Filter "create-1.21.1-$createVersion.jar" | Select-Object -First 1
if ($null -eq $createJar) { throw 'Resolved Create runtime jar is missing' }
$createEntries = & jar tf $createJar.FullName
if ($createEntries -notcontains 'assets/create/models/block/stock_ticker.json') { throw 'Create stock_ticker parent is missing' }
if (Test-Path src/generated/resources/data/create_biotech/tags/block/computer_internal_components.json) { throw 'Custom internal tag has two authorities' }
$pickaxe = Get-Content -Raw src/generated/resources/data/minecraft/tags/block/mineable/pickaxe.json | ConvertFrom-Json
foreach ($id in @('create_biotech:computer', 'create_biotech:computer_casing')) {
  if (@($pickaxe.values | Where-Object { $_ -eq $id }).Count -ne 1) { throw "Pickaxe tag must contain $id exactly once" }
}
git diff --check
```

Expected: all pass. The script parses every owned JSON, resolves the local texture and dependency model parent, proves each mining-tag entry occurs exactly once, and proves `runData` did not generate a second custom internal-component tag. Task 7's public destroy/release GameTest is the final runtime loot-table/data-pack resolution gate.

- [ ] **Step 10: Commit exact files (one path per add; no directory adds)**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockTags.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerDisplayState.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerClientState.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerInstallResult.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureRecord.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlock.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBreakHandler.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerCasingBlock.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockEntity.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerResidentLifecycle.java
git add src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockEntityTest.java
git add src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerResidentLifecycleTest.java
git add src/main/java/com/nobodiiiii/createbiotech/registry/CBBlocks.java
git add src/main/java/com/nobodiiiii/createbiotech/registry/CBItems.java
git add src/main/java/com/nobodiiiii/createbiotech/registry/CBBlockEntityTypes.java
git add src/main/java/com/nobodiiiii/createbiotech/registry/CBCreativeModeTabs.java
git add src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java
git add src/main/java/com/nobodiiiii/createbiotech/client/CreateBiotechClient.java
git add src/generated/resources/data/minecraft/tags/block/mineable/pickaxe.json
git add src/main/resources/assets/create_biotech/blockstates/computer.json
git add src/main/resources/assets/create_biotech/blockstates/computer_casing.json
git add src/main/resources/assets/create_biotech/models/block/computer.json
git add src/main/resources/assets/create_biotech/models/block/computer_casing.json
git add src/main/resources/assets/create_biotech/models/item/computer.json
git add src/main/resources/assets/create_biotech/models/item/computer_casing.json
git add src/main/resources/data/create_biotech/loot_table/blocks/computer.json
git add src/main/resources/data/create_biotech/loot_table/blocks/computer_casing.json
git add src/main/resources/data/create_biotech/tags/block/computer_internal_components.json
git add src/main/resources/assets/create_biotech/lang/en_us.json
git add src/main/resources/assets/create_biotech/lang/zh_cn.json
git commit -m "feat: add lossless villager computers and casing"
```

Before committing, `git diff --cached --name-only` must equal this list exactly.

---

## Task 5: Coordinate Stable Structure Replicas, Epoch Membership, and Public Views

**Files:** Task 5 ownership set only.

**Produces:** scan application, stable structure identity/revision, loaded-vs-destroyed election, active-epoch pending/missing semantics, monotonic fault reconciliation, fault-free natural epoch close, explicit reform, locator, and Runtime/UI accessors. It still does not publish to Foundation.

- [ ] **Step 1: RED — write topology transition tests with real `ComputerBlockEntity` instances and a fake loaded resolver**

Required arrangements/assertions:

```java
@Test void firstFullyLoadedValidScanCreatesOneRandomStructureMemberIdAndReplicatesIt();
@Test void oneExistingStructureIdClaimsNullNewMembersButTwoExistingIdsFault();
@Test void lowestComputerUuidCoordinatesRegardlessOfBlockPosition();
@Test void legitimateEmptyComputerFormsValidNotReadyWithoutElectingOrStartingEpoch();
@Test void missingDuplicateOrCorruptIdentityDoesNotElectOrOverwritePersistedData();
@Test void knownChunkUnloadRetainsSnapshotCoordinatorAndRevisionWithoutReelection();
@Test void fullyLoadedCoordinatorDestructionReelectsOnlyWhenNoEpochNoFaultAndQuiescent();
@Test void lowerUuidActiveHotAddIsPendingAndDoesNotChangeRecordCoordinatorFrozenOrderOrRangeBonus();
@Test void emptyPendingHotAddCanInstallOnceWithoutChangingEpochOrFaults();
@Test void activeEpochMissingNonCoordinatorKeepsCoordinatorAndMarksOnlyThatFrozenNodeOffline();
@Test void activeEpochMissingCoordinatorGoesOfflineAndNeverReelects();
@Test void activeEpochRigidMoveUpdatesEpochBoundsAndCoordinatorAddress();
@Test void partialOrOpenShellRetainsLastActiveSnapshotWithDifferentReasons();
@Test void widthDepthUnionSurvivesStaleReplicaLoadCancellationAndNbtRoundTrip();
@Test void equalRevisionIndependentlyDecodedSnapshotReplicasAreAccepted();
@Test void naturalLastRootCompletionClosesIdleFaultFreeEpochAtomically();
@Test void staleEpochCloseDoesNotProbeScanOrMutate();
@Test void widthOrDepthRejectsNormalCloseAsRequiresReform();
@Test void unloadedOrSpaceUncertainMemberRefusesNormalCloseByteIdentically();
@Test void missingFrozenMemberCannotBeAbandonedByNormalClose();
@Test void nonIdleRootOwningOrNonemptyMailboxMemberRefusesNormalClose();
@Test void emptyPendingExtraCanCloseButLeavesInactiveStructureNotReady();
@Test void coordinatorDestroyedAfterCloseReelectsAndPreservesStructureIdentity();
@Test void onlyVerifiedStopAllReformClearsLatchedFaultsOnEveryReplica();
@Test void staleEpochReformRequestDoesNotCallStopOrMutateTheNewEpoch();
@Test void confirmedPermanentFrozenMemberLossReformsWithNewEpochAndSameStructureIdentity();
@Test void unloadedOrSpaceUncertainFrozenMemberCannotReformOrClearFaults();
@Test void failedReformLeavesEveryReplicaByteIdentical();
@Test void accessorsReturnImmutableCurrentFrozenAndPendingViewsWithPendingFlag();
```

The normal-close fixture starts an epoch, simulates natural completion of the last root without calling any stop/clear method, supplies a fully loaded same-space current production snapshot plus true read-only quiescence for the frozen/current union, and proves every replica loses only epoch/fault state while stable structure identity and binding remain exact. The stale-ID fixture asserts zero quiescence and world-probe calls. Separate fixtures cover each root/node predicate, unioned faults, unloaded proof-domain cells, wrong/uncertain space, missing frozen identity, equal decoded replica records, and a later fully loaded inactive coordinator destruction/re-election. Task 6 owns the first compiling Foundation rebind assertion because its adapter/service types do not exist in Task 5.

The reform success fixture records that `stopAllAndClear()` is called first and returns true, then returns true for `allRootsStopped` and, after the current scan is known, idle/root-free/mailbox-empty for the union of every still-present frozen UUID and every current UUID (including pending extras). Separate fixtures make each global/per-node check false. Topology/epoch/latches remain byte-identical on failure, although a successful first-stage Runtime stop remains stopped as required. One fixture removes a frozen node from a fully loaded same-space valid scan and proves reform creates a new epoch; another makes one relevant chunk unavailable and proves the old epoch/latches remain.

- [ ] **Step 2: RED — write bounded/non-loading locator tests**

The locator fake throws if it receives a state/BE read after unloaded/same-space failure. Tests cover: unique clicked shell casing -> coordinator; clicking an external spur -> empty while clicking that structure's shell still resolves; two nearby unrelated structures -> only the one whose snapshot contains the clicked casing; ambiguous clicked casing -> empty; unloaded candidate -> empty without loading; Sable-space mismatch -> empty; a stored snapshot and rescanned snapshot that are distinct independently decoded but structurally equal objects -> coordinator; and a move/unload race where any one structural component/address/ID/member changes before revalidation -> empty.

- [ ] **Step 3: Verify RED**

```powershell
./gradlew.bat test --tests "*ComputerTopologyControllerTest" --tests "*ComputerStructureLocatorTest" --offline
```

Expected: compilation fails because topology/locator types do not exist.

- [ ] **Step 4: GREEN — map the real world without loading or bypassing the tag**

`ComputerWorldView` maps states in this order: Casing, Computer, air, `state.is(ComputerBlockTags.INTERNAL_COMPONENTS)`, illegal. Before block state access it uses `CBMultiBlockLifecycle.isLoaded`; before BE access it uses `SubLevelCompat.getLoadedBlockEntity`; every position must satisfy `SubLevelCompat.sameSpace(level, seed, pos)`. Its node observation distinguishes legitimate `EMPTY` from persisted `CORRUPT` and captures `SpaceAddress` only after validation.

Expose a package-private classifier accepting the tag predicate so a unit test can mark a real vanilla state as tagged and prove it is accepted in the interior but still rejected on the shell. The shipped tag remains empty.

- [ ] **Step 5: GREEN — implement stable replica identity and revision rules**

Only the provisional minimum-`computerId` BE may apply a fully loaded scan. It gathers the exact loaded BEs from the snapshot and validates object identity/address again. Identity resolution is deterministic:

- all structure IDs null: generate one UUID once on the provisional coordinator and commit it plus revision `0` to the exact loaded set;
- exactly one non-null ID: assign it to null members; never replace a different non-null ID;
- more than one non-null ID, missing/corrupt computer ID/profile metadata, or incompatible same-revision records: set `IDENTITY_CONFLICT`, preserve raw/persisted fields, elect/publish nothing;
- a lower-revision replica may adopt the verified coordinator record; equal-revision records whose independently decoded snapshots are structurally equal are the same value and are accepted, while an equal structurally divergent or higher non-coordinator revision is a conflict, not a tick-order winner. All comparison uses `ComputerStructureSnapshot.equals`, never object identity or serialized-tag equality.

Every accepted topology change is committed as one immutable `ComputerStructureRecord` to all exact loaded members with checked `revision + 1`. Address-only rigid relocation also increments the structure revision. Partial/unloaded and invalid scans never overwrite the last record.

Record coordinator selection is epoch-aware: without an epoch the candidate is the minimum current physical UUID; during an epoch `ComputerStructureRecord.coordinatorId` remains exactly `ClusterEpoch.coordinatorId`, even if a pending extra has a lower UUID or the frozen coordinator is missing. A pending/remaining minimum may own scan application but does not thereby become coordinator. While an epoch is active, only successful reform changes it to a replacement epoch's new current-minimum coordinator; successful normal close may write the current minimum only as part of its atomic transition to no epoch.

- [ ] **Step 6: GREEN — implement active-epoch membership and reasoned availability**

Use a reason enum, never mutable booleans:

```java
NONE, NOT_READY, PARTIAL_UNLOADED, STRUCTURE_INVALID, AMBIGUOUS,
IDENTITY_CONFLICT, PERSISTENCE_INVALID, FROZEN_MEMBER_MISSING,
COORDINATOR_MISSING, AUTHORITY_OFFLINE
```

`structureOnline()` and `coordinatorOnline()` derive from current reason plus per-node resolution. No `setRuntimeOffline(boolean)` exists.

The derived truth table is fixed: `NONE` yields `true/true`; `FROZEN_MEMBER_MISSING` yields `true/true` while the absent node alone is offline; `AUTHORITY_OFFLINE` yields `true/false` because the physical topology is usable but cannot accept authority-dependent new work; every other reason yields `false/false`. For `NONE`, a frozen/current node is online only when its exact ID/address/profile resolves; a profiled pending extra may report physically online but is never schedulable from the frozen list. Any globally-offline reason projects every frozen node offline even if its chunk is individually loaded.

`FROZEN_MEMBER_MISSING` is degraded rather than globally offline: `coordinatorOnline()` and `structureOnline()` remain true when the shell and frozen coordinator are valid, the missing node's `ComputerNodeView.online` is false, and independent frames on other loaded nodes may continue. `COORDINATOR_MISSING`, `PARTIAL_UNLOADED`, identity/persistence conflict, and invalid shell are globally offline.

`NOT_READY` applies to a no-epoch structure containing any legitimate empty Computer. During an active epoch, legitimate null profiles exclusively on pending extras do not replace `NONE`/the existing epoch reason; those extras alone are `online=false` until installed, while frozen-member availability is unchanged.

Before an ordinary rescan, if any chunk in the last accepted snapshot's `containingChunks()` is unavailable, set `PARTIAL_UNLOADED`, retain record/epoch/coordinator exactly, and do not scan a shrunken visible subset. A never-formed structure may scan bounded candidates, but cannot accept a candidate containing an unavailable required coordinate.

With no epoch/fault, a fully loaded valid rescan may elect the minimum current UUID after it proves the old coordinator block/ID absent. There is no generic Runtime callback here: the locked invariant is that every future root/frame/mailbox is owned by an epoch, so the absence of an epoch is the persisted proof that ordinary re-election has no active Runtime work. Future Runtime must diagnose any work-without-epoch save as `PERSISTENCE_INVALID`, not ask this controller to elect. Missing known chunks always preserve the old coordinator. With an epoch:

- `currentIds` may be a strict superset; extras are pending and never enter `ClusterEpoch.nodes()`;
- a pending extra may have a null profile and is then shown `pending=true, online=false`; it does not make the frozen epoch unavailable. Its public resident installation is allowed exactly once, after which the next accepted scan shows its physical current view as `pending=true, online=true` but still leaves the epoch and latches byte-identical and grants no current-epoch capacity;
- each pending extra adopts the same stable structure record/binding and the unioned epoch-fault set, so redundant persistence cannot be weakened even though the extra supplies no current-epoch capacity;
- if all frozen IDs exist, call `epoch.relocate(currentSnapshot)` and propagate the moved epoch;
- if a non-coordinator frozen ID is missing, retain its old epoch node/address, mark that node offline, keep the coordinator, and do not substitute a pending node;
- if the frozen coordinator is missing/mismatched, set `COORDINATOR_MISSING`, unregister/no-publish later in Task 6, and do not elect;
- partial load retains record/epoch with `PARTIAL_UNLOADED`; a fully loaded hole/bus fault retains them with `STRUCTURE_INVALID`.

Accordingly, an active-epoch `VALID_NOT_READY` scan is accepted only when every null profile belongs to a current ID outside the frozen set and every frozen ID/profile still matches. Any null/corrupt frozen profile is `PERSISTENCE_INVALID`. Without an epoch, `VALID_NOT_READY` may form and the minimum UUID may act only as the provisional scan/replication owner, but `coordinatorOnline()` is false, no Foundation adapter is published, and no epoch may start until the scan becomes `VALID`. The persisted record may name that deterministic candidate; it is not an elected external coordinator yet. Pending readiness never contributes slots, depth, trader range, or online epoch capacity.

`startEpoch(clusterId)` requires elected coordinator, fully loaded `VALID` snapshot, stable structure identity, strict profile on every physical Computer, no existing epoch/fault, and matching valid binding cluster. It freezes and redundantly commits the exact epoch.

`latchEpochFault(expectedEpochId, WIDTH|DEPTH)` may be called on any loaded frozen member; it is not coordinator-only, but it requires the caller's exact epoch ID and returns `EPOCH_MISMATCH` for a stale frame. It is monotonic and unions the fault into every loaded replica with that same epoch. Replica reconciliation always unions same-epoch fault sets, so a stale empty replica can never clear a fault. Ordinary scan and cancellation APIs have no clear path.

`closeIdleEpoch(expectedEpochId, quiescence)` is the normal, fault-free epoch-close transaction used after natural completion of the last root. It is independent of every future Runtime class and accepts only the Task-3 read-only `EpochQuiescence` seam. It never calls `stopAllAndClear`, never abandons an old ID, and never clears a latched topology fault. It may be invoked through any loaded exact replica, is deduplicated by `(computerStructureMemberId, expectedEpochId)`, and runs in this exact order:

1. From the invoking replica, require an active epoch and exact `expectedEpochId`. Return `NO_EPOCH`/`EPOCH_MISMATCH` before any quiescence, scanner, locator, or world probe; a delayed last-root callback cannot close a replacement epoch.
2. If the invoking replica already carries `WIDTH` or `DEPTH`, return `REQUIRES_REFORM` before any quiescence/world probe. Otherwise establish the proof domain as the union of the persisted structure-record bounds/chunks, epoch bounds and every frozen address, and the candidate current bounds. For every known coordinate/chunk, require loaded before state/BE access and exact root dimension plus exact nullable Sable-space identity. Run the production bounded non-loading scan and require one `VALID` or `VALID_NOT_READY` snapshot with the same `computerStructureMemberId` and no identity conflict. A `VALID_NOT_READY` result is eligible only when every null profile belongs to a current pending extra outside the frozen set; any null/corrupt frozen profile is `PROFILE_NOT_READY`. `PARTIAL_UNLOADED`, `SPACE_UNCERTAIN`, `STRUCTURE_INVALID`, and `IDENTITY_CONFLICT` map to their exact result without mutation.
3. Resolve every frozen UUID to an exact loaded current node/address/profile. Normal close interprets “surviving frozen members” as the complete frozen membership required by approved epoch closure: a missing ID returns `FROZEN_MEMBER_MISSING` and must use `STOP_ALL_AND_REFORM` if permanent destruction is later proved. It may not be silently dropped by normal close. Current pending extras are included in the staged replica/quiescence set but never retroactively add capacity to the closing epoch.
4. Gather the exact loaded BE object set for the union of frozen and current IDs. Union the persisted same-epoch fault sets across those replicas. Any `WIDTH` or `DEPTH` returns `REQUIRES_REFORM` before quiescence and leaves epoch/latches unchanged; neither a stale fault-free replica nor ordinary completion can erase the union.
5. Require `quiescence.allRootsStopped()`. False is `ROOTS_REMAIN` with byte-identical replicas. Then require `nodeIdle`, `nodeRootFree`, and `nodeMailboxEmpty` for every UUID in that union. Any false value is `RUNTIME_NOT_QUIESCENT`. These are observations only: the Computer layer has no stop/clear capability through this interface. They explicitly prove all frozen members online/idle/root-free/mailbox-empty and also prevent a pending node from carrying orphan state into the inactive topology.
6. Stage, then revalidate on the same server tick: exact BE object/address set, expected epoch ID, structural snapshot equality, structure ID, binding bytes, profiles, fault union, loaded status, and same-space identity. Derive the inactive coordinator as the minimum current physical UUID. Preserve `computerStructureMemberId` and every replica's exact valid binding. If current snapshot/coordinator differs from the record, write one checked `revision + 1` record; otherwise retain the record revision. Atomically set `Epoch=null` and `Faults=[]` on every staged current replica and publish dirty/sync only after the entire stage succeeds. There is no partial-success branch.

On `CLOSED`, the structure is genuinely inactive. A fully profiled current snapshot lets Task-6 binding preparation succeed immediately; a pending empty extra instead yields the existing no-epoch `NOT_READY` state until public installation completes. A later fully loaded coordinator destruction follows the ordinary no-epoch minimum-UUID/provisional-owner rule while retaining the same stable `computerStructureMemberId` and binding. Every refusal retains epoch, faults, record, coordinator, binding, and resident bytes exactly. The test named `naturalLastRootCompletionClosesIdleFaultFreeEpochAtomically` must record that no `EpochReformControl`/stop callback exists or runs.

`stopAllAndReform(expectedEpochId, control)` may be invoked through any loaded replica carrying the exact stable structure identity (this is required when the old coordinator was destroyed). Before starting the transaction, reject `NO_EPOCH`/`EPOCH_MISMATCH` without calling the control; this prevents a delayed UI command for an abandoned epoch from stopping new work. The controller then deduplicates the matching request and lets the minimum current valid `computerId` stage it. It is the sole exception to ordinary active-epoch no-election rules and runs in this exact order after that identity/epoch entry check:

1. Call `control.stopAllAndClear()` first and require `true` plus `allRootsStopped()`. This operation owns the global termination/removal of every root, frame, and mailbox; no other API may perform a topology clear.
2. Before scanning, require every chunk intersecting the persisted structure-record bounds, old epoch bounds, and every frozen address to be loaded, and require every checked old coordinate to have the exact root dimension/Sable identity. If an old relevant coordinate is unloaded or space identity is uncertain, retain the old epoch and fault set offline.
3. Run the production bounded scanner from the requesting replica using its normal non-loading observation protocol and require exactly one current `VALID` snapshot with the same stable `computerStructureMemberId`, no identity conflict, and a strict profile on every current Computer. Then require every chunk in that snapshot's `containingChunks()` loaded and every snapshot coordinate same-space. Scanner `PARTIAL_UNLOADED` maps to `PARTIAL_UNLOADED`; any wrong/uncertain space maps to `SPACE_UNCERTAIN`.
4. The loaded proof domain is now the union of the persisted record, old epoch bounds/frozen addresses, and accepted current bounds. Partition old frozen IDs into present and missing. Present IDs must resolve to the exact current BE/profile. A missing ID is considered permanently destroyed only because that whole domain is loaded and same-space and the valid production scan conclusively contains no Computer with that ID. A merely unresolved/unloaded ID never enters this set.
5. Now that the complete current set is known, require `nodeIdle`, `nodeRootFree`, and `nodeMailboxEmpty` for the union of all present old-frozen IDs and all current IDs, including pending extras. Any false result is `RUNTIME_NOT_QUIESCENT` and retains the old epoch/latches; the successful global stop remains in effect.
6. Freeze a replacement `ClusterEpoch` from the current snapshot using the old `clusterId` and unchanged `computerStructureMemberId`. This creates a new random `epochId`, current membership/profile set, and current minimum-UUID coordinator. Pending current nodes therefore become normal members of the replacement epoch.
7. Stage the replacement epoch plus an empty fault set and one incremented `ComputerStructureRecord` revision for every current replica; commit without revalidation on the same server tick. Removed IDs receive no write because their permanent absence was proven. The new coordinator may publish the same Foundation structure identity in Task 6.

If Steps 1–6 fail, do not clear or replace the topology epoch/faults. Runtime work successfully stopped in Step 1 remains stopped; the old epoch remains offline and retryable. Ordinary cancellation, hot-add, rescan, unload, or restart never calls this transaction and cannot clear a latch.

- [ ] **Step 7: GREEN — implement bounded locator and revalidation**

Task 5 exposes only package-private `ComputerStructureLocator.findCoordinatorEntity(ServerLevel, BlockPos casingPos, Limits) -> Optional<ComputerBlockEntity>` so it compiles without Task 6's adapter class. It does not flood an arbitrary casing component. It enumerates only positions in the checked cube whose radius is `limits.maxSize() - 1`, and for each coordinate executes loaded -> same-space -> `SubLevelCompat.getLoadedBlockEntity`. Each discovered Computer is scanned; results are deduplicated by exact space+bounds+structure member ID; the clicked position must be in `snapshot.casingPositions()`. More than one matching valid structure returns empty.

Resolve the returned coordinator through its `SpaceAddress`, then recheck: exact `ComputerBlockEntity`, exact `computerId`, exact current address, exact `computerStructureMemberId`, coordinator ID in its current record, and current snapshot structurally equal to the candidate through `ComputerStructureSnapshot.equals`. Distinct decoded instances are expected and accepted; a difference in any bound/node/casing/chunk component, any other mismatch, or unload returns empty. The search reads at most `(2 * (maxSize - 1) + 1)^3` candidate coordinates and never calls a loading `getChunk` path.

- [ ] **Step 8: Verify GREEN and all prior units**

```powershell
./gradlew.bat test --tests "*ComputerTopologyControllerTest" --tests "*ComputerStructureLocatorTest" --offline
./gradlew.bat test --tests "*ComputerProfileTest" --tests "*ComputerStructureScannerTest" --tests "*ClusterEpochTest" --offline
./gradlew.bat compileJava --offline
```

Expected: all pass.

- [ ] **Step 9: Commit exact files**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerAvailabilityReason.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerNodeView.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerClusterView.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerWorldView.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerTopologyController.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureLocator.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockEntity.java
git add src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerTopologyControllerTest.java
git add src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureLocatorTest.java
git commit -m "feat: coordinate persistent computer topology"
```

---

## Task 6: Publish One Stable Foundation Coordinator and Bind Through Casing

**Files:** Task 6 ownership set only.

**Consumes:** exact public Foundation APIs at `b72b099b`: `ClusterBindingSelection.resolve(ServerPlayer)`, `ClusterBindingService.bind(ServerPlayer, ClusterMember, ClusterMember)`, `ClusterBindingService.bindingAccess(MinecraftServer, ClusterMember)`, and `ClusterMemberIndex.register/unregister/rebind`.

**Produces:** one external Foundation identity per valid structure and atomic internal binding propagation.

- [ ] **Step 1: RED — test adapter identity, no-fail propagation, and publication lifecycle**

`ComputerCoordinatorMemberTest` uses real Computer BEs and asserts:

- `memberId()` is the shared `computerStructureMemberId`, while `coordinatorComputerId()` is the elected physical `computerId` and they differ;
- non-coordinator BEs are not `ClusterMember` and never register as `COMPUTER_COORDINATOR`;
- a no-epoch `VALID_NOT_READY` structure has a provisional record owner but no adapter/index member until every Computer is profiled;
- prepare rejects partial/invalid identity, epoch/fault, missing replica, capacity, stale/equal-divergent revision, and an unready authority without mutating any replica;
- successful prepare followed by commit writes the same immutable `ClusterBinding` to every exact replica and cannot fail/rescan;
- an active fault-free epoch rejects binding, then a successful natural `closeIdleEpoch` preserves the old binding/structure ID and allows the real public binding transaction to commit a new binding on every replica;
- a lower-revision internal replica adopts only the Foundation-READY coordinator state; it never publishes over the coordinator;
- coordinator unload unregisters; a non-coordinator cannot replace it while known chunks are unavailable; same identity reload republishes and reconciles;
- fully loaded coordinator destruction **after that normal close** republishes the same structure-member ID from the new minimum-UUID BE; active-epoch destruction publishes no replacement;
- same-cluster second Computer structure is reported as Foundation computer conflict.

- [ ] **Step 2: RED — test real Foundation participants, not only fakes**

In package `com.nobodiiiii.createbiotech.content.factorycluster`, construct real `FactoryPanelBlockEntity`, `PatternStorageCoreBlockEntity`, and Computer coordinator/replicas using the existing Minecraft bootstrap fixture. Drive Foundation's package-visible pure bind transaction with the exact participant set and assert revision `0 -> 1 -> 2`, authority transfer `PANEL -> PATTERN_CORE -> COMPUTER_COORDINATOR(structureMemberId)`, exact binding propagation to every Computer, stale internal replica refusal, and conflict with two Computer structures. No test may use a Computer's physical `computerId` in `ClusterAuthority`.

- [ ] **Step 3: Verify RED**

```powershell
./gradlew.bat test --tests "*ComputerCoordinatorMemberTest" --tests "*ComputerCoordinatorBindingTest" --offline
```

Expected: compilation fails because `ComputerCoordinatorMember` does not exist and casing is not wired.

- [ ] **Step 4: GREEN — implement a coordinator adapter, not `ClusterMember` on every BE**

`ComputerBlockEntity` does **not** implement `ClusterMember`. Only the elected BE owns a strongly referenced `public final ComputerCoordinatorMember` adapter. Task 6 adds public `ComputerStructureLocator.findCoordinator(ServerLevel, BlockPos, Limits) -> Optional<ComputerCoordinatorMember>` as a thin final revalidation/mapping over Task 5's package-private `findCoordinatorEntity`; there was no earlier public method with an incompatible return type. Tests may also read `coordinatorComputerId()` to distinguish the physical coordinator from `memberId()`. The adapter returns:

```java
memberId()       == computerStructureMemberId
memberType()     == ClusterMemberType.COMPUTER_COORDINATOR
memberAddress()  == SpaceAddress.capture(level, electedComputerPos)
coordinatorComputerId() == electedComputer.computerId().orElseThrow()
bindingState()   == electedComputer.bindingState()
```

On load/scan, register the adapter only when identity/record/coordinator checks pass and readiness is exact: with no epoch every current Computer is profiled (`VALID`); with an epoch the persisted frozen profiles remain strict and the frozen coordinator is loaded/matching. A missing non-coordinator may stay offline/degraded and an empty pending extra is ignored. A no-epoch `VALID_NOT_READY` structure has only a provisional scan owner and publishes nothing. On invalidate, not-ready transition, coordinator change, active-epoch coordinator loss, or identity fault, unregister the exact adapter before discarding it. An unbound ready adapter may be a direct bind target but has no cluster key until commit.

`prepareClusterBinding` validates the cached exact loaded replica set from the accepted scan, valid internal revisions, no epoch/fault, valid identity/persistence, matching root dimension, and `ClusterBinding.MAX_BINDINGS`. It mutates no persistent field. `commitClusterBinding` uses that already validated cached object list, calls `ClusterMemberIndex.rebind(server, adapter, mutation)`, and inside the mutation writes the exact prepared binding to every replica. It performs no world lookup, scan, or validation and has no failure branch.

After `ClusterBindingService.bindingAccess(server, adapter) == READY`, the adapter may copy its exact immutable binding to a lower-revision loaded replica. If access is `AUTHORITY_OFFLINE`/`CONFLICT`, or an internal replica is ahead/equal-divergent, propagation is refused and availability records the reason. Internal replicas never call `ClusterBindingService.reconcileLoaded` because they are not indexed members.

Task 6 also finalizes the existing `startEpoch(clusterId)` behavior without changing its signature: besides Task 5's local checks, the exact owned adapter must still be registered and `ClusterBindingService.bindingAccess(server, adapter)` must be `READY` in the same server tick. Otherwise return `BINDING_UNAVAILABLE` without freezing or mutating replicas. Add this assertion to `ComputerCoordinatorMemberTest` for unbound, authority-offline, conflict, and READY cases.

- [ ] **Step 5: GREEN — implement exact casing interaction call sequence**

`ComputerCasingBlock.onSneakWrenched` uses this sequence, with no nonexistent overload:

```java
if (!(context.getPlayer() instanceof ServerPlayer serverPlayer))
    return InteractionResult.SUCCESS;
Optional<ClusterMember> source = ClusterBindingSelection.resolve(serverPlayer);
if (source.isEmpty())
    return IWrenchable.super.onSneakWrenched(state, context);
Optional<ComputerCoordinatorMember> coordinator =
    ComputerStructureLocator.findCoordinator(serverPlayer.serverLevel(),
        context.getClickedPos(), ComputerStructureScanner.Limits.fromConfig());
if (coordinator.isEmpty()) return InteractionResult.FAIL;
ClusterBindingService.BindResult result = ClusterBindingService.bind(
    serverPlayer, source.get(), coordinator.get());
return result.succeeded() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
```

The public service performs permission/revision/authority checks. Locator revalidation closes move/unload races. A coordinator unload makes the prior `ClusterAuthority(COMPUTER_COORDINATOR, structureMemberId)` unavailable, so external `bindingAccess` is `AUTHORITY_OFFLINE`; binding/order operations refuse without mutating internal replicas. Reload of the same adapter identity restores READY reconciliation.

- [ ] **Step 6: Verify GREEN and Foundation regression suite**

```powershell
./gradlew.bat test --tests "*ComputerCoordinatorMemberTest" --tests "*ComputerCoordinatorBindingTest" --offline
./gradlew.bat test --tests "*ClusterBindingServiceTest" --tests "*ClusterMemberIndexTest" --offline
./gradlew.bat build --offline
```

Expected: all pass.

- [ ] **Step 7: Commit exact files**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerCoordinatorMember.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockEntity.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerCasingBlock.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerTopologyController.java
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureLocator.java
git add src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerCoordinatorMemberTest.java
git add src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/ComputerCoordinatorBindingTest.java
git commit -m "feat: publish stable computer coordinators"
```

---

## Task 7: World-Level Computer GameTest Gate

**Files:** create only `src/main/java/com/nobodiiiii/createbiotech/gametest/ComputerClusterGameTests.java`.

**Fixture contract:** every test uses `@GameTestHolder(CreateBiotech.MOD_ID)`, `@PrefixGameTestTemplate(false)`, and `@GameTest(templateNamespace = "create_biotech", template = "empty", ...)`. Never use `minecraft:empty`.

- [ ] **Step 1: RED — prove the exact namespaced fixture and GameTest harness are reachable**

Create the owned test class with the fixture-contract annotations and only this deliberate harness sentinel plus the minimal `assertEmptyTemplateFixture` decoder copied in style, not implementation ownership, from `PatternLibraryGameTests`:

```java
@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 40)
public static void redComputerHarnessIsReachable(GameTestHelper helper) {
    assertEmptyTemplateFixture(helper);
    helper.fail("RED: replace the Computer harness sentinel with the complete suite");
}
```

- [ ] **Step 2: Verify RED with the same server runner used by the final gate**

```powershell
./gradlew.bat compileJava --offline
./gradlew.bat runGameTestServer --offline
```

Expected: compilation succeeds; the server starts with `create_biotech:empty`, reports `redComputerHarnessIsReachable` failed with the exact sentinel text, and exits nonzero. Any missing-template/bootstrap failure is not the intended RED and must be fixed before continuing.

- [ ] **Step 3: GREEN — replace the sentinel with complete reachable world fixtures**

Delete the sentinel. Create helpers `buildShell(level, min, sizeX, sizeY, sizeZ)`, `computer(level,pos)`, `capturedResident(level, kind, level)`, `headlessPlayer`, `entities`, and `assertEmptyTemplateFixture`, following `PatternLibraryGameTests` conventions. Implement these required tests without direct-only substitutes for a public interaction:

1. `publicBoxInteractionInstallsAtomically`: place an empty Computer, create a filled adult villager box with known UUID/trades/name/health, put it in a `FakePlayer` hand, and call the real public path `player.gameMode.useItemOn(player, level, held, hand, new BlockHitResult(...))`. Wait three ticks, then assert the returned `InteractionResult` consumes the action, held box is empty, serialized resident equals source, and locked profile is correct. Repeat with baby/unsupported/wrong item and assert both sides byte-identical. Posting `PlayerInteractEvent.RightClickBlock` alone is not accepted as proof because posting an event does not invoke a block's `useItemOn` implementation.
2. `everyResidentProfileLocksThroughPublicInteraction`: repeat that `ServerPlayerGameMode.useItemOn` path across ordinary villagers levels `1..5`, librarians levels `1..5`, nitwit, wandering trader, and zombie villager; assert all 13 exact slot/depth rows, only nitwit loop abort, only trader frozen range bonus, and no profile changes after mutating the temporary/source entity data.
3. `threeByThreeAndThreeByFiveBySevenForm`: build exact casing shells with interior Computers, wait 25 ticks, assert `VALID`/`VALID_NOT_READY`, exact non-cubic bounds, and minimum UUID coordinator independent of position.
4. `spurTouchingAmbiguityAndLimits`: assert an external spur does not alter bounds; two shells joined by a one-block casing spur remain separate for their respective seeds; nested `3^3` and `7^3` complete shells joined by the explicit inner-to-outer interior bus bridge from Task 2 report `AMBIGUOUS`; a hole, isolated bus, configured-min-minus-one fixture, configured-max-plus-one fixture, and 33 Computers never form. (The shipped custom tag is intentionally empty; its interior-only classifier branch is proved by the injected-predicate unit test in Task 5.) For config cases, save the three `IntValue` values, set `min=4/max=7` for a `3`-wide rejection and `min=3/max=6` for a `7`-wide rejection, and restore all values in `finally` so later GameTests retain defaults.
5. `breakReleasesExactlyOneHalfHeartResidentAndEmptyComputer`: use the public install path, destroy with the real survival `player.gameMode.destroyBlock(computerPos)`, then assert exactly one entity with original UUID/type and health `1.0F`, preserved trader despawn delay/trades/name, exactly one empty Computer drop across world+inventory, and no filled recovery box on successful release. This proves the `BreakEvent` entry, not a direct lifecycle helper.
6. `forcedSpawnFailureRecoversResident`: put a loaded entity with the resident UUID but a different `EntityType` in another server level (matching the existing cross-level collision fixture style), force non-player replacement, and assert exactly one untouched filled recovery `ItemEntity`, no released duplicate, and no resident data in the empty Computer loot. Do not call an injected unit-only spawn sink from this GameTest.
7. `controlledBreakDoubleFailureKeepsOccupiedComputerAndNoLoot`: install a valid known resident through the public interaction and save the complete authoritative BE tag. Register a scoped listener object on `NeoForge.EVENT_BUS` whose `@SubscribeEvent` method cancels `EntityJoinLevelEvent` only in this test level for (a) the known resident UUID and (b) an `ItemEntity` whose stack still contains captured-entity data; it must not cancel the ordinary empty-Computer loot entity. Inside one `try/finally` listener scope, call the real survival `player.gameMode.destroyBlock(computerPos)`, assert synchronous counters show one rejected resident and one rejected recovery, verify the occupied bytes are unchanged, then call the same real destroy path a second time and assert the counters become two/two. Unregister that exact listener object in `finally`. After the delayed world assertion, require the Computer state/BE and complete saved tag/resident bytes to equal the pre-break copy and require zero matching resident entities, filled recovery items, `CardboardBoxEntity` replacements, or empty Computer items across world plus player inventory. This exercises cancellation/rollback of already prepared empty-block loot and proves repeated attempts do not duplicate; invoking a package-private transaction directly is not an acceptable substitute.
8. `realPanelPatternComputerBindingAndAuthorityReload`: place a real panel, real pattern core, and valid Computer shell; add a real Create logistics link as in `PatternLibraryGameTests`; begin panel selection, sneak-wrench real casing, and assert revision/authority transfer to `ClusterAuthority(COMPUTER_COORDINATOR, computerStructureMemberId)`, exact internal propagation, one index coordinator, and `bindingAccess == READY`. Invalidate/unregister the elected BE and assert `AUTHORITY_OFFLINE` plus no replacement; reload the same BE NBT and assert READY plus stale replica adoption. Bind a second Computer structure and assert computer conflict blocks new work.
9. `faultFreeLastRootCloseAllowsRebindAndInactiveReelection`: form and bind a multi-node Computer, start an epoch, retain its ID, and invoke public `closeIdleEpoch` with a read-only probe reporting natural last-root completion plus idle/root-free/mailbox-empty for every frozen/current UUID. Assert `CLOSED`, no epoch/fault on any replica, and byte-identical binding plus unchanged `computerStructureMemberId`. Perform a real new panel selection and sneak-wrench casing bind to prove rebinding is reachable. Then destroy the now-inactive elected Computer through the real survival path, wait for a fully loaded rescan, and assert the remaining minimum UUID publishes under the same structure member ID and preserved new binding. A stale close request for the old ID must return `NO_EPOCH` without probing.
10. `activeEpochHotAddAndCoordinatorDestruction`: install profiles in every frozen member, start an epoch, add one empty Computer and assert it appears only in `pendingNodes()` with `pending=true, online=false`; install that pending resident through the public box interaction and assert it becomes `pending=true, online=true` while epoch bytes/order/range bonus, usable capacity, and latches do not change. Remove a non-coordinator and assert frozen order/coordinator unchanged; remove coordinator and assert `COORDINATOR_MISSING`, adapter absent, no automatic re-election, epoch/fault NBT preserved, and Foundation authority offline. Call `STOP_ALL_AND_REFORM` with the exact old epoch ID and a control whose stop fails, and assert it retains the old epoch/latch. Then use the fully loaded fixture's same-space valid production scan to prove the old ID absent, supply that same expected ID plus a successful stop/clear control, and assert a new epoch ID/current member set/new minimum coordinator, cleared latches, unchanged `computerStructureMemberId`, and restored Foundation publication under that same structure identity. Re-send the abandoned ID and assert `EPOCH_MISMATCH` without another stop call. The genuinely-unavailable-chunk and uncertain-space refusal branches remain the throwing non-loading controller tests in Task 5 rather than pretending the GameTest harness can unload its ticketed template chunk.
11. `clientProjectionAndServerRestart`: save a Computer containing sensitive resident/binding/epoch/fault state, recursively inspect the update tag for the same forbidden keys/marker as the unit test, load the server tag into a replacement BE, and assert identity/profile/structure/binding/epoch/fault/resident round-trip exactly.

The `create_biotech:empty` fixture assertion must decode `/data/create_biotech/structure/empty.nbt`, verify a zero palette/block/entity list, verify its dimensions fit the largest fixture, and confirm every structure coordinate begins as air.

- [ ] **Step 4: Verify GREEN with focused unit/Foundation gates first**

```powershell
./gradlew.bat test --tests "*Computer*" --tests "*ClusterEpochTest" --offline
./gradlew.bat test --tests "*ClusterBinding*" --tests "*ClusterMemberIndex*" --tests "*PatternStorageCoreBlockEntityTest" --offline
$sentinel = git grep -n "RED: replace" -- src/main/java/com/nobodiiiii/createbiotech/gametest/ComputerClusterGameTests.java
if ($LASTEXITCODE -eq 0) { throw "GameTest RED sentinel remains: $sentinel" }
```

Expected: both Gradle commands end in `BUILD SUCCESSFUL` and the sentinel check does not throw.

- [ ] **Step 5: Final GameTest gate**

```powershell
./gradlew.bat runGameTestServer --offline
```

Expected: the server exits successfully and reports every Pattern and Computer GameTest passed, including the exact `create_biotech:empty` fixture.

- [ ] **Step 6: Final repository gate**

```powershell
./gradlew.bat runData --offline
./gradlew.bat build --offline
git diff --check
git status --short
```

Expected: build/data/test gates succeed; status shows only the new GameTest file. If `runData` changed any generated file, stop and return that path to its owning earlier task. No Pattern file changes are introduced by this task.

- [ ] **Step 7: Commit exact file**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/gametest/ComputerClusterGameTests.java
git commit -m "test: gate computer multiblock behavior"
```

If `runData` changed an already committed owned generated tag because Task 4 failed to stage it, stop and amend Task 4 rather than hiding it in the GameTest commit.

---

## Final Acceptance Audit

- [ ] Scanner bounds come only from capped candidate enumeration; external casing, touching structures, ambiguity, min/max, node limit, same-space, and throw-after-unloaded tests pass.
- [ ] Snapshot exposes immutable bounds/nodes/casing/chunks and `node(UUID)` with strict NBT; structural `equals`/`hashCode` accepts independently decoded replica/locator values and rejects any component change.
- [ ] Epoch freeze requires every physical node's strict installed profile; relocation updates bounds and coordinator address under rigid translation/rotation; pending nodes never join.
- [ ] Stable `computerStructureMemberId` is distinct from physical `computerId`; only one adapter is indexed; inactive re-election preserves Foundation authority identity.
- [ ] Active epoch uses frozen-subset semantics; missing non-coordinator never substitutes/elects; missing coordinator is offline; `WIDTH/DEPTH` survives cancellation/reload. Fault-free natural close is expected-ID guarded, loaded/same-space/fully quiescent, atomically clears the epoch without a stop callback, preserves structure identity/binding, and makes rebind plus later inactive re-election reachable. Explicit reform first clears all Runtime ownership, rejects unloaded/space-uncertain members, and may rebuild a new epoch only after a fully loaded same-space scan proves permanent loss.
- [ ] Public Runtime/UI accessors have the locked nullability/immutability and expose pending/frozen/online/fault reasons without raw NBT.
- [ ] Installation is through the real block interaction and is atomic. `BreakEvent` owns controlled player destruction before vanilla loot; removal is idempotent and lossless across spawn success, exact collision, recovery output, double-failure reconstruction/loot rollback, and `isMoving=true`. Every output/collision/fallback coordinate uses `SubLevelCompat` projection and the translated/rotated fixture passes.
- [ ] Server/client NBT roots are disjoint; corrupt children are isolated conservatively; recursive leak tests exclude resident, cluster, binding, structure, epoch, address, frame, and mailbox data.
- [ ] Internal-component tag has one authoritative static JSON; production classification uses its `TagKey`; tagged classification is tested as interior-only; datagen/resource gates pass.
- [ ] Pattern recovery API is rechecked at clean `fac73ecd`; future Runtime is locked to requester-filtered `drainReplies(UUID,int)` and this Computer plan has no Runtime/Pattern implementation dependency.
- [ ] Every intermediate commit compiles/tests independently and stages only exact files.
- [ ] Final `create_biotech:empty` GameTest server gate passes.

## Resolved Permanent-Destruction Rule

Normal fault-free `closeIdleEpoch` is deliberately narrower: it observes natural quiescence, requires every frozen ID online, refuses `WIDTH/DEPTH` and missing members, clears the old epoch atomically, and preserves structure identity/binding without invoking any Runtime stop. `STOP_ALL_AND_REFORM` reconciles permanent-loss recovery without an identity-preserving drop. It first terminates and clears all Runtime ownership. It then distinguishes permanent loss from unload by requiring every relevant known chunk loaded, exact same-space certainty, and one valid production scan that positively omits the old frozen ID. Only that proof permits replacing the old epoch/fault set with a newly frozen epoch over current valid nodes. An unloaded or uncertain old member keeps the old epoch offline. The stable `computerStructureMemberId` never changes, so Foundation authority identity survives the newly elected physical coordinator. No architectural decision remains open in this plan.
