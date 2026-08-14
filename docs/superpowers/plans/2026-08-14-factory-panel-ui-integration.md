# Factory Panel UI and Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the factory computing cluster through a two-page Create-native Factory Panel that reads one or many Create logistics networks, submits real-time orders to one explicit network/address, and displays current tasks and cluster hardware without a scheduling log.

**Architecture:** `FactoryPanelMenu` only proves that the player still has one concrete panel open. Every mutation packet resolves that menu and panel again, derives the cluster from the server-side BE, and rechecks Create permissions. Bounded server snapshots are generation-tagged and chunked; the warehouse page may merge multiple networks for display but never for ordering, while the task/cluster page is a live read model of loaded local runtimes rather than a persistent UI database.

**Tech Stack:** Java 21, Minecraft 1.21.1 menus and `RegistryFriendlyByteBuf`, NeoForge 21.1.234, Create 6.0.10-281 `AbstractSimiContainerScreen`, `AllGuiTextures`, `IconButton`, `SelectionScrollInput`, `ScrollInput`, `AddressEditBox`, Catnip packet registry, JUnit Jupiter 5.11.4, NeoForge GameTest, `SubLevelCompat`.

## Global Constraints

- Requires plans 1–4 and executes last on branch `feature/factory-computing-cluster`.
- The screen has exactly two pages: `WAREHOUSE` and `CLUSTER`; live tasks and cluster hardware share `CLUSTER`.
- A panel stores an ordered, UUID-deduplicated list of Create logistics bindings. `ALL_NETWORKS` is read-only; an order always names exactly one bound logistics UUID.
- Every order carries an explicit non-blank final delivery address of at most `25` characters, matching `AddressEditBox#setMaxLength(25)`.
- Stock truth comes only from `LogisticsManager.getSummaryOfNetwork(logisticsId, true)`; promises, packages in transit, task outputs, and private inventories are never shown as available stock.
- The server derives `clusterId` from the opened panel. No client packet contains or selects a cluster UUID.
- Revalidate container ID, block position, Sable space identity, loaded BE identity, panel membership, Create permissions, packet sizes, selected network, item count, and address on the server.
- Use Create GUI classes and `AllGuiTextures.STOCK_KEEPER_REQUEST_*`; do not copy AE source/assets and do not add LDLib or another UI framework.
- The cluster view shows current state only. Do not store or transmit a chronological scheduling log.
- Client snapshots are ephemeral, generation-tagged, size-bounded, and cleared when the menu closes.
- No chunk tickets, synchronous saves, whole-world saves, disk rereads, or IO-worker waits.
- Preserve every existing packet ID. Append the final packet set, then change `CBPackets.NETWORK_VERSION` exactly once from `15` to `16`.
- Use `SubLevelCompat.matchesSpace` when resolving the open panel and `SubLevelCompat` conversions for any world-space endpoint shown by the UI.

---

## File Map

**Create — panel menu and UI**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelMenu.java` — server/client menu construction and open-panel identity.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelTab.java` — exactly two stable tab IDs.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelScreen.java` — Create-native shell, warehouse page, live task/cluster page, polling, and ephemeral client state.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelMenuValidator.java` — authoritative server resolution and permission checks shared by all packets.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelSnapshotService.java` — bounded stock/current-runtime read models.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelStockEntry.java` — one exact item stack count in one logistics network plus proxy flags.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelFrameView.java` — compact current frame row, no history.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelNodeView.java` — current computer state/profile/capacity.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelLibraryView.java` — current pattern-core availability, pages, capacity, range, search budget, queue count, and bounded bad-page locations.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelStockSnapshot.java` — generation accumulator and exact-component aggregation.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelClusterSnapshot.java` — generation accumulator for current roots/frames/nodes/library/conflicts.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelResultCode.java` — bounded translated result code, never arbitrary server text.

**Create — packets**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelRefreshPacket.java` — serverbound stock/cluster refresh request.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelOrderPacket.java` — serverbound finite/continuous order.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelControlPacket.java` — serverbound cancel, stop/reform, retry-uncertain, and terminal acknowledgement.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBindingPacket.java` — serverbound alias/remove/reorder operations.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelStockPacket.java` — clientbound chunked stock snapshot.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelClusterPacket.java` — clientbound chunked current-runtime snapshot.
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelResultPacket.java` — clientbound accepted/rejected/completed result.

**Tests**

- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelSnapshotTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelRequestRulesTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelPacketLimitsTest.java`
- `src/main/java/com/nobodiiiii/createbiotech/gametest/FactoryPanelGameTests.java`

**Modify**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlock.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlockEntity.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ComputerNodeRuntime.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBMenuTypes.java`
- `src/main/java/com/nobodiiiii/createbiotech/network/CBPackets.java`
- `src/main/java/com/nobodiiiii/createbiotech/network/CBClientPacketHandlers.java`
- `src/main/java/com/nobodiiiii/createbiotech/client/CreateBiotechClient.java`
- `src/main/resources/assets/create_biotech/lang/en_us.json`
- `src/main/resources/assets/create_biotech/lang/zh_cn.json`

---

### Task 1: Define Bounded Current-State View Models

**Files:**
- Create: `FactoryPanelTab.java`
- Create: `FactoryPanelStockEntry.java`
- Create: `FactoryPanelFrameView.java`
- Create: `FactoryPanelNodeView.java`
- Create: `FactoryPanelLibraryView.java`
- Create: `FactoryPanelStockSnapshot.java`
- Create: `FactoryPanelClusterSnapshot.java`
- Create: `FactoryPanelResultCode.java`
- Test: `FactoryPanelSnapshotTest.java`

**Interfaces:**
- Consumes: `StackKey`, `FrameState`, `FrameReason`, `ComputerState`, `OrderMode`, `ComputerProfile`, `LogisticsBinding`.
- Produces: immutable view records; `FactoryPanelStockSnapshot.accept(FactoryPanelStockPacket)`; `FactoryPanelClusterSnapshot.accept(FactoryPanelClusterPacket)`; `FactoryPanelStockSnapshot.rows(@Nullable UUID)`.

- [ ] **Step 1: Write failing exact-component and generation tests**

```java
class FactoryPanelSnapshotTest {
	private static final UUID NET_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID NET_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void allNetworksAggregatesDisplayCountButRetainsPerNetworkBreakdown() {
		ItemStack named = new ItemStack(Items.IRON_INGOT);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Batch A"));
		FactoryPanelStockSnapshot snapshot = new FactoryPanelStockSnapshot();
		snapshot.begin(7, List.of(NET_A, NET_B));
		snapshot.append(7, List.of(
			new FactoryPanelStockEntry(NET_A, named, 3, true, false),
			new FactoryPanelStockEntry(NET_B, named, 5, false, true)), true);
		FactoryPanelStockSnapshot.DisplayRow row = snapshot.rows(null).getFirst();
		assertEquals(8, row.total());
		assertEquals(Map.of(NET_A, 3, NET_B, 5), row.byNetwork());
	}

	@Test
	void staleChunkCannotReplaceNewGeneration() {
		FactoryPanelStockSnapshot snapshot = new FactoryPanelStockSnapshot();
		snapshot.begin(9, List.of(NET_A));
		assertFalse(snapshot.append(8, List.of(), true));
		assertEquals(9, snapshot.generation());
	}
}
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run: `./gradlew.bat test --tests "*FactoryPanelSnapshotTest" --offline`

Expected: compilation fails because the snapshot/view types do not exist.

- [ ] **Step 3: Add exact stable record shapes**

```java
public enum FactoryPanelTab { WAREHOUSE, CLUSTER }

public record FactoryPanelStockEntry(UUID logisticsId, ItemStack stack, int count,
	boolean physicalProxy, boolean patternProxy) {
	public FactoryPanelStockEntry {
		Objects.requireNonNull(logisticsId, "logisticsId");
		stack = stack.copyWithCount(1);
		count = Mth.clamp(count, 0, BigItemStack.INF);
	}
}

public record FactoryPanelFrameView(UUID jobId, UUID frameId, @Nullable UUID parentFrameId,
	UUID computerId, ItemStack output, int requested, int completed, int depth,
	FrameState state, FrameReason reason, OrderMode mode, String deliveryAddress) {}

public record FactoryPanelNodeView(UUID computerId, ComputerState state,
	NodeKind kind, int usedSlots, int totalSlots, int maxDepth, int currentDepth,
	boolean coordinator, boolean online) {}

public record FactoryPanelLibraryView(boolean present, boolean online, int pages,
	int capacity, int indexedPatterns, int queuedPages, int range, int searchBudget,
	int queueCount, boolean scanning, List<PatternPageError> pageErrors) {
	public FactoryPanelLibraryView {
		pageErrors = List.copyOf(pageErrors.subList(0, Math.min(pageErrors.size(), 128)));
	}
}

public enum FactoryPanelResultCode {
	ORDER_ACCEPTED, ORDER_COMPLETED, CANCELLED, REFORMED, RETRY_ARMED,
	INVALID_MENU, WRONG_SPACE, NO_PERMISSION, UNKNOWN_NETWORK, READ_ONLY_ALL,
	INVALID_ITEM, INVALID_AMOUNT, INVALID_ADDRESS, CLUSTER_UNBOUND,
	CLUSTER_CONFLICT, COORDINATOR_OFFLINE, JOB_NOT_FOUND, RETRY_NOT_UNCERTAIN,
	PACKET_TOO_LARGE, INTERNAL_ERROR
}
```

`FactoryPanelStockSnapshot` keys rows with `StackKey`, not item ID. It keeps `Map<UUID,Integer>` per row so selecting a network changes the visible/orderable count without requesting another snapshot. `FactoryPanelClusterSnapshot` replaces its data only after the final chunk for one generation; until then the screen retains the prior complete generation.

Hard limits used by both encoders and decoders:

```java
static final int MAX_BINDINGS = ClusterBinding.MAX_BINDINGS;
static final int MAX_STOCK_ENTRIES_PER_CHUNK = 100;
static final int MAX_ROOTS = 128;
static final int MAX_FRAMES = 512;
static final int MAX_NODES = 32;
static final int MAX_RESULTS = 128;
static final int MAX_PATTERN_ERRORS = 128;
static final int MAX_ALIAS_LENGTH = 32;
static final int MAX_SEARCH_LENGTH = 50;
```

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew.bat test --tests "*FactoryPanelSnapshotTest" --offline`

Expected: `BUILD SUCCESSFUL`; exact components remain distinct, all-network display totals retain per-network counts, and stale generations are ignored.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelTab.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelStockEntry.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelFrameView.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelNodeView.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelLibraryView.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelStockSnapshot.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelClusterSnapshot.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelResultCode.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelSnapshotTest.java
git commit -m "feat: define factory panel read models"
```

### Task 2: Open One Concrete Panel Menu and Revalidate Every Request

**Files:**
- Create: `FactoryPanelMenu.java`
- Create: `FactoryPanelMenuValidator.java`
- Modify: `FactoryPanelBlock.java`
- Modify: `FactoryPanelBlockEntity.java`
- Modify: `CBMenuTypes.java`
- Test: `FactoryPanelRequestRulesTest.java`

**Interfaces:**
- Consumes: `FactoryPanelBlockEntity`, `ClusterMemberIndex`, `SubLevelCompat`, `Create.LOGISTICS.mayInteract`, `Create.LOGISTICS.mayAdministrate`.
- Produces: `FactoryPanelMenu.create`; `FactoryPanelMenu.panel()`; `FactoryPanelMenuValidator.resolve`; `PanelAccess`.

- [ ] **Step 1: Write failing request-rule tests**

```java
class FactoryPanelRequestRulesTest {
	@Test
	void allNetworksCannotBeOrdered() {
		assertEquals(FactoryPanelResultCode.READ_ONLY_ALL,
			FactoryPanelMenuValidator.validateOrderNetwork(null, List.of(binding(NET_A))));
	}

	@Test
	void unboundNetworkIsRejected() {
		assertEquals(FactoryPanelResultCode.UNKNOWN_NETWORK,
			FactoryPanelMenuValidator.validateOrderNetwork(NET_B, List.of(binding(NET_A))));
	}

	@Test
	void finalAddressIsMandatoryAndBounded() {
		assertFalse(FactoryPanelMenuValidator.validAddress(" "));
		assertTrue(FactoryPanelMenuValidator.validAddress("Line A / Output"));
		assertFalse(FactoryPanelMenuValidator.validAddress("x".repeat(26)));
	}
}
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run: `./gradlew.bat test --tests "*FactoryPanelRequestRulesTest" --offline`

Expected: compilation fails because `FactoryPanelMenuValidator` does not exist.

- [ ] **Step 3: Implement the menu using the existing Create `MenuBase` pattern**

```java
public class FactoryPanelMenu extends MenuBase<FactoryPanelBlockEntity> {
	public FactoryPanelMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extraData) {
		super(CBMenuTypes.FACTORY_PANEL.get(), id, inventory, extraData);
	}

	public FactoryPanelMenu(int id, Inventory inventory, FactoryPanelBlockEntity panel) {
		super(CBMenuTypes.FACTORY_PANEL.get(), id, inventory, panel);
	}

	public static AbstractContainerMenu create(int id, Inventory inventory,
		FactoryPanelBlockEntity panel) {
		return new FactoryPanelMenu(id, inventory, panel);
	}
}
```

Implement `createOnClient` by reading `BlockPos`, optional sublevel UUID, and the panel update tag. Reuse a loaded client `FactoryPanelBlockEntity` only when `SubLevelCompat.matchesSpace` succeeds; otherwise create a client-only BE with `CBBlockEntityTypes.FACTORY_PANEL`, set its level, and apply the update tag. `addSlots` places player slots off-screen as the existing stock request menu does; `quickMoveStack` returns `ItemStack.EMPTY`; `stillValid` requires a live panel and server-side distance/permission.

`FactoryPanelBlockEntity` implements `MenuProvider`, creates `FactoryPanelMenu`, and writes menu data in this exact order:

```java
buffer.writeBlockPos(getBlockPos());
buffer.writeBoolean(memberAddress().subLevelId() != null);
if (memberAddress().subLevelId() != null)
	buffer.writeUUID(memberAddress().subLevelId());
buffer.writeNbt(getUpdateTag(registries));
```

`FactoryPanelBlock#useWithoutItem` opens the menu on the server only after every currently bound network passes `Create.LOGISTICS.mayInteract`. Sneak-wrench binding remains handled before normal menu opening.

- [ ] **Step 4: Implement one shared authoritative resolver**

```java
public record PanelAccess(ServerPlayer player, FactoryPanelMenu menu,
	FactoryPanelBlockEntity panel, UUID clusterId, List<LogisticsBinding> bindings) {}

public record ResolveResult(@Nullable PanelAccess access,
	@Nullable FactoryPanelResultCode rejection) {
	public static ResolveResult allowed(PanelAccess access) {
		return new ResolveResult(access, null);
	}

	public static ResolveResult rejected(FactoryPanelResultCode code) {
		return new ResolveResult(null, code);
	}

	public boolean succeeded() { return access != null && rejection == null; }
}

public static ResolveResult resolve(
	ServerPlayer player, int containerId, BlockPos panelPos, @Nullable UUID expectedSpaceId,
	boolean administration) {
	if (!(player.containerMenu instanceof FactoryPanelMenu menu)
		|| menu.containerId != containerId)
		return ResolveResult.rejected(FactoryPanelResultCode.INVALID_MENU);
	FactoryPanelBlockEntity panel = menu.panel();
	if (panel == null || panel.isRemoved() || !panel.getBlockPos().equals(panelPos))
		return ResolveResult.rejected(FactoryPanelResultCode.INVALID_MENU);
	if (!SubLevelCompat.matchesSpace(panel.getLevel(), panelPos, expectedSpaceId))
		return ResolveResult.rejected(FactoryPanelResultCode.WRONG_SPACE);
	if (panel.clusterId() == null)
		return ResolveResult.rejected(FactoryPanelResultCode.CLUSTER_UNBOUND);
	boolean allowed = panel.logisticsBindings().stream().allMatch(binding -> administration
		? Create.LOGISTICS.mayAdministrate(binding.logisticsId(), player)
		: Create.LOGISTICS.mayInteract(binding.logisticsId(), player));
	return allowed
		? ResolveResult.allowed(new PanelAccess(player, menu, panel, panel.clusterId(), panel.logisticsBindings()))
		: ResolveResult.rejected(FactoryPanelResultCode.NO_PERMISSION);
}
```

The implementation also verifies player range through `panel.canPlayerUse(player)` and verifies that `menu.panel()` is the same instance returned by `SubLevelCompat.resolveBlockEntityFast(panel.getLevel(), panelPos, expectedSpaceId)`; never call `level.getChunk` directly.

- [ ] **Step 5: Register the menu and run tests**

Append to `CBMenuTypes`:

```java
public static final DeferredHolder<MenuType<?>, MenuType<FactoryPanelMenu>> FACTORY_PANEL =
	MENU_TYPES.register("factory_panel", () -> IMenuTypeExtension.create(FactoryPanelMenu::new));
```

Run: `./gradlew.bat test --tests "*FactoryPanelRequestRulesTest" --offline`

Expected: `BUILD SUCCESSFUL`; all-network, unknown-network, and address validation tests pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelMenu.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelMenuValidator.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlock.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBlockEntity.java src/main/java/com/nobodiiiii/createbiotech/registry/CBMenuTypes.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelRequestRulesTest.java
git commit -m "feat: open and validate factory panel menus"
```

### Task 3: Stream Actual Multi-Network Stock and Current Cluster State

**Files:**
- Create: `FactoryPanelSnapshotService.java`
- Create: `FactoryPanelRefreshPacket.java`
- Create: `FactoryPanelStockPacket.java`
- Create: `FactoryPanelClusterPacket.java`
- Test: `FactoryPanelPacketLimitsTest.java`

**Interfaces:**
- Consumes: Task 1 view records, `FactoryPanelMenuValidator.resolve`, `FactoryStockService`, `ClusterMemberIndex`, coordinator `ComputerNodeRuntime` live views, pattern index summary.
- Produces: `FactoryPanelSnapshotService.sendRefresh`; bounded packet constructors/readers/writers.

- [ ] **Step 1: Write failing size-limit tests**

```java
class FactoryPanelPacketLimitsTest {
	@Test
	void stockChunksNeverExceedOneHundredRows() {
		List<FactoryPanelStockEntry> entries = IntStream.range(0, 205)
			.mapToObj(i -> stockEntry(i)).toList();
		List<List<FactoryPanelStockEntry>> chunks = FactoryPanelStockPacket.partition(entries);
		assertEquals(List.of(100, 100, 5), chunks.stream().map(List::size).toList());
	}

	@Test
	void decoderRejectsOversizedChunkBeforeAllocatingEntries() {
		assertThrows(IllegalArgumentException.class,
			() -> FactoryPanelStockPacket.checkEntryCount(101));
	}
}
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run: `./gradlew.bat test --tests "*FactoryPanelPacketLimitsTest" --offline`

Expected: compilation fails because the packet classes do not exist.

- [ ] **Step 3: Define exact refresh and stock packet payloads**

```java
public record FactoryPanelRefreshPacket(int containerId, BlockPos panelPos,
	@Nullable UUID panelSpaceId, int generation, FactoryPanelTab tab,
	@Nullable UUID selectedLogisticsId) {}

public record FactoryPanelStockPacket(int containerId, int generation,
	List<LogisticsBinding> bindings, List<FactoryPanelStockEntry> entries,
	boolean lastChunk) {}
```

`FactoryPanelRefreshPacket.handle` calls the shared resolver with interaction permission. A non-null selection must occur in `PanelAccess.bindings`. A null selection means `ALL_NETWORKS` and is accepted for reads only. The service reads every selected network with:

```java
InventorySummary summary = LogisticsManager.getSummaryOfNetwork(logisticsId, true);
```

It emits one row per exact stack variant per network. Counts are saturated at `BigItemStack.INF`. Physical and virtual proxy flags are derived from loaded `ProcessingProxy` indexes and never change the stock count. Sort by binding order, registry ID, then component patch hash before partitioning into chunks of at most 100.

After validation, persist `FactoryPanelBlockEntity.selectedNetwork` only when it differs from the request; `null` clears the preference. Do not call `setChanged` on unchanged 20-tick refreshes. This is the sole server-persisted UI preference; search text, scroll offsets, and partial snapshots remain client-only.

- [ ] **Step 4: Define the current-state cluster packet without logs**

```java
public record FactoryPanelClusterPacket(int containerId, int generation,
	List<FactoryPanelFrameView> frames, List<FactoryPanelNodeView> nodes,
	FactoryPanelLibraryView library, boolean clusterConflict,
	boolean frozenEpoch, boolean lastChunk) {}
```

The snapshot includes at most 128 root jobs, 512 total frames, 32 nodes, one library summary, and 128 `PatternPageError` entries. Frames are sorted by job UUID then frame UUID for deterministic display; this is not a timestamped event log. When nodes or chunks are unloaded, retain their frozen epoch entry with `online=false`; never load the chunk to fill a screen. Each bad-page entry preserves its `SpaceAddress`, chiseled-bookshelf slot, page number, bounded reason, and 96-character detail so the player can locate the invalid JSON without receiving every page body.

- [ ] **Step 5: Encode and decode defensively**

Each packet follows the repository pattern: constructor from `RegistryFriendlyByteBuf`, `write(RegistryFriendlyByteBuf)`, and `handle`. Read counts as VarInt, validate against the constants before creating a list, copy item stacks, cap strings (`alias=32`, `address=25`), reject unknown enum ordinals, and send `PACKET_TOO_LARGE` instead of disconnecting for a valid menu request that exceeds a server snapshot cap.

Empty stock still sends one final packet so a previous generation can be cleared. Every packet carries `containerId`; the client handler ignores packets not matching the currently open `FactoryPanelMenu`.

- [ ] **Step 6: Run packet tests and commit**

Run: `./gradlew.bat test --tests "*FactoryPanelPacketLimitsTest" --offline`

Expected: `BUILD SUCCESSFUL`; partition sizes are `100,100,5`, and oversized counts are rejected before list allocation.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelSnapshotService.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelRefreshPacket.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelStockPacket.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelClusterPacket.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelPacketLimitsTest.java
git commit -m "feat: stream factory panel snapshots"
```

### Task 4: Submit Orders and Apply Explicit Cleanup Controls

**Files:**
- Create: `FactoryPanelOrderPacket.java`
- Create: `FactoryPanelControlPacket.java`
- Create: `FactoryPanelBindingPacket.java`
- Create: `FactoryPanelResultPacket.java`
- Modify: `ComputerNodeRuntime.java`
- Test: `FactoryPanelRequestRulesTest.java`

**Interfaces:**
- Consumes: `PanelAccess`, `RootOrder`, `OrderMode`, `NodeSelector`, runtime cancellation/reform/dispatch retry methods.
- Produces: `submitRoot(RootOrder)`; `cancelJob(UUID)`; `stopAllAndReform()`; `retryUncertain(UUID)`; `acknowledgeTerminal(UUID)`; panel binding mutations.

- [ ] **Step 1: Add failing authoritative-order tests**

```java
@Test
void sameAddressOnTwoNetworksStillCreatesDistinctTaskKeys() {
	RootOrder a = order(NET_A, "Shared Output");
	RootOrder b = order(NET_B, "Shared Output");
	assertNotEquals(a.logisticsId(), b.logisticsId());
	assertNotEquals(new TaskKey(a.logisticsId(), a.output()),
		new TaskKey(b.logisticsId(), b.output()));
}

@Test
void warningDoesNotPreventDirectStreamingSubmission() {
	OrderValidation validation = OrderValidation.warning(FactoryPanelResultCode.COORDINATOR_OFFLINE);
	assertTrue(validation.mayAttemptSubmission());
}
```

The second test encodes the approved no-preplanning model: informational warnings do not disable the submit action. The runtime itself returns a fatal rejection only when no valid root can own the order.

- [ ] **Step 2: Define exact serverbound operations**

```java
public record FactoryPanelOrderPacket(int containerId, BlockPos panelPos,
	@Nullable UUID panelSpaceId, UUID logisticsId, ItemStack output, int amount,
	OrderMode mode, String deliveryAddress) {}

public enum FactoryPanelControlAction {
	CANCEL_JOB, STOP_ALL_AND_REFORM, RETRY_UNCERTAIN, ACK_TERMINAL
}

public record FactoryPanelControlPacket(int containerId, BlockPos panelPos,
	@Nullable UUID panelSpaceId, FactoryPanelControlAction action,
	@Nullable UUID jobId) {}

public enum FactoryPanelBindingAction { RENAME, REMOVE, MOVE_UP, MOVE_DOWN }

public record FactoryPanelBindingPacket(int containerId, BlockPos panelPos,
	@Nullable UUID panelSpaceId, FactoryPanelBindingAction action,
	UUID logisticsId, String alias) {}
```

Order validation normalizes output to count one and rejects empty stacks, amounts outside `1..BigItemStack.INF`, null/unbound logistics IDs, blank/over-25 addresses, non-member panels, conflicts, and permission failure. It derives the current `epochId` and weakest eligible root owner from the server runtime, creates `jobId` and `iterationId` on the server, and sends only `FactoryPanelResultCode.ORDER_ACCEPTED` plus the resulting job UUID back to the opener.

An order is attempted immediately; there is no AE-style complete-plan confirmation screen. `FINITE` dispatches the final result once and ends. `CONTINUOUS` creates a fresh iteration after each successful final dispatch until cancelled.

- [ ] **Step 3: Separate processing and final Create dispatch request types**

The runtime adapter must use:

```java
RequestType.RESTOCK  // intermediate processing-proxy input dispatch
RequestType.PLAYER   // root final-result dispatch to deliveryAddress
```

Both calls name `RootOrder.logisticsId()`. Never choose a network by address text. Re-read actual network stock immediately before each dispatch.

- [ ] **Step 4: Implement control and cleanup semantics**

- `CANCEL_JOB` broadcasts cancellation only to loaded frozen-epoch members and leaves confirmed packages alone.
- `STOP_ALL_AND_REFORM` is the only action that clears `BLOCK(WIDTH)` and `BLOCK(DEPTH)`: cancel all roots, wait for loaded nodes to clear owned frames/mailboxes, then create a new epoch from the current valid structure.
- `RETRY_UNCERTAIN` is accepted only for a selected `DispatchRecord` in `UNCERTAIN`; it is explicit because it may duplicate an already emitted package.
- `ACK_TERMINAL` removes the compact completed-root notification after the client has displayed it. It never acknowledges an active root.
- Panel close drops partial client snapshots. Server cleanup continues using the plan-4 bounded runtime rules; no menu-close save is added.

`FactoryPanelResultPacket` contains only `containerId`, `FactoryPanelResultCode`, optional `jobId`, and optional exact output stack/count. It never contains an exception message or an unbounded log string.

- [ ] **Step 5: Implement binding edits with administration permissions**

Binding packets call the resolver with `administration=true`. The resolver also requires `ClusterBindingService.bindingAccess(server, panel) == READY`; a panel whose known authority is unloaded or whose revision conflicts cannot mutate bindings or serve an order. `RENAME` strips/truncates alias to 32 characters. `REMOVE` cannot leave an in-use selected logistics ID on an active root without confirmation: reject it while any current root references that ID. `MOVE_UP/DOWN` swaps within the ordered immutable list. Every mutation calls the public authoritative `ClusterBindingService.replaceBindings(player, panel, normalizedBindings)`; it never invokes a panel-local commit method. Only a successful cluster-wide transaction returns a fresh stock generation. A 33rd binding maps to the translated `TOO_MANY_BINDINGS` result.

- [ ] **Step 6: Run focused tests and commit**

Run: `./gradlew.bat test --tests "*FactoryPanelRequestRulesTest" --offline`

Expected: `BUILD SUCCESSFUL`; multi-network task keys differ, warning submission remains enabled, and fatal validation paths stay rejected.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelOrderPacket.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelControlPacket.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelBindingPacket.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelResultPacket.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/ComputerNodeRuntime.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelRequestRulesTest.java
git commit -m "feat: control factory orders from panels"
```

### Task 5: Build the Create-Native Warehouse and Order Page

**Files:**
- Create: `FactoryPanelScreen.java`

**Interfaces:**
- Consumes: Tasks 1–4 packets/snapshots and Create GUI assets/widgets.
- Produces: `FactoryPanelScreen`, `receiveStock`, `receiveCluster`, `receiveResult`, two-tab navigation.

- [ ] **Step 1: Build a Create-native screen shell without new GUI textures**

`FactoryPanelScreen extends AbstractSimiContainerScreen<FactoryPanelMenu>`. Use width `226`. Calculate height exactly like `StockKeeperRequestScreen`: clamp to available GUI height, then round the body section to `AllGuiTextures.STOCK_KEEPER_REQUEST_BODY.getHeight()` rows. Render only these Create resources:

```java
AllGuiTextures.STOCK_KEEPER_REQUEST_HEADER;
AllGuiTextures.STOCK_KEEPER_REQUEST_BODY;
AllGuiTextures.STOCK_KEEPER_REQUEST_FOOTER;
AllGuiTextures.STOCK_KEEPER_REQUEST_SEARCH;
AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT;
AllGuiTextures.STOCK_KEEPER_REQUEST_SCROLL_TOP;
AllGuiTextures.STOCK_KEEPER_REQUEST_SCROLL_PAD;
AllGuiTextures.STOCK_KEEPER_REQUEST_SCROLL_MID;
AllGuiTextures.STOCK_KEEPER_REQUEST_SCROLL_BOT;
AllGuiTextures.STOCK_KEEPER_REQUEST_BANNER_L;
AllGuiTextures.STOCK_KEEPER_REQUEST_BANNER_M;
AllGuiTextures.STOCK_KEEPER_REQUEST_BANNER_R;
```

Use `NoShadowFontWrapper`, `LerpedFloat`, `IconButton`, `SelectionScrollInput`, `ScrollInput`, and `AddressEditBox`. Do not subclass `StockKeeperRequestScreen`; its single-ticker/categories/crafting-list internal model is incompatible with multiple logistics IDs and the live cluster page.

Two top `IconButton`s select `WAREHOUSE` and `CLUSTER`; use `AllIcons.I_3x3` and `AllIcons.I_VIEW_SCHEDULE`. Keep one screen instance and rebuild page widgets in `setTab` so search text, address, selected network, amount, mode, and complete snapshots survive tab switches.

- [ ] **Step 2: Implement generation-based polling**

At `init`, send generation `1` for the current tab. While open, request the visible tab every 20 client ticks only if the previous generation has completed or timed out after 100 ticks. Increment generation monotonically. A page switch immediately requests that page. `removed()` clears the two snapshot accumulators, pending result banners, hovered stack, and search cache; it sends no cleanup/save packet.

- [ ] **Step 3: Implement ordered multi-network selection**

The selector options are:

1. translated `All Networks` sentinel mapped to `null`;
2. panel bindings in stored order, showing alias then the first eight UUID characters.

`ALL_NETWORKS` aggregates exact-component display rows but disables the amount, order mode, address, and submit widgets. Selecting one network filters each row to its `byNetwork` count and enables ordering. A network is never inferred from an item row because one exact stack may occur in several networks.

Administrative users get rename, remove, move-up, and move-down buttons using `AllIcons.I_PLACEMENT_SETTINGS`, `I_TRASH`, `I_MTD_LEFT`, and `I_MTD_RIGHT`; non-admin users see the same ordered names without mutation controls.

- [ ] **Step 4: Render searchable exact stock and proxy availability**

Use a 9-column, 20-pixel grid and the stock-keeper slot/scroll textures. Search case-folded hover names and registry IDs, cap search input at 50 characters, and preserve item components for rendering/tooltips. Each cell renders:

- exact item stack;
- physical count for the selected network, or aggregate total for all networks;
- a small `F` marker when a loaded Factory Gauge can produce it;
- a small `P` marker when the loaded Pattern Storage can describe it;
- both markers when both proxies exist.

Markers indicate available processing descriptions, not inventory. Items with zero physical count appear only when at least one proxy can produce them and display count `0`.

In `ALL_NETWORKS`, hovering the count appends one line per bound network in binding order as `alias: count`; this is the required per-network breakdown behind the aggregate cell. Networks with zero for that exact component variant remain omitted.

- [ ] **Step 5: Implement direct streaming order controls**

Clicking a row selects its exact one-count `ItemStack`. `ScrollInput` edits amount `1..BigItemStack.INF`; a two-option `SelectionScrollInput` edits `FINITE/CONTINUOUS`; `AddressEditBox` is always required and max length 25. The submit `IconButton` uses `AllIcons.I_CONFIRM` and is active only when one concrete bound network, non-empty output, positive amount, and non-blank address are selected.

Do not add a planning preview modal. Informational warning banners are drawn with stock-keeper banner textures and never disable submit. Fatal server results keep the editor values and display a translated result. On accepted submission, keep address/network as convenience defaults, clear only selected item/amount, switch to `CLUSTER`, and highlight the returned job UUID.

- [ ] **Step 6: Compile and commit the warehouse page**

Run: `./gradlew.bat compileJava --offline`

Expected: `BUILD SUCCESSFUL`; the screen uses only imports present in Create 6.0.10-281 and Minecraft 1.21.1.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelScreen.java
git commit -m "feat: add factory warehouse order screen"
```

### Task 6: Render Live Tasks and Cluster Hardware on One Page

**Files:**
- Modify: `FactoryPanelScreen.java`

**Interfaces:**
- Consumes: `FactoryPanelClusterSnapshot`, control packets, compact runtime state/reason enums.
- Produces: combined live task+cluster page without historical events.

- [ ] **Step 1: Divide one Create body into task and hardware panes**

The `CLUSTER` page keeps the same header/body/footer textures. The upper 60% is the current task tree; the lower 40% is the cluster summary. One vertical `LerpedFloat` scrolls frames, and a second scrolls nodes only when more than eight rows exist. Never create a third page or a separate log panel.

- [ ] **Step 2: Render current frame hierarchy compactly**

Render each current root and loaded descendant as one row:

```text
[item]  x<count>  RUN|WAIT|SLP|BLOCK|HALT  (reason)  node-short-id
```

Indent direct children by `min(depth, 6) * 8` pixels; depths beyond six show `+N`. Color by state: RUN green, WAIT amber, SLP blue, BLOCK red, HALT gray. Show `WIDTH`, `DEPTH`, `LOOP`, `OFFLINE`, `STOCK`, and other reasons as translated tooltips, not long status strings. A selected row shows job/frame UUID, exact network alias/short UUID, finite/continuous mode, completed/requested count, and final delivery address.

No row records when a prior state occurred, which node previously owned it, or a chronological list of dispatches.

- [ ] **Step 3: Add current controls and explicit dangerous action confirmation**

- `AllIcons.I_STOP` on an active root sends `CANCEL_JOB` after a one-click confirmation banner.
- A cluster-wide `STOP_ALL_AND_REFORM` button requires holding Shift while clicking; it warns that all tasks stop and frozen width/depth faults clear.
- `RETRY_UNCERTAIN` appears only for a selected uncertain dispatch and requires a confirmation banner that duplicate packages are possible.
- `HALT` terminal rows display until one render tick has completed, then the client sends `ACK_TERMINAL` once; the client keeps a local success/failure toast for 100 ticks after the server removes the root.

- [ ] **Step 4: Render nodes and the pattern library in the same page**

Node rows show short UUID, coordinator marker, resident kind, `IDLE/RUN/SLP`, used/total slots, current/max depth, and online/offline. Sort by the frozen epoch's stable node order, not transient load order. Show conflict/frozen-epoch banners above the nodes.

The library row shows present/online, pages/capacity (`<=600`), indexed patterns, queued pages, search budget, queue count, range, and scanning/idle. Expanding the row shows at most 128 invalid-page locations as `shelf short address / slot / page / reason`; hovering reveals the bounded parser detail. Missing or unloaded library is a current availability state, not an erased binding and not a reason to load its chunk.

- [ ] **Step 5: Compile and commit the cluster page**

Run: `./gradlew.bat compileJava --offline`

Expected: `BUILD SUCCESSFUL`; task and hardware views coexist under `FactoryPanelTab.CLUSTER` and no log collection exists.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/FactoryPanelScreen.java
git commit -m "feat: show live factory task clusters"
```

### Task 7: Register Final Packets, Client Handlers, Screen, and Translations

**Files:**
- Modify: `CBPackets.java`
- Modify: `CBClientPacketHandlers.java`
- Modify: `CreateBiotechClient.java`
- Modify: `en_us.json`
- Modify: `zh_cn.json`

**Interfaces:**
- Consumes: all Task 3–4 packet classes and `FactoryPanelScreen.receive*` methods.
- Produces: final protocol `16`, registered menu screen, complete translated UI.

- [ ] **Step 1: Append packet registrations without renumbering existing entries**

Append serverbound registrations after `AllayPortConfigurationPacket` in this exact order:

```java
registerServer(FactoryPanelRefreshPacket.class, FactoryPanelRefreshPacket::new,
	FactoryPanelRefreshPacket::write, FactoryPanelRefreshPacket::handle);
registerServer(FactoryPanelOrderPacket.class, FactoryPanelOrderPacket::new,
	FactoryPanelOrderPacket::write, FactoryPanelOrderPacket::handle);
registerServer(FactoryPanelControlPacket.class, FactoryPanelControlPacket::new,
	FactoryPanelControlPacket::write, FactoryPanelControlPacket::handle);
registerServer(FactoryPanelBindingPacket.class, FactoryPanelBindingPacket::new,
	FactoryPanelBindingPacket::write, FactoryPanelBindingPacket::handle);
```

Append clientbound registrations after the existing final `ShulkerPackagerPlacementPacket.ClientBoundResult` in this exact order:

```java
registerClient(FactoryPanelStockPacket.class, FactoryPanelStockPacket::new,
	FactoryPanelStockPacket::write);
registerClient(FactoryPanelClusterPacket.class, FactoryPanelClusterPacket::new,
	FactoryPanelClusterPacket::write);
registerClient(FactoryPanelResultPacket.class, FactoryPanelResultPacket::new,
	FactoryPanelResultPacket::write);
```

Only after every shape is final, change:

```java
private static final String NETWORK_VERSION = "16";
```

- [ ] **Step 2: Route client packets only to the matching open menu**

Append branches in `CBClientPacketHandlers.handle`. Each packet's client `handle` checks:

```java
if (player.containerMenu instanceof FactoryPanelMenu menu
	&& menu.containerId == packet.containerId()
	&& Minecraft.getInstance().screen instanceof FactoryPanelScreen screen)
	screen.receive(packet);
```

Otherwise discard silently. Do not cache packets globally or on the panel BE.

- [ ] **Step 3: Register the screen with the existing generic-cast convention**

Append `FACTORY_PANEL` registration in `CreateBiotechClient.registerMenuScreens`. Use a small private generic bridge like `registerWirelessStockKeeperScreen` only if NeoForge generic inference requires it; do not introduce a standalone accessor/wrapper class.

- [ ] **Step 4: Add complete English and Chinese keys**

Add keys for:

- both tabs, all-networks sentinel, aliases, selected network, search, amount, finite/continuous, final address, order;
- physical/pattern markers and their tooltips;
- all five frame states, every `FrameReason`, all three computer states, all resident kinds;
- node slots/depth/coordinator/offline, library pages/capacity/patterns/range/scanning;
- cancel, stop/reform, retry-uncertain, and confirmation text;
- every `FactoryPanelResultCode`.

Chinese terminology is fixed: `工厂面板`, `仓储与下单`, `实时任务与集群`, `全部网络（只读）`, `最终投递地址`, `停止全部并重组`, `重试不确定派发`. English uses `Factory Panel`, `Warehouse & Orders`, `Live Tasks & Cluster`, `All Networks (Read-only)`, and `Final Delivery Address`.

- [ ] **Step 5: Compile, inspect packet order, and commit**

```powershell
./gradlew.bat compileJava --offline
git diff -- src/main/java/com/nobodiiiii/createbiotech/network/CBPackets.java
git grep -n 'NETWORK_VERSION = "16"' -- src/main/java/com/nobodiiiii/createbiotech/network/CBPackets.java
```

Expected: compile succeeds; existing registrations remain byte-for-byte ordered; four serverbound and three clientbound packets appear only at list tails; exactly one version match exists.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/network/CBPackets.java src/main/java/com/nobodiiiii/createbiotech/network/CBClientPacketHandlers.java src/main/java/com/nobodiiiii/createbiotech/client/CreateBiotechClient.java src/main/resources/assets/create_biotech/lang/en_us.json src/main/resources/assets/create_biotech/lang/zh_cn.json
git commit -m "feat: register factory panel interface"
```

### Task 8: Integration and Security Regression Gate

**Files:**
- Create: `FactoryPanelGameTests.java`
- Modify only when a failing test proves a defect: files from Tasks 1–7.

**Interfaces:**
- Consumes: complete plans 1–5.
- Produces: dedicated-server, optional-Sable, multi-network, cleanup, and packet-security evidence.

- [ ] **Step 1: Add exact GameTest scenarios**

Use empty templates and deterministic helpers for:

```java
static void panelDeduplicatesAndPreservesBindingOrder(GameTestHelper helper);
static void allNetworksReadsButCannotOrder(GameTestHelper helper);
static void sameAddressOnTwoNetworksRoutesBySelectedUuid(GameTestHelper helper);
static void orderRequiresExplicitFinalAddress(GameTestHelper helper);
static void stockExcludesPromisesAndInTransitPackages(GameTestHelper helper);
static void wrongContainerIdIsRejected(GameTestHelper helper);
static void wrongPanelPositionIsRejected(GameTestHelper helper);
static void wrongSublevelUuidIsRejected(GameTestHelper helper);
static void unboundLogisticsUuidIsRejected(GameTestHelper helper);
static void missingCreatePermissionIsRejected(GameTestHelper helper);
static void finiteOrderDispatchesFinalResultOnce(GameTestHelper helper);
static void continuousOrderStartsFreshIterations(GameTestHelper helper);
static void completedRootIsRemovedAfterUiAck(GameTestHelper helper);
static void closeMenuLeavesNoClientOrServerSnapshotDirectory(GameTestHelper helper);
static void stopAndReformClearsWidthDepthFreeze(GameTestHelper helper);
static void clusterSnapshotNeverLoadsOfflineMemberChunk(GameTestHelper helper);
```

- [ ] **Step 2: Run unit and GameTest suites**

```powershell
./gradlew.bat test --offline
./gradlew.bat runGameTestServer --offline
```

Expected: both commands exit `0`; all factory-cluster tests pass on the dedicated game-test server.

- [ ] **Step 3: Run client UI smoke checks**

Run: `./gradlew.bat runClient --offline`

Check at GUI scales `2`, `3`, and `4`:

1. both tabs fit and preserve inputs when switched;
2. all-networks aggregates stock and disables ordering;
3. two logistics UUIDs sharing one address remain independently selectable;
4. exact component variants render as separate rows;
5. accepted order switches to the highlighted live root;
6. tasks and hardware appear on the same cluster page;
7. no historical log appears after state transitions;
8. terminal result persists as a short client toast after ACK cleanup;
9. Sable world↔sublevel and sublevel↔sublevel panels reject wrong-space packets and display particles at local endpoints;
10. closing the panel clears partial snapshots and reopening starts a new generation.

Expected: no clipped controls, duplicate widgets, stale-generation flashes, packet disconnects, or forced chunk loads.

- [ ] **Step 4: Build and audit forbidden behavior**

```powershell
./gradlew.bat build --offline
git diff --check
git grep -n -E "saveAllChunks|waitUntilIOWorkerComplete|DimensionDataStorage\.save|getChunk\(" -- src/main/java/com/nobodiiiii/createbiotech/content/factorycluster
git grep -n -E "ae2|appeng|LDLib|ldlib" -- src/main/java/com/nobodiiiii/createbiotech/content/factorycluster src/main/resources/assets/create_biotech
git grep -n -E "history|eventLog|scheduleLog|timestamp" -- src/main/java/com/nobodiiiii/createbiotech/content/factorycluster
```

Expected: build succeeds; no whitespace errors; all three searches return no prohibited implementation match. Translation text mentioning the design comparison is not added.

- [ ] **Step 5: Commit the integration gate**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/gametest/FactoryPanelGameTests.java src/main src/test
git commit -m "test: verify factory panel integration"
```

Expected: commit succeeds, `.superpowers/` remains untracked, and `git status --short` shows no production/test changes.
