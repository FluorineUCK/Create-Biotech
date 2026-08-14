# Factory Cluster Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish test infrastructure, stable cluster/logistics/space identities, transient loaded-member discovery, binding rules, and a persistent multi-network Factory Panel block.

**Architecture:** Pure value records own normalization and serialization. A per-server weak runtime index discovers only loaded members and never becomes persistent state. The Factory Panel stores its own ordered logistics UUID list rather than installing duplicate `LogisticallyLinkedBehaviour` instances.

**Tech Stack:** Java 21 records, NeoForge Block/BlockEntity registration, Create `SmartBlockEntity`, Create logistics permissions, Minecraft data components, JUnit Jupiter 5.11.4, `SubLevelCompat`.

## Global Constraints

- Use branch `feature/factory-computing-cluster`; leave `.superpowers/` untracked.
- Keep protocol `CBPackets.NETWORK_VERSION = "15"` in this plan; this plan adds no packets.
- The runtime index uses weak references and a `WeakHashMap<MinecraftServer, ...>`; it is not NBT and not `SavedData`.
- A `null` sublevel UUID means the outer world and must reject plot-grid positions through `SubLevelCompat`.
- Logistics bindings preserve insertion order, reject duplicates by UUID, clamp aliases to 32 code points, and never trust item/client UUIDs without Create permission checks.
- Do not add `LogisticallyLinkedBehaviour` to Factory Panel.
- Do not add recipes whose material balance was not approved; make the panel available in the Create Biotech creative tab.

## Final-Review Binding Amendment (Normative)

This section supersedes the earlier Task 1–4 snippets where they show a two-field `ClusterBinding`, `SpaceAddress.load`, or one-phase `applyClusterBinding`:

- `ClusterBinding` is member-local schema version 1 and stores `clusterId`, nonnegative `revision`, optional `ClusterAuthority(type, memberId)`, and the exact ordered bindings. `ClusterBinding.MAX_BINDINGS` is 32; constructors reject an oversized normalized list and strict NBT decode rejects oversized, duplicate, malformed, or wrong-version data.
- `SpaceAddress.tryLoad(tag[, expectedDimension])` is the only foundation decode boundary. It requires exact NBT field types, a valid dimension resource location, an exact optional UUID, and an exact long position. Selection and member decoders use it instead of duplicating lenient checks.
- `ClusterMember` exposes `bindingState`, side-effect-free `prepareClusterBinding(ClusterBinding)`, and no-fail `commitClusterBinding(ClusterBinding)`. Every service transaction prepares every participant before committing any participant.
- `ClusterBindingService.replaceBindings(...)` is the public cluster-wide mutation API. It validates all old/proposed logistics permissions, all loaded participants, all known authorities, activity, dimension, revision, and the 32-entry cap before a single commit.
- Binding authority priority is computer coordinator, then pattern core, then lowest currently participating panel UUID. Authority transfer requires every known old authority loaded and every participant idle. A stale replica only adopts a loaded authority's newer/equal verified state; missing authority or incomparable state blocks mutation/orders without loading chunks.
- `ClusterMemberIndex` remains a weak runtime index. It performs load-time reconciliation and reports binding conflicts but never becomes persistent state and never forces a save.
- A linked-block `useOn` appends a network only when the player is not sneaking; sneak-use delegates to normal block placement.

---

## File Map

**Create**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/LogisticsBinding.java` — ordered binding value, alias normalization, NBT.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBinding.java` — cluster UUID plus immutable normalized bindings.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterAuthority.java` — stable binding-authority type and member UUID.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingPreparation.java` — two-phase participant preparation result.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/SpaceAddress.java` — root dimension, optional sublevel UUID, local block position, non-loading resolution.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMemberType.java` — panel/pattern/coordinator discriminator.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMember.java` — shared binding/discovery contract.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMemberIndex.java` — weak per-server loaded-member index and conflict report.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingSelection.java` — 200-tick player binding selection persisted in player persistent data.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingService.java` — authoritative bind/copy validation.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlock.java` — interaction, wrench selection, custom configured drops.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlockEntity.java` — panel/cluster IDs, ordered bindings, index lifecycle.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlockItem.java` — append/clear Create logistics bindings on the unplaced panel.
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/LogisticsBindingTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/SpaceAddressTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMemberIndexTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingServiceTest.java`
- `src/main/resources/assets/create_biotech/blockstates/factory_panel.json`
- `src/main/resources/assets/create_biotech/models/block/factory_panel.json`
- `src/main/resources/assets/create_biotech/models/item/factory_panel.json`
- `src/main/resources/data/create_biotech/loot_table/blocks/factory_panel.json`

**Modify**

- `build.gradle` — JUnit Jupiter dependencies and platform launcher.
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlocks.java` — `FACTORY_PANEL`.
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBItems.java` — custom panel item.
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlockEntityTypes.java` — panel BE type.
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBCreativeModeTabs.java` — panel entry.
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBConfigs.java` — complete factory-cluster server defaults.
- `src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java` — pickaxe tag.
- `src/main/resources/assets/create_biotech/lang/en_us.json`
- `src/main/resources/assets/create_biotech/lang/zh_cn.json`

### Task 1: Add Unit-Test Support and Binding Value Types

**Files:**
- Modify: `build.gradle:74-111`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/LogisticsBinding.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBinding.java`
- Test: `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/LogisticsBindingTest.java`

**Interfaces:**
- Produces: `LogisticsBinding.normalize(List<LogisticsBinding>)`, strict binding/address decoders, and the versioned `ClusterBinding` value used only through `ClusterBindingService` transactions.

- [ ] **Step 1: Configure JUnit Jupiter**

Add to `dependencies`:

```groovy
testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
```

Add after the dependency block:

```groovy
tasks.named('test', Test).configure {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write failing normalization tests**

```java
class LogisticsBindingTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void normalizePreservesFirstOccurrenceAndOrder() {
		List<LogisticsBinding> actual = LogisticsBinding.normalize(List.of(
			new LogisticsBinding(A, " first "),
			new LogisticsBinding(B, "second"),
			new LogisticsBinding(A, "replacement")));
		assertEquals(List.of(new LogisticsBinding(A, "first"),
			new LogisticsBinding(B, "second")), actual);
	}

	@Test
	void emptyAliasFallsBackToShortNetworkId() {
		assertEquals("00000000", new LogisticsBinding(A, "  ").alias());
	}
}
```

- [ ] **Step 3: Run the focused test and confirm failure**

Run: `./gradlew.bat test --tests "*LogisticsBindingTest" --offline`

Expected: compilation fails because `LogisticsBinding` does not exist.

- [ ] **Step 4: Implement immutable normalized bindings and NBT**

Use these exact public shapes:

```java
public record LogisticsBinding(UUID logisticsId, String alias) {
	public static final int MAX_ALIAS_LENGTH = 32;

	public LogisticsBinding {
		Objects.requireNonNull(logisticsId, "logisticsId");
		alias = StringUtil.truncateStringIfNecessary(alias == null ? "" : alias.strip(),
			MAX_ALIAS_LENGTH, false);
		if (alias.isEmpty())
			alias = logisticsId.toString().substring(0, 8);
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("Id", logisticsId);
		tag.putString("Alias", alias);
		return tag;
	}

	public static Optional<LogisticsBinding> load(CompoundTag tag) {
		return tag.hasUUID("Id")
			? Optional.of(new LogisticsBinding(tag.getUUID("Id"), tag.getString("Alias")))
			: Optional.empty();
	}

	public static List<LogisticsBinding> normalize(List<LogisticsBinding> input) {
		LinkedHashMap<UUID, LogisticsBinding> ordered = new LinkedHashMap<>();
		for (LogisticsBinding binding : input)
			if (binding != null)
				ordered.putIfAbsent(binding.logisticsId(), binding);
		return List.copyOf(ordered.values());
	}
}

public record ClusterBinding(UUID clusterId, long revision,
	@Nullable ClusterAuthority authority, List<LogisticsBinding> logisticsBindings) {
	public static final int MAX_BINDINGS = 32;

	public ClusterBinding {
		Objects.requireNonNull(clusterId, "clusterId");
		logisticsBindings = LogisticsBinding.normalize(logisticsBindings);
		if (revision < 0 || logisticsBindings.size() > MAX_BINDINGS)
			throw new IllegalArgumentException("invalid cluster binding state");
	}
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew.bat test --tests "*LogisticsBindingTest" --offline`

Expected: `BUILD SUCCESSFUL`, two tests pass.

- [ ] **Step 6: Commit**

```powershell
git add build.gradle src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/LogisticsBinding.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBinding.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/LogisticsBindingTest.java
git commit -m "test: add factory cluster value types"
```

### Task 2: Add Sable-Aware Addresses and the Weak Loaded-Member Index

**Files:**
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/SpaceAddress.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMemberType.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMember.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMemberIndex.java`
- Test: `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMemberIndexTest.java`

**Interfaces:**
- Consumes: `LogisticsBinding` from Task 1 and `SubLevelCompat`.
- Produces: `SpaceAddress.capture`, `resolveBlockEntity`, `worldCenter`; `ClusterMemberIndex.register`, `unregister`, `members`, `conflicts`.

- [ ] **Step 1: Write failing index tests**

Use a package-private `ServerIndex` constructor so unit tests do not need a real `MinecraftServer`:

```java
@Test
void oldInstanceCannotUnregisterReplacementAtSameStableId() {
	ClusterMemberIndex.ServerIndex index = new ClusterMemberIndex.ServerIndex();
	FakeMember oldSource = member(PANEL_ID, CLUSTER_ID, ClusterMemberType.PANEL);
	FakeMember movedTarget = member(PANEL_ID, CLUSTER_ID, ClusterMemberType.PANEL);
	index.register(oldSource);
	index.register(movedTarget);
	index.unregister(oldSource);
	assertSame(movedTarget, index.members(CLUSTER_ID, ClusterMemberType.PANEL).getFirst());
}

@Test
void twoCoordinatorsReportConflictButPanelsDoNot() {
	ClusterMemberIndex.ServerIndex index = new ClusterMemberIndex.ServerIndex();
	index.register(member(UUID.randomUUID(), CLUSTER_ID, ClusterMemberType.PANEL));
	index.register(member(UUID.randomUUID(), CLUSTER_ID, ClusterMemberType.PANEL));
	assertFalse(index.conflicts(CLUSTER_ID).panelConflict());
	index.register(member(UUID.randomUUID(), CLUSTER_ID, ClusterMemberType.COMPUTER_COORDINATOR));
	index.register(member(UUID.randomUUID(), CLUSTER_ID, ClusterMemberType.COMPUTER_COORDINATOR));
	assertTrue(index.conflicts(CLUSTER_ID).computerConflict());
}
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run: `./gradlew.bat test --tests "*ClusterMemberIndexTest" --offline`

Expected: compilation fails because the index types do not exist.

- [ ] **Step 3: Implement the address contract using existing compatibility code**

```java
public record SpaceAddress(ResourceKey<Level> dimension, @Nullable UUID subLevelId, BlockPos localPos) {
	public static SpaceAddress capture(Level level, BlockPos pos) {
		return new SpaceAddress(level.dimension(), SubLevelCompat.getSpaceId(level, pos), pos.immutable());
	}

	public boolean matches(Level level, BlockPos pos) {
		return level.dimension().equals(dimension) && localPos.equals(pos)
			&& SubLevelCompat.matchesSpace(level, pos, subLevelId);
	}

	@Nullable
	public BlockEntity resolveBlockEntity(MinecraftServer server) {
		ServerLevel level = server.getLevel(dimension);
		return level == null ? null
			: SubLevelCompat.resolveBlockEntityFast(level, localPos, subLevelId);
	}

	public Vec3 worldCenter(ServerLevel level) {
		if (!level.dimension().equals(dimension))
			throw new IllegalArgumentException("Address belongs to another root dimension");
		return SubLevelCompat.toWorld(level, localPos, Vec3.atCenterOf(localPos));
	}
}
```

Add explicit `save()`/strict `tryLoad(CompoundTag)` using keys `Dimension`, `SubLevel`, and `Pos`; `tryLoad(tag, expectedDimension)` also rejects a root-dimension mismatch. `resolveBlockEntity` remains non-loading. Add a later GameTest case proving an unloaded address does not load its chunk and an outer-world/null-sublevel address rejects Sable plot-grid positions.

- [ ] **Step 4: Implement member/index types**

```java
public enum ClusterMemberType { PANEL, PATTERN_CORE, COMPUTER_COORDINATOR }

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
	void commitClusterBinding(ClusterBinding prepared);
}
```

`ClusterMemberIndex.ServerIndex` stores `Map<UUID, Map<MemberKey, WeakReference<ClusterMember>>>`. `MemberKey` is `(ClusterMemberType type, UUID memberId)`. `unregister` removes only when `reference.get() == member`; `members` removes cleared references while iterating. `conflicts` returns:

```java
public record ConflictReport(boolean patternConflict, boolean computerConflict,
	boolean panelConflict, boolean bindingConflict) {
	public boolean blocksNewTasks() {
		return patternConflict || computerConflict || bindingConflict;
	}
}
```

The public static façade is:

```java
public static void register(MinecraftServer server, ClusterMember member);
public static void unregister(MinecraftServer server, ClusterMember member);
public static List<ClusterMember> members(MinecraftServer server, UUID clusterId, ClusterMemberType type);
public static ConflictReport conflicts(MinecraftServer server, UUID clusterId);
```

- [ ] **Step 5: Run tests**

Run: `./gradlew.bat test --tests "*ClusterMemberIndexTest" --offline`

Expected: `BUILD SUCCESSFUL`; replacement-safe unregister and conflict rules pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterMemberIndexTest.java
git commit -m "feat: add transient factory cluster discovery"
```

### Task 3: Add Authoritative Cluster Binding Selection and Rules

**Files:**
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingSelection.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingService.java`
- Test: `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingServiceTest.java`

**Interfaces:**
- Consumes: `ClusterMember`, `SpaceAddress`, `ClusterMemberIndex`, `Create.LOGISTICS.mayAdministrate`.
- Produces: `ClusterBindingSelection.begin/resolve/clear`, `ClusterBindingService.bind` and `BindResult`.

- [ ] **Step 1: Write failing rule tests around a pure permission callback**

```java
@Test
void bindRejectsDifferentRootDimension() {
	BindResult result = ClusterBindingService.validate(source, targetInNether, id -> true,
		List.of(source, targetInNether));
	assertEquals(BindResult.DIMENSION, result);
}

@Test
void bindRequiresAdministrationOfEveryOldAndNewNetwork() {
	BindResult result = ClusterBindingService.validate(source, target, id -> !id.equals(LOCKED),
		List.of(source, target));
	assertEquals(BindResult.PERMISSION, result);
}

@Test
void activeMemberPreventsRebinding() {
	target.rebindable = false;
	assertEquals(BindResult.ACTIVE,
		ClusterBindingService.validate(source, target, id -> true, List.of(source, target)));
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*ClusterBindingServiceTest" --offline`

Expected: compilation fails because the binding service does not exist.

- [ ] **Step 3: Implement selection persistence without a global map**

Store under `player.getPersistentData().getCompound("create_biotech:factory_cluster_binding")`:

```java
public static void begin(ServerPlayer player, ClusterMember source) {
	CompoundTag tag = source.memberAddress().save();
	tag.putUUID("MemberId", source.memberId());
	tag.putLong("Expires", player.serverLevel().getGameTime() + 200);
	player.getPersistentData().put(KEY, tag);
}

public static Optional<ClusterMember> resolve(ServerPlayer player) {
	// Reject and clear expired, unloaded, moved-to-a-different-space, or ID-mismatched sources.
}
```

Resolution uses `SpaceAddress.resolveBlockEntity`, then requires the BE to implement `ClusterMember` and match `memberId`. It never scans levels or loads chunks.

- [ ] **Step 4: Implement binding validation and application**

```java
public enum BindResult {
	OK, NO_SOURCE, ACTIVE, DIMENSION, PERMISSION, EMPTY_NETWORKS,
	TOO_MANY_BINDINGS, AUTHORITY_OFFLINE, STALE_BINDING,
	PARTICIPANT_REJECTED, CONFLICT;

	public boolean succeeded() {
		return this == OK;
	}
}

static BindResult validate(ClusterMember source, ClusterMember target,
	Predicate<UUID> mayAdministrate, Collection<ClusterMember> loadedClusterMembers) {
	if (source.clusterId() == null)
		return BindResult.NO_SOURCE;
	if (!source.canRebind() || !target.canRebind()
		|| loadedClusterMembers.stream().anyMatch(member -> !member.canRebind()))
		return BindResult.ACTIVE;
	if (!source.memberAddress().dimension().equals(target.memberAddress().dimension()))
		return BindResult.DIMENSION;
	LinkedHashSet<UUID> allIds = new LinkedHashSet<>();
	source.logisticsBindings().forEach(binding -> allIds.add(binding.logisticsId()));
	target.logisticsBindings().forEach(binding -> allIds.add(binding.logisticsId()));
	if (allIds.isEmpty())
		return BindResult.EMPTY_NETWORKS;
	return allIds.stream().allMatch(mayAdministrate) ? BindResult.OK : BindResult.PERMISSION;
}
```

The server entrypoint obtains all loaded members for both old clusters, invokes `Create.LOGISTICS.mayAdministrate(id, player)` for every old and proposed network, resolves every member-local authority, creates revision `max(old)+1`, prepares every participant, then commits the one exact immutable state. It clears player selection only after all commits. `replaceBindings(ServerPlayer, ClusterMember, List<LogisticsBinding>)` follows the same transaction and is the sole API for later UI edits.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew.bat test --tests "*ClusterBindingServiceTest" --offline`

Expected: `BUILD SUCCESSFUL`; dimension, permissions, and activity gates pass.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingSelection.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingService.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/ClusterBindingServiceTest.java
git commit -m "feat: add factory cluster binding rules"
```

### Task 4: Register the Multi-Network Factory Panel

**Files:**
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlock.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlockEntity.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlockItem.java`
- Modify: registry/resource files listed in the File Map.

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: registered `create_biotech:factory_panel` and a `ClusterMember` implementation used by plans 2–5.

- [ ] **Step 1: Add block, item, BE, creative-tab, and mining registrations**

Register exact fields:

```java
public static final DeferredHolder<Block, FactoryPanelBlock> FACTORY_PANEL =
	BLOCKS.register("factory_panel", () -> new FactoryPanelBlock(CBSharedProperties.createSoftMetal()
		.noOcclusion().mapColor(MapColor.COLOR_LIGHT_BLUE)));

public static final DeferredHolder<Item, FactoryPanelBlockItem> FACTORY_PANEL =
	ITEMS.register("factory_panel", () -> new FactoryPanelBlockItem(CBBlocks.FACTORY_PANEL.get(),
		new Item.Properties().stacksTo(1)));

public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactoryPanelBlockEntity>> FACTORY_PANEL =
	BLOCK_ENTITY_TYPES.register("factory_panel", () -> BlockEntityType.Builder
		.of(FactoryPanelBlockEntity::new, CBBlocks.FACTORY_PANEL.get()).build(null));
```

Add the item to `CBCreativeModeTabs.MAIN` immediately after `WIRELESS_TERMINAL`; add the block to `MINEABLE_WITH_PICKAXE`.

- [ ] **Step 2: Implement panel persistence and index lifecycle**

The BE extends `SmartBlockEntity` and implements `ClusterMember`. Use NBT keys:

```text
PanelId: UUID
ClusterId: UUID
LogisticsBindings: List<CompoundTag>
SelectedNetwork: UUID (optional UI preference)
```

Constructor creates stable `panelId` plus an initial self-authoritative version-1 binding state. `read` strictly decodes `BindingState`, migrates legacy `ClusterId`/`LogisticsBindings`, and marks malformed or oversized persisted state invalid. `initialize` registers and reconciles from a loaded authority; `invalidate` unregisters before `super.invalidate()`. `prepareClusterBinding` only validates identity/revision/cap/activity. `commitClusterBinding` performs the already-validated rekey, assignment, dirty mark, and sync without a second refusal path.

Use this Sable-aware interaction check:

```java
@Override
public boolean canPlayerUse(Player player) {
	if (level == null || level.getBlockEntity(worldPosition) != this)
		return false;
	return SubLevelCompat.canEntityInteractWith(level, worldPosition, player)
		&& SubLevelCompat.distanceSquared(level, Vec3.atCenterOf(worldPosition), player.position()) <= 64.0;
}
```

- [ ] **Step 3: Implement item-side multi-network tuning**

`FactoryPanelBlockItem.useOn` checks `BlockEntityBehaviour.get(level, pos, LogisticallyLinkedBehaviour.TYPE)` before calling `super.useOn`. When a link exists:

```java
if (!link.mayInteractMessage(player))
	return InteractionResult.SUCCESS;
List<LogisticsBinding> bindings = readBindings(stack);
bindings.add(new LogisticsBinding(link.freqId, link.freqId.toString().substring(0, 8)));
writeBindings(stack, LogisticsBinding.normalize(bindings));
```

Write the list to `DataComponents.BLOCK_ENTITY_DATA` under `LogisticsBindings`, call `BlockEntity.addEntityType` with `CBBlockEntityTypes.FACTORY_PANEL`, and preserve unrelated BE keys. `use` in air clears only `LogisticsBindings` after server-side administration checks for every stored ID; it does not remove `PanelId` or `ClusterId`. `isFoil` is true when the list is non-empty.

- [ ] **Step 4: Implement block interaction and configured drops**

The block implements `IBE<FactoryPanelBlockEntity>` and `IWrenchable`, has `HORIZONTAL_FACING`, and supplies a server ticker through `getTicker`. Sneak-wrench behavior:

```java
Optional<ClusterMember> selected = ClusterBindingSelection.resolve(player);
if (selected.isPresent())
	return ClusterBindingService.bind(player, selected.get(), panel).succeeded()
		? InteractionResult.SUCCESS : InteractionResult.FAIL;
ClusterBindingSelection.begin(player, panel);
return InteractionResult.SUCCESS;
```

Override protected `getDrops` to read `LootContextParams.BLOCK_ENTITY`, call `FactoryPanelBlockEntity.asConfiguredStack()`, and return one stack. `asConfiguredStack` uses `saveWithoutMetadata(registries)` plus `BlockItem.setBlockEntityData`; this preserves panel/cluster/bindings without a synchronous save.

- [ ] **Step 5: Add exact model/loot/lang resources**

`models/block/factory_panel.json`:

```json
{"parent":"create:block/stock_ticker"}
```

`models/item/factory_panel.json`:

```json
{"parent":"create_biotech:block/factory_panel"}
```

Use four horizontal blockstate variants with y rotations `0`, `90`, `180`, `270`. The loot table is an empty block table because `FactoryPanelBlock#getDrops` is authoritative:

```json
{"type":"minecraft:block","pools":[]}
```

Add translations for the block name and binding success/refusal results in both locale files.

- [ ] **Step 6: Compile and commit**

Run: `./gradlew.bat compileJava --offline`

Expected: `BUILD SUCCESSFUL`; no client-only classes load on the dedicated-server classpath.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel src/main/java/com/nobodiiiii/createbiotech/registry src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java src/main/resources/assets/create_biotech src/main/resources/data/create_biotech/loot_table/blocks/factory_panel.json
git commit -m "feat: register multi-network factory panel"
```

### Task 5: Add Shared Server Configuration

**Files:**
- Modify: `src/main/java/com/nobodiiiii/createbiotech/registry/CBConfigs.java:85-145`

**Interfaces:**
- Produces: `CBConfigs.SERVER.factoryCluster` with every approved default used by plans 2–4.

- [ ] **Step 1: Add the config object to `Server`**

```java
public final FactoryCluster factoryCluster;
// In Server constructor, before features:
factoryCluster = new FactoryCluster(builder);
```

- [ ] **Step 2: Define bounded values**

```java
public static class FactoryCluster {
	public final ModConfigSpec.IntValue computerMinSize;
	public final ModConfigSpec.IntValue computerMaxSize;
	public final ModConfigSpec.IntValue computerMaxNodes;
	public final ModConfigSpec.IntValue libraryMaxMembers;
	public final ModConfigSpec.IntValue libraryMaxSpan;
	public final ModConfigSpec.IntValue patternBaseRange;
	public final ModConfigSpec.IntValue wanderingTraderRangeBonus;
	public final ModConfigSpec.IntValue patternMaxRange;
	public final ModConfigSpec.IntValue patternMaxPagesPerTick;

	FactoryCluster(ModConfigSpec.Builder builder) {
		builder.push("factoryCluster");
		computerMinSize = builder.defineInRange("computerMinSize", 3, 3, 7);
		computerMaxSize = builder.defineInRange("computerMaxSize", 7, 3, 16);
		computerMaxNodes = builder.defineInRange("computerMaxNodes", 32, 1, 256);
		libraryMaxMembers = builder.defineInRange("libraryMaxMembers", 64, 1, 1024);
		libraryMaxSpan = builder.defineInRange("libraryMaxSpan", 16, 1, 128);
		patternBaseRange = builder.defineInRange("patternBaseRange", 64, 1, 4096);
		wanderingTraderRangeBonus = builder.defineInRange("wanderingTraderRangeBonus", 64, 0, 4096);
		patternMaxRange = builder.defineInRange("patternMaxRange", 512, 1, 16384);
		patternMaxPagesPerTick = builder.defineInRange("patternMaxPagesPerTick", 600, 1, 10000);
		builder.pop();
	}
}
```

Consumers clamp `computerMinSize <= computerMaxSize` and `patternBaseRange <= patternMaxRange` at read time; they do not mutate config values.

- [ ] **Step 3: Compile and commit**

Run: `./gradlew.bat compileJava --offline`

Expected: `BUILD SUCCESSFUL`.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/registry/CBConfigs.java
git commit -m "feat: configure factory computing limits"
```

### Task 6: Foundation Verification Gate

**Files:**
- Review only.

**Interfaces:**
- Produces: a stable base for the remaining four plans.

- [ ] **Step 1: Run all foundation tests**

Run: `./gradlew.bat test --tests "*factorycluster*" --offline`

Expected: all binding/index/rule tests pass.

- [ ] **Step 2: Build and inspect resources**

Run: `./gradlew.bat build --offline`

Expected: `BUILD SUCCESSFUL`; `factory_panel` registry and resources resolve.

- [ ] **Step 3: Check forbidden architecture and whitespace**

```powershell
git diff --check
git grep -n -E "SavedData|DimensionDataStorage|LogisticallyLinkedBehaviour\(" -- src/main/java/com/nobodiiiii/createbiotech/content/factorycluster
```

Expected: no whitespace errors; only legitimate references to `LogisticallyLinkedBehaviour.TYPE` from the item are present, and there is no `SavedData` or behaviour construction.
