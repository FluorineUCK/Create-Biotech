# Computer Multiblock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add villager-powered Computer blocks and dedicated Computer Casing, validate sealed casing-bus cuboids, and persist stable coordinator/epoch topology without centralizing task scheduling.

**Architecture:** Computer hardware capability is frozen into a pure `ComputerProfile` when a captured entity is installed. A pure casing-component scanner derives one sealed rectangular structure and its members. Every Computer redundantly stores structure/epoch summaries; only the lowest stable `computerId` registers as the cluster coordinator.

**Tech Stack:** Java 21, NeoForge blocks/BEs, Create casing CT rendering, captured-entity boxes, JUnit Jupiter, NeoForge GameTest, `ClusterMemberIndex`, `CBMultiBlockLifecycle`, `SubLevelCompat`.

## Global Constraints

- Requires the foundation and pattern-library plans.
- Cuboid dimensions are 3–7 per axis by default, volume at most 343, with at most 32 Computer blocks.
- Every outer-shell position is `computer_casing`; a Computer cannot replace an outer-shell casing.
- Interior allows air, Computer, Computer Casing, or `create_biotech:computer_internal_components`.
- Every Computer must touch the connected casing component that includes the outer shell.
- A structure is entirely in one root dimension and one Sable space.
- Installed entities cannot be removed or changed until the Computer block is broken.
- Resident snapshots are not `Clearable` content; `isMoving=true` suppresses release.
- Coordinator selection uses stable UUID ordering, never `BlockPos` ordering.
- During an active epoch, destroyed coordinator means `SLP(OFFLINE)`; no automatic re-election.
- No task execution, packet, menu, or protocol-version change is introduced in this plan.

---

## File Map

**Create**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/NodeKind.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerProfile.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureScanner.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureSnapshot.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/EpochNode.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ClusterEpoch.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlock.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerBlockEntity.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerCasingBlock.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureLocator.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerProfileTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureScannerTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ClusterEpochTest.java`
- `src/main/resources/assets/create_biotech/blockstates/computer.json`
- `src/main/resources/assets/create_biotech/blockstates/computer_casing.json`
- `src/main/resources/assets/create_biotech/models/block/computer.json`
- `src/main/resources/assets/create_biotech/models/block/computer_casing.json`
- `src/main/resources/assets/create_biotech/models/item/computer.json`
- `src/main/resources/assets/create_biotech/models/item/computer_casing.json`
- `src/main/resources/data/create_biotech/loot_table/blocks/computer.json`
- `src/main/resources/data/create_biotech/loot_table/blocks/computer_casing.json`
- `src/main/resources/data/create_biotech/tags/block/computer_internal_components.json`

**Modify**

- `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlocks.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBItems.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlockEntityTypes.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBCreativeModeTabs.java`
- `src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java`
- `src/main/java/com/nobodiiiii/createbiotech/client/CreateBiotechClient.java`
- `src/main/resources/assets/create_biotech/lang/en_us.json`
- `src/main/resources/assets/create_biotech/lang/zh_cn.json`

### Task 1: Freeze the Approved Biological Hardware Table

**Files:**
- Create: `NodeKind.java`
- Create: `ComputerProfile.java`
- Test: `ComputerProfileTest.java`

**Interfaces:**
- Consumes: the current `wanderingTraderRangeBonus` config value only when a resident is installed.
- Produces: `ComputerProfile.fromEntity(Entity, int)` and pure `ComputerProfile.forKind(NodeKind, int, int)`; the resulting profile freezes its range bonus in NBT.

- [ ] **Step 1: Write the complete table as failing parameterized tests**

```java
@ParameterizedTest
@CsvSource({
	"VILLAGER,1,1,1", "VILLAGER,2,2,1", "VILLAGER,3,3,2",
	"VILLAGER,4,4,2", "VILLAGER,5,4,2",
	"LIBRARIAN,1,4,2", "LIBRARIAN,2,6,2", "LIBRARIAN,3,8,3",
	"LIBRARIAN,4,12,3", "LIBRARIAN,5,16,4",
	"NITWIT,1,1,0", "WANDERING_TRADER,1,2,1", "ZOMBIE_VILLAGER,1,0,0"
})
void approvedHardwareTable(NodeKind kind, int level, int slots, int depth) {
	ComputerProfile profile = ComputerProfile.forKind(kind, level, 64);
	assertEquals(slots, profile.slots());
	assertEquals(depth, profile.depth());
}

@Test
void onlyNitwitAbortsLoopsAndOnlyTraderAddsRange() {
	assertTrue(ComputerProfile.forKind(NodeKind.NITWIT, 1, 64).loopAbort());
	assertEquals(64, ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 1, 64).patternRangeBonus());
	assertEquals(0, ComputerProfile.forKind(NodeKind.ZOMBIE_VILLAGER, 1, 64).patternRangeBonus());
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*ComputerProfileTest" --offline`

Expected: compilation fails because profile types do not exist.

- [ ] **Step 3: Implement compact hardware records**

```java
public enum NodeKind {
	VILLAGER, LIBRARIAN, NITWIT, WANDERING_TRADER, ZOMBIE_VILLAGER
}

public record ComputerProfile(NodeKind kind, int slots, int depth,
	boolean loopAbort, int patternRangeBonus) {
	public static ComputerProfile forKind(NodeKind kind, int level, int traderRangeBonus) {
		int rank = Math.clamp(level, 1, 5);
		int frozenTraderBonus = Math.max(0, traderRangeBonus);
		return switch (kind) {
			case VILLAGER -> new ComputerProfile(kind,
				new int[]{1, 2, 3, 4, 4}[rank - 1], new int[]{1, 1, 2, 2, 2}[rank - 1], false, 0);
			case LIBRARIAN -> new ComputerProfile(kind,
				new int[]{4, 6, 8, 12, 16}[rank - 1], new int[]{2, 2, 3, 3, 4}[rank - 1], false, 0);
			case NITWIT -> new ComputerProfile(kind, 1, 0, true, 0);
			case WANDERING_TRADER -> new ComputerProfile(kind, 2, 1, false, frozenTraderBonus);
			case ZOMBIE_VILLAGER -> new ComputerProfile(kind, 0, 0, false, 0);
		};
	}
}
```

`fromEntity` accepts adult `Villager`, `WanderingTrader`, and non-baby `ZombieVillager`; classify librarian/nitwit from `VillagerData`, then call `forKind(kind, level, traderRangeBonus)`. Read the config only at installation, so later config changes or entity changes cannot mutate the active epoch profile. Return `Optional.empty()` for every other entity.

- [ ] **Step 4: Add NBT and run tests**

Use keys `Kind`, `Slots`, `Depth`, `LoopAbort`, and `PatternRangeBonus`. On load, reject negative values or values above slots 16/depth 4/range 4096 and return empty rather than widening a corrupted node.

Run: `./gradlew.bat test --tests "*ComputerProfileTest" --offline`

Expected: full table and invariants pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/NodeKind.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerProfile.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerProfileTest.java
git commit -m "feat: define biological computer profiles"
```

### Task 2: Implement the Sealed Cuboid and Casing-Bus Scanner

**Files:**
- Create: `ComputerStructureScanner.java`
- Create: `ComputerStructureSnapshot.java`
- Test: `ComputerStructureScannerTest.java`

**Interfaces:**
- Produces: `ComputerStructureScanner.scan(View, BlockPos, Limits)`.

- [ ] **Step 1: Write scanner tests for all structural invariants**

```java
@Test void acceptsThreeByFiveBySevenSealedCuboid();
@Test void acceptsSevenBySevenBySevenAtVolume343();
@Test void rejectsHoleInOuterShell();
@Test void rejectsComputerOnOuterShell();
@Test void rejectsIllegalInteriorBlock();
@Test void rejectsComputerNotTouchingCasingBus();
@Test void rejectsThirtyThirdComputer();
@Test void pausesWhenKnownVolumeChunkIsUnloaded();
@Test void rejectsMemberFromAnotherSublevel();
```

The fake view builds exact block maps and asserts the scanner's `ComputerStructureState` reason for each case.

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*ComputerStructureScannerTest" --offline`

Expected: compilation fails because scanner types do not exist.

- [ ] **Step 3: Define view, limits, and states**

```java
public interface View {
	boolean isLoaded(BlockPos pos);
	boolean sameSpace(BlockPos origin, BlockPos pos);
	CellKind cellAt(BlockPos pos);
	@Nullable UUID computerIdAt(BlockPos pos);
}

public enum CellKind { AIR, CASING, COMPUTER, ALLOWED_INTERNAL, ILLEGAL }
public enum ComputerStructureState {
	UNFORMED, VALID, PARTIAL, SIZE, VOLUME, OPEN_SHELL, ILLEGAL_INTERNAL,
	DISCONNECTED_BUS, NODE_LIMIT, SPACE
}

public record Limits(int minSize, int maxSize, int maxVolume, int maxNodes) {}
```

- [ ] **Step 4: Implement casing-component discovery and cuboid validation**

From each casing adjacent to the seed Computer, BFS same-space casing positions and stop at `maxVolume + 1`. The casing component's min/max coordinates define the only candidate cuboid. Validate:

```java
int dx = maxX - minX + 1;
int dy = maxY - minY + 1;
int dz = maxZ - minZ + 1;
if (dx < min || dy < min || dz < min || dx > max || dy > max || dz > max) return SIZE;
if (Math.multiplyExact(Math.multiplyExact(dx, dy), dz) > maxVolume) return VOLUME;
```

Read every position only after `isLoaded`. Every outer coordinate is `CASING`. Every inner coordinate is an allowed kind. Count Computers and enforce `maxNodes`. For each Computer require at least one adjacent position in the discovered casing component. Require `sameSpace(seed, pos)` across the volume. Return an immutable snapshot with bounds, sorted Computer IDs/positions, casing component, containing chunks, and coordinator ID equal to the minimum UUID.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew.bat test --tests "*ComputerStructureScannerTest" --offline`

Expected: every shape/bus/space/limit case returns its exact state.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureScanner.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureSnapshot.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ComputerStructureScannerTest.java
git commit -m "feat: validate computer casing structures"
```

### Task 3: Register Computer and Computer Casing with Resident Lifecycle

**Files:**
- Create: `ComputerBlock.java`
- Create: `ComputerBlockEntity.java`
- Create: `ComputerCasingBlock.java`
- Modify: registrations, models, loot, tag, locales.

**Interfaces:**
- Consumes: `ComputerProfile`, captured-box exact-UUID creation.
- Produces: registered `create_biotech:computer` and `create_biotech:computer_casing`.

- [ ] **Step 1: Add registrations**

```java
public static final DeferredHolder<Block, ComputerBlock> COMPUTER = BLOCKS.register("computer",
	() -> new ComputerBlock(CBSharedProperties.createSoftMetal().noOcclusion()
		.mapColor(MapColor.COLOR_LIGHT_BLUE)));
public static final DeferredHolder<Block, ComputerCasingBlock> COMPUTER_CASING = BLOCKS.register("computer_casing",
	() -> new ComputerCasingBlock(CBSharedProperties.createStone().sound(SoundType.WOOD)
		.mapColor(MapColor.COLOR_LIGHT_BLUE)));
```

Register ordinary `BlockItem`s and one lower-level `ComputerBlockEntity` type. Add both items to the main creative tab and pickaxe mining tag.

- [ ] **Step 2: Implement atomic resident installation**

`ComputerBlockEntity.installResident(ServerPlayer, InteractionHand, ItemStack)`:

1. reject non-empty `residentSnapshotBox`, active epoch, frame, or mailbox;
2. create a temporary exact-UUID entity from the held box;
3. call `ComputerProfile.fromEntity` and reject unsupported/baby entities;
4. copy the filled box and computed profile into local variables;
5. assign both BE fields and mark/sync;
6. clear the captured entity from the player's held box, leaving the same empty box item.

The locked profile is always loaded from stored profile NBT after installation; it is never recomputed from later profession/level changes.

- [ ] **Step 3: Implement fixed release on block removal**

`ComputerBlock.onRemove` obtains the BE before `super.onRemove`. When type changes and `isMoving=false`, call `releaseResidentAfterBreak`. Find a safe point around the Computer; if none exists, use its center because the block is being removed. Restore the snapshot entity, set a `LivingEntity` to `1.0f` health, and add it once. Preserve the stored wandering-trader despawn delay; no ticking occurs while only the item snapshot exists. Clear the snapshot only after successful spawn or after confirming an already-loaded entity with the same UUID and type.

The normal loot table drops exactly one empty Computer block and never writes resident BE data. `isMoving=true` skips release and leaves the source snapshot intact for Sable's pre-removal NBT copy.

- [ ] **Step 4: Add exact resources**

`models/block/computer.json`:

```json
{"parent":"create:block/stock_ticker"}
```

`models/block/computer_casing.json`:

```json
{"parent":"minecraft:block/cube_all","textures":{"all":"create_biotech:block/biotech_casing"}}
```

Both item models parent their block models. Both loot tables drop self. `computer_internal_components.json` begins as:

```json
{"replace":false,"values":[]}
```

Register Computer Casing with `CreateClient.CASING_CONNECTIVITY.makeCasing(..., CBSpriteShifts.BIOTECH_CASING)` and a `CTModel` using `EncasedCTBehaviour(CBSpriteShifts.BIOTECH_CASING)`.

- [ ] **Step 5: Compile and commit**

Run: `./gradlew.bat build --offline`

Expected: `BUILD SUCCESSFUL`; both block/item/model/loot IDs resolve.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer src/main/java/com/nobodiiiii/createbiotech/registry src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java src/main/java/com/nobodiiiii/createbiotech/client/CreateBiotechClient.java src/main/resources
git commit -m "feat: add villager computers and casing"
```

### Task 4: Add Stable Structure Coordination and Frozen Epochs

**Files:**
- Create: `EpochNode.java`
- Create: `ClusterEpoch.java`
- Create: `ComputerStructureLocator.java`
- Modify: `ComputerBlockEntity.java`
- Test: `ClusterEpochTest.java`

**Interfaces:**
- Consumes: `ComputerStructureSnapshot`, cluster identities.
- Produces: stable coordinator publication and `ClusterEpoch.freeze/relocate` for the runtime plan.

- [ ] **Step 1: Write epoch tests**

```java
@Test
void coordinatorIsLowestUuidNotLowestPosition() {
	ClusterEpoch epoch = ClusterEpoch.freeze(CLUSTER, snapshotWithIds(HIGH_AT_LOW_POS, LOW_AT_HIGH_POS));
	assertEquals(LOW_AT_HIGH_POS, epoch.coordinatorId());
}

@Test
void relocationChangesAddressesButNotMembershipOrProfiles() {
	ClusterEpoch moved = epoch.relocate(Map.of(NODE_ID, NEW_ADDRESS));
	assertEquals(epoch.epochId(), moved.epochId());
	assertEquals(epoch.nodes().getFirst().profile(), moved.nodes().getFirst().profile());
	assertEquals(NEW_ADDRESS, moved.nodes().getFirst().address());
}

@Test
void hotAddedNodeIsExcludedFromExistingEpoch() {
	assertFalse(epoch.contains(HOT_ADDED_ID));
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*ClusterEpochTest" --offline`

Expected: compilation fails because epoch types do not exist.

- [ ] **Step 3: Implement immutable epoch records**

```java
public record EpochNode(UUID computerId, SpaceAddress address, ComputerProfile profile) {}

public record ClusterEpoch(UUID epochId, UUID clusterId, UUID coordinatorId,
	SpaceAddress coordinatorAddress, BoundingBox bounds, List<EpochNode> nodes) {
	public ClusterEpoch {
		nodes = nodes.stream().sorted(Comparator.comparing(EpochNode::computerId)).toList();
	}

	public int totalPatternRangeBonus() {
		return nodes.stream().mapToInt(node -> node.profile().patternRangeBonus()).sum();
	}
}
```

`freeze` rejects an invalid/partial structure or duplicate Computer IDs and creates a random epoch UUID. `relocate` requires an address for every existing node, keeps membership/profiles/epoch ID fixed, and ignores extra IDs.

- [ ] **Step 4: Integrate structure scans and redundant summaries**

Every Computer has a stable random `computerId`. A 20-tick lazy scan:

- pauses on missing chunks using the last snapshot;
- applies a new valid snapshot to every loaded Computer in the snapshot;
- when there is no epoch, elects the lowest UUID coordinator;
- when there is an epoch, validates exact member IDs and relocates addresses only;
- never substitutes a new Computer for a missing epoch member.

Only the elected BE registers in `ClusterMemberIndex` as `COMPUTER_COORDINATOR`. It unregisters when invalidated or no longer coordinator. Every Computer persists the same structure summary and epoch plus its local `computerId`, binding, profile, and snapshot.

- [ ] **Step 5: Implement coordinator loss behavior**

Expose:

```java
public boolean isCoordinator();
public boolean epochActive();
public boolean coordinatorOnline();
public Optional<ClusterEpoch> epoch();
public boolean canCloseEpoch();
public void setRuntimeOffline(boolean offline);
```

When an active epoch's coordinator ID is absent/mismatched, all loaded members retain the old ID and set runtime offline. `canCloseEpoch` is true only when later runtime fields report no roots, no frame, empty inbound/outbound mailboxes, all epoch IDs loaded, and all nodes idle. With no epoch, loss of the old coordinator allows immediate lowest-UUID election.

- [ ] **Step 6: Run tests and commit**

Run: `./gradlew.bat test --tests "*ClusterEpochTest" --offline`

Expected: stable election, relocation, hot-add exclusion, and coordinator-loss tests pass.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/computer/ClusterEpochTest.java
git commit -m "feat: freeze computer cluster epochs"
```

### Task 5: Bind Through the Casing and Enforce Same-Space Structure Membership

**Files:**
- Modify: `ComputerCasingBlock.java`
- Modify: `ComputerStructureLocator.java`
- Modify: `ComputerBlockEntity.java`

**Interfaces:**
- Consumes: `ClusterBindingSelection`, `ClusterBindingService`.
- Produces: player-facing binding of an entire valid computer structure.

- [ ] **Step 1: Implement casing-to-coordinator lookup**

`ComputerStructureLocator.findCoordinator(Level, BlockPos casingPos)` BFSes the local connected Computer Casing component with the same 343-position cap, gathers adjacent Computers without loading chunks, invokes the production scanner, and returns the BE whose UUID equals the valid snapshot coordinator.

- [ ] **Step 2: Route sneak-wrench binding through the coordinator**

`ComputerCasingBlock.onSneakWrenched` resolves an active `ClusterBindingSelection`. With no selection, it returns `IWrenchable.super.onSneakWrenched`; with a selection and valid coordinator, it calls `ClusterBindingService.bind`.

`ComputerBlockEntity.applyClusterBinding` on the coordinator first verifies every structure member is loaded, idle, and has empty mailboxes, then applies the exact same `clusterId` and normalized binding list to every Computer. If any member fails validation, no member changes.

- [ ] **Step 3: Verify cross-space rejection and normal Sable movement**

The production scanner view calls `SubLevelCompat.sameSpace` for every cell. Whole-structure movement may change all local positions; the next scan relocates epoch addresses by stable IDs. A partial move yields `PARTIAL`/offline and retains summaries/entity snapshots.

- [ ] **Step 4: Compile and commit**

Run: `./gradlew.bat build --offline`

Expected: `BUILD SUCCESSFUL`.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/computer
git commit -m "feat: bind sealed computer clusters"
```

### Task 6: Computer Game-Test Gate

**Files:**
- Create: `src/main/java/com/nobodiiiii/createbiotech/gametest/ComputerClusterGameTests.java`

**Interfaces:**
- Produces: world-level coverage required by the runtime plan.

- [ ] **Step 1: Add empty-template tests**

Add required tests for:

```java
static void validThreeByThreeByThreeForms(GameTestHelper helper);
static void rectangularThreeByFiveBySevenForms(GameTestHelper helper);
static void shellHoleRejects(GameTestHelper helper);
static void isolatedComputerRejects(GameTestHelper helper);
static void thirtyThirdComputerRejects(GameTestHelper helper);
static void everyResidentProfileLocks(GameTestHelper helper);
static void breakReleasesOneHalfHeartResident(GameTestHelper helper);
static void movingRemovalDoesNotReleaseResident(GameTestHelper helper);
static void coordinatorLossDoesNotReelectActiveEpoch(GameTestHelper helper);
```

Use `@GameTestHolder(CreateBiotech.MOD_ID)`, `@PrefixGameTestTemplate(false)`, and `templateNamespace="minecraft", template="empty"`.

- [ ] **Step 2: Run GameTest and repository checks**

```powershell
./gradlew.bat runGameTestServer --offline
git diff --check
```

Expected: all Computer tests pass; no whitespace errors.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/gametest/ComputerClusterGameTests.java
git commit -m "test: cover computer cluster topology"
```
