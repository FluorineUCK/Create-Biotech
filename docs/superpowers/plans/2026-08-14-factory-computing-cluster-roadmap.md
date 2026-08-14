# Factory Computing Cluster Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved factory computing cluster as five ordered, reviewable increments without introducing a global persistent task directory or a second UI framework.

**Architecture:** The feature is split at stable interfaces: cluster identity/discovery, pattern library, computer structure, distributed runtime/logistics, and panel UI/integration. Each increment compiles and has its own tests; later plans consume exact records and service interfaces produced by earlier plans.

**Tech Stack:** Java 21, Minecraft 1.21.1, NeoForge 21.1.234, Create 6.0.10-281, Catnip packets, Create GUI widgets, JUnit Jupiter 5.11.4, NeoForge GameTest, optional Sable through `SubLevelCompat`.

## Global Constraints

- Work only on branch `feature/factory-computing-cluster`; do not commit `.superpowers/`.
- Keep Create compatibility in `[6.0.10,6.0.12)` and Sable optional; do not add a Sable Mixin.
- Use `SubLevelCompat` for space identity, loaded block lookup, external-coordinate distance, and local particle placement.
- Do not persist a global member/task directory and do not use `SavedData` for cluster tasks.
- Do not request chunk tickets, force chunk saves, save the whole world, reread player/world files, or wait for an IO worker from interaction/tick code.
- Do not add a private item inventory; stock truth comes from `LogisticsManager.getSummaryOfNetwork(logisticsId, true)`.
- Do not use Request Promises or in-flight packages as available stock.
- Do not copy AE source, widgets, textures, or scheduling semantics; use Create UI classes and resources.
- Keep network protocol `15` through plans 1–4; append packet IDs and change it once to `16` only in plan 5 after packet shapes are final.
- Preserve existing packet ordering; every new packet registration is appended.
- Entity snapshots are not `Clearable` content. Sable movement uses `isMoving=true` to suppress release and restores the pre-move NBT at the destination.
- Every multiblock is confined to one root dimension and one Sable space; cluster members may communicate across spaces in the same root dimension.
- Server-side code is authoritative for menu identity, cluster ID, logistics ID, space identity, address validity, and Create permissions.

---

## Ordered Plan Set

1. [`2026-08-14-factory-cluster-foundation.md`](2026-08-14-factory-cluster-foundation.md)
   - JUnit setup.
   - Stable identities, ordered logistics bindings, space addresses, transient loaded-member index.
   - Cluster binding selection/service and basic Factory Panel block/item/BE.
   - Server config values shared by later stages.

2. [`2026-08-14-pattern-library.md`](2026-08-14-pattern-library.md)
   - Two-block Pattern Storage Core conversion and librarian lifecycle.
   - Connected bookshelf multiblock with partial-chunk pause.
   - Writable-book page JSON, incremental cache/search, queue fairness, renderer.

3. [`2026-08-14-computer-multiblock.md`](2026-08-14-computer-multiblock.md)
   - Computer Casing and Computer registration.
   - Captured villager/trader/zombie hardware profiles and exact release behavior.
   - Sealed 3–7 cuboid validation, casing bus, stable coordinator, frozen epoch.

4. [`2026-08-14-factory-task-runtime.md`](2026-08-14-factory-task-runtime.md)
   - Compact task states/reasons and local frame/mailbox persistence.
   - Weakest-first tail delegation, width/depth blocking, loop handling, continuous batches.
   - Physical Factory Gauge and virtual pattern proxy adapters.
   - Real-stock checks, Create package dispatch, semantic WiFi particles, cleanup.

5. [`2026-08-14-factory-panel-ui-integration.md`](2026-08-14-factory-panel-ui-integration.md)
   - Two-page Create-native panel menu/screen.
   - Multi-network stock snapshots, explicit final address, live cluster/task view.
   - Server-authoritative packets, stop/cancel/reform controls, protocol bump to 16.
   - Integration, GameTest, Sable/no-Sable, client/server, and resource validation.

## Cross-Plan Interface Lock

The following names are fixed so separate implementers do not invent incompatible boundaries:

```java
public record LogisticsBinding(UUID logisticsId, String alias) {}

public record SpaceAddress(ResourceKey<Level> dimension,
	@Nullable UUID subLevelId, BlockPos localPos) {}

public enum ClusterMemberType {
	PANEL, PATTERN_CORE, COMPUTER_COORDINATOR
}

public record ClusterAuthority(ClusterMemberType type, UUID memberId) {}

public record ClusterBinding(UUID clusterId, long revision,
	@Nullable ClusterAuthority authority, List<LogisticsBinding> logisticsBindings) {
	public static final int MAX_BINDINGS = 32;
}

public enum ClusterBindingPreparation {
	READY, IDENTITY, REVISION, CAPACITY, ACTIVE
}

public interface ClusterMember {
	UUID memberId();
	@Nullable ClusterBinding bindingState();
	default @Nullable UUID clusterId();
	default List<LogisticsBinding> logisticsBindings();
	default boolean hasValidBindingState();
	ClusterMemberType memberType();
	SpaceAddress memberAddress();
	boolean canRebind();
	ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed);
	void commitClusterBinding(ClusterBinding prepared); // no-fail after READY
}

public record StackKey(ItemStack stack) {}
public record PatternRecord(UUID patternId, List<PatternIngredient> inputs,
	List<PatternOutput> outputs, String recipeAddress, PatternPageKey source) {}
public record ComputerProfile(NodeKind kind, int slots, int depth,
	boolean loopAbort, int patternRangeBonus) {}
public record ClusterEpoch(UUID epochId, UUID clusterId, UUID coordinatorId,
	SpaceAddress coordinatorAddress, BoundingBox bounds, List<EpochNode> nodes) {}
```

The task runtime uses only these compact state names:

```java
enum FrameState { RUN, WAIT, SLP, BLOCK, HALT }
enum FrameReason {
	NONE, NODE, CHILD, STOCK, MATERIAL, PATTERN, PROXY, STORAGE,
	OFFLINE, LOOP, ROUTE, WIDTH, DEPTH, OK, CANCEL, FAULT, LOOP_ABORT
}
enum ComputerState { IDLE, RUN, SLP }
```

## Completion Gate

- [ ] **Step 1: Execute the plans in order**

Run each plan's focused tests and commit gate before starting the next plan. Do not cherry-pick a later plan ahead of an earlier interface-producing plan.

- [ ] **Step 2: Run the complete unit suite**

Run: `./gradlew.bat test --offline`

Expected: `BUILD SUCCESSFUL`; all factory-cluster JUnit classes pass.

- [ ] **Step 3: Run the complete game-test suite**

Run: `./gradlew.bat runGameTestServer --offline`

Expected: the server exits successfully with all required `create_biotech` game tests passing.

- [ ] **Step 4: Build the distributable**

Run: `./gradlew.bat build --offline`

Expected: `BUILD SUCCESSFUL`; the jar contains the four block registrations, menu, packets, mixin, models, blockstates, loot tables, tags, and English/Chinese translations.

- [ ] **Step 5: Perform repository checks**

Run:

```powershell
git diff --check
git status --short
git grep -n -E "DimensionDataStorage\.save|saveAllChunks|waitUntilIOWorkerComplete" -- src/main/java/com/nobodiiiii/createbiotech/content/factorycluster
git grep -n "Class.forName" -- src/main/java/com/nobodiiiii/createbiotech/mixin/compat/sable
```

Expected: no whitespace errors; only intended source/resource/plan files are shown; both forbidden-call searches return no matches.

- [ ] **Step 6: Commit the completed feature**

```powershell
git add src/main src/test build.gradle docs/superpowers/plans
git commit -m "feat: add factory computing cluster"
```

Expected: commit succeeds and `.superpowers/` remains untracked.
