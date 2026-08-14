package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Runtime coordinator for the incremental, chiseled-bookshelf pattern index. */
public class PatternStorageCoreBlockEntity extends SmartBlockEntity implements ClusterMember {
	private static final int MAX_STRUCTURE_MEMBERS = 1024;
	private static final String LIBRARY_ID = "LibraryId";
	private static final String PATTERN_LIBRARY = "PatternLibrary";
	private static final String BINDING_STATE = "BindingState";
	private static final String BINDING_STATE_INVALID = "BindingStateInvalid";
	private static final String SERVER_STATE = "ServerState";
	private static final String CLIENT_STATE = "ClientState";
	private static final String LIBRARIAN_SNAPSHOT = "LibrarianSnapshotBox";
	private static final String PENDING_SAFE_RELEASE = "PendingSafeRelease";
	private static final String STRUCTURE_SNAPSHOT = "StructureSnapshot";
	private static final String STRUCTURE_STATE = "StructureState";
	private static final String LIBRARY_INDEX = "PatternIndex";
	private static final String PAGE_TOPOLOGIES = "PageTopologies";

	private UUID libraryId = UUID.randomUUID();
	@Nullable private ClusterBinding bindingState;
	private boolean bindingStateValid = true;
	private ItemStack librarianSnapshotBox = ItemStack.EMPTY;
	@Nullable private CompoundTag rawLibrarianSnapshotBox;
	private boolean pendingSafeRelease;
	@Nullable private PatternStructureSnapshot structureSnapshot;
	private PatternLibraryScanner.StructureState structureState = PatternLibraryScanner.StructureState.UNFORMED;
	private PatternLibraryIndex libraryIndex = new PatternLibraryIndex();
	private int searchBudget;
	private int queueCount = 1;
	private int efficiencyBonus;
	private Map<BlockPos, Integer> pageTopologies = Map.of();
	private boolean topologyRefreshPending;
	private long lastStructureCheck = Long.MIN_VALUE;
	private final ReplyDispatchAllowance replyDispatchAllowance = new ReplyDispatchAllowance();
	private PatternCoreClientState clientState = defaultClientState();

	public PatternStorageCoreBlockEntity(BlockPos pos, BlockState state) {
	this(CBBlockEntityTypes.PATTERN_STORAGE_CORE.get(), pos, state);
}

PatternStorageCoreBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
	super(type, pos, state);
}

	@Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

	@Override
	public void initialize() {
		super.initialize();
		MinecraftServer server = server();
		if (server != null)
			ClusterMemberIndex.register(server, this);
	}

	@Override
	public void invalidate() {
		MinecraftServer server = server();
		if (server != null)
			ClusterMemberIndex.unregister(server, this);
		super.invalidate();
	}

	@Override
	public void tick() {
		super.tick();
		if (level == null || level.isClientSide)
			return;
		if (lastStructureCheck == Long.MIN_VALUE || level.getGameTime() - lastStructureCheck >= 20)
			refreshStructure();
		if (topologyRefreshPending
			&& structureState == PatternLibraryScanner.StructureState.VALID
			&& authorityAccessReady())
			refreshStructure();
		if (structureState == PatternLibraryScanner.StructureState.PARTIAL)
			return;
		Predicate<BlockPos> loaded = pos -> CBMultiBlockLifecycle.isLoaded(level, pos);
		if (!allMembersLoaded(structureSnapshot, loaded)) {
			if (structureState != PatternLibraryScanner.StructureState.PARTIAL)
				applyStructureScan(new PatternLibraryScanner.ScanResult(
					PatternLibraryScanner.StructureState.PARTIAL, null));
			return;
		}
		PatternCoreClientState previousClientState = currentClientState();
		searchBudget = structureState == PatternLibraryScanner.StructureState.VALID
			? Math.min(CBConfigs.SERVER.factoryCluster.patternMaxPagesPerTick.get(),
				baseBudget(librarianLevel()) + efficiencyBonus) : 0;
		queueCount = structureSnapshot == null ? 1 : structureSnapshot.queueCount();
		if (!previousClientState.equals(currentClientState()))
			sendData();
		if (!queryAccessReady())
			return;
		serviceIndexIfMembersLoaded(loaded, this::readPage,
			new PatternJsonParser(level.registryAccess()), searchBudget);
	}

	private void refreshStructure() {
		lastStructureCheck = level.getGameTime();
		PatternLibraryScanner.ScanResult result = PatternLibraryScanner.scan(new ScannerView(level), worldPosition,
			CBConfigs.SERVER.factoryCluster.libraryMaxMembers.get(), CBConfigs.SERVER.factoryCluster.libraryMaxSpan.get());
		if (result.state() != PatternLibraryScanner.StructureState.VALID
			|| result.snapshot() == null) {
			applyStructureScan(result);
			return;
		}
		ShelfInspection inspection = inspectShelves(result.snapshot());
		if (!inspection.complete()) {
			topologyRefreshPending = true;
			applyStructureScan(new PatternLibraryScanner.ScanResult(
				PatternLibraryScanner.StructureState.PARTIAL, null));
			lastStructureCheck = Long.MIN_VALUE;
			return;
		}
		applyStructureScan(result, inspection.topologies(), inspection.efficiencyBonus(),
			authorityAccessReady());
	}

	void applyStructureScan(PatternLibraryScanner.ScanResult result) {
		applyStructureScan(result, null,
			result.state() == PatternLibraryScanner.StructureState.VALID ? efficiencyBonus : 0);
	}

	void applyStructureScan(PatternLibraryScanner.ScanResult result,
		@Nullable Map<BlockPos, Integer> currentTopologies, int currentEfficiencyBonus) {
		applyStructureScan(result, currentTopologies, currentEfficiencyBonus, true);
	}

	private void applyStructureScan(PatternLibraryScanner.ScanResult result,
		@Nullable Map<BlockPos, Integer> currentTopologies, int currentEfficiencyBonus,
		boolean permitPageOrderChange) {
		Objects.requireNonNull(result, "result");
		PatternCoreClientState previousClientState = currentClientState();
		PatternLibraryScanner.StructureState previousState = structureState;
		PatternStructureSnapshot previousSnapshot = structureSnapshot;
		int previousEfficiencyBonus = efficiencyBonus;
		int previousQueueCount = queueCount;
		boolean pageOrderChanged = false;
		boolean topologyStateChanged = false;
		structureState = result.state();
		if (result.state() != PatternLibraryScanner.StructureState.PARTIAL) {
			PatternStructureSnapshot next = result.snapshot();
			boolean structureChanged = !Objects.equals(structureSnapshot, next);
			if (structureChanged)
				structureSnapshot = next;
			if (result.state() == PatternLibraryScanner.StructureState.VALID
				&& currentTopologies != null) {
				Map<BlockPos, Integer> immutableTopologies = Map.copyOf(currentTopologies);
				boolean topologyChanged = !pageTopologies.equals(immutableTopologies);
				boolean stalePageOrder = immutableTopologies.isEmpty()
					&& !libraryIndex.pageOrder().isEmpty();
				if (topologyChanged || stalePageOrder || topologyRefreshPending) {
					List<PatternPageKey> currentPageOrder = next == null ? List.of()
						: PatternLibraryIndex.canonicalPageOrder(pageKeys(next, immutableTopologies));
					boolean pageOrderMismatch = !libraryIndex.pageOrder().equals(currentPageOrder);
					if (pageOrderMismatch && !permitPageOrderChange) {
						topologyRefreshPending = true;
					} else if (pageOrderMismatch) {
						libraryIndex.rebuildPageOrder(currentPageOrder);
						pageOrderChanged = true;
					}
					if (!pageOrderMismatch || permitPageOrderChange) {
						pageTopologies = immutableTopologies;
						topologyStateChanged = topologyChanged;
						topologyRefreshPending = false;
					}
				} else {
					topologyRefreshPending = false;
				}
			}
			efficiencyBonus = currentEfficiencyBonus;
			queueCount = next == null ? 1 : next.queueCount();
		}
		boolean changed = previousState != structureState
			|| !Objects.equals(previousSnapshot, structureSnapshot)
			|| previousEfficiencyBonus != efficiencyBonus
			|| previousQueueCount != queueCount || pageOrderChanged || topologyStateChanged;
		if (changed)
			setChanged();
		if (!previousClientState.equals(currentClientState()))
			sendData();
	}

	boolean serviceIndexIfMembersLoaded(Predicate<BlockPos> loaded, PageReader pages,
		PatternJsonParser parser, int budget) {
		Objects.requireNonNull(loaded, "loaded");
		if (!queryAccessReady())
			return false;
		if (!allMembersLoaded(structureSnapshot, loaded)) {
			if (structureState != PatternLibraryScanner.StructureState.PARTIAL)
				applyStructureScan(new PatternLibraryScanner.ScanResult(
					PatternLibraryScanner.StructureState.PARTIAL, null));
			return false;
		}
		if (!allShelfEntitiesReadable(structureSnapshot)) {
			markShelfInspectionIncomplete();
			return false;
		}
		libraryIndex.tick(pages, parser, budget);
		if (libraryIndex.lastTotalUnits() > 0)
			setChanged();
		return true;
	}

	private static boolean allMembersLoaded(@Nullable PatternStructureSnapshot snapshot,
		Predicate<BlockPos> loaded) {
		return snapshot == null || snapshot.members().stream().allMatch(loaded);
	}

	private boolean allShelfEntitiesReadable(@Nullable PatternStructureSnapshot snapshot) {
		return snapshot == null || snapshot.chiseledShelves().stream()
			.allMatch(this::shelfEntityReadable);
	}

	boolean shelfEntityReadable(BlockPos shelfPos) {
		return level != null && SubLevelCompat.getLoadedBlockEntity(level, shelfPos)
			instanceof ChiseledBookShelfBlockEntity;
	}

	private void markShelfInspectionIncomplete() {
		topologyRefreshPending = true;
		if (structureState != PatternLibraryScanner.StructureState.PARTIAL)
			applyStructureScan(new PatternLibraryScanner.ScanResult(
				PatternLibraryScanner.StructureState.PARTIAL, null));
		lastStructureCheck = Long.MIN_VALUE;
	}

	private List<PatternPageKey> pageKeys(PatternStructureSnapshot snapshot,
		Map<BlockPos, Integer> topologies) {
		List<PatternPageKey> keys = new ArrayList<>();
		for (BlockPos shelfPos : snapshot.chiseledShelves()) {
			int topology = topologies.getOrDefault(shelfPos, 0);
			if (topology == 0)
				continue;
			SpaceAddress address = SpaceAddress.capture(level, shelfPos);
			for (int slot = 0; slot < 6; slot++) {
				if ((topology & (1 << slot)) == 0)
					continue;
				for (int page = 0; page < 100; page++)
					keys.add(new PatternPageKey(address, slot, page));
			}
		}
		return List.copyOf(keys);
	}

	private ShelfInspection inspectShelves(PatternStructureSnapshot snapshot) {
		Holder<Enchantment> efficiency = level.registryAccess().holderOrThrow(Enchantments.EFFICIENCY);
		return inspectShelfContents(snapshot, shelfPos -> {
			BlockEntity blockEntity = SubLevelCompat.getLoadedBlockEntity(level, shelfPos);
			return blockEntity instanceof ChiseledBookShelfBlockEntity shelf
				? shelfItems(shelf) : null;
		}, efficiency);
	}

	static ShelfInspection inspectShelfContents(PatternStructureSnapshot snapshot,
		Function<BlockPos, List<ItemStack>> shelfContents,
		Holder<Enchantment> efficiency) {
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(shelfContents, "shelfContents");
		Objects.requireNonNull(efficiency, "efficiency");
		Map<BlockPos, Integer> topologies = new LinkedHashMap<>();
		int bonus = 0;
		for (BlockPos shelfPos : snapshot.chiseledShelves()) {
			List<ItemStack> slots = shelfContents.apply(shelfPos);
			if (slots == null)
				return ShelfInspection.INCOMPLETE;
			topologies.put(shelfPos, pageTopology(slots));
			bonus += efficiencyBonus(slots, efficiency);
		}
		return new ShelfInspection(true, Map.copyOf(topologies), bonus);
	}

	static List<PatternPageKey> pageKeys(SpaceAddress shelfAddress, List<ItemStack> slots) {
		Objects.requireNonNull(shelfAddress, "shelfAddress");
		Objects.requireNonNull(slots, "slots");
		List<PatternPageKey> keys = new ArrayList<>();
		for (int slot = 0; slot < Math.min(6, slots.size()); slot++) {
			ItemStack stack = Objects.requireNonNull(slots.get(slot), "slot stack");
			if (!stack.is(Items.WRITABLE_BOOK))
				continue;
			for (int page = 0; page < 100; page++)
				keys.add(new PatternPageKey(shelfAddress, slot, page));
		}
		return List.copyOf(keys);
	}

	static int pageTopology(List<ItemStack> slots) {
		Objects.requireNonNull(slots, "slots");
		int topology = 0;
		for (int slot = 0; slot < Math.min(6, slots.size()); slot++) {
			ItemStack stack = Objects.requireNonNull(slots.get(slot), "slot stack");
			if (stack.is(Items.WRITABLE_BOOK))
				topology |= 1 << slot;
		}
		return topology;
	}

	static boolean synchronizePageOrder(PatternLibraryIndex index,
		List<PatternPageKey> currentPageKeys) {
		Objects.requireNonNull(index, "index");
		Objects.requireNonNull(currentPageKeys, "currentPageKeys");
		Set<PatternPageKey> current = new HashSet<>(currentPageKeys);
		if (current.size() == currentPageKeys.size()
			&& current.size() == index.pageOrder().size()
			&& current.equals(new HashSet<>(index.pageOrder())))
			return false;
		index.rebuildPageOrder(currentPageKeys);
		return true;
	}

	static int efficiencyBonus(List<ItemStack> slots, Holder<Enchantment> efficiency) {
		Objects.requireNonNull(slots, "slots");
		Objects.requireNonNull(efficiency, "efficiency");
		int result = 0;
		for (int slot = 0; slot < Math.min(6, slots.size()); slot++) {
			ItemStack stack = Objects.requireNonNull(slots.get(slot), "slot stack");
			if (!stack.is(Items.ENCHANTED_BOOK))
				continue;
			int enchantment = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS,
				ItemEnchantments.EMPTY).getLevel(efficiency);
			if (enchantment > 0)
				result += enchantment * enchantment + 1;
		}
		return result;
	}

	private String readPage(PatternPageKey key) {
		if (!key.shelf().matches(level, key.shelf().localPos())
			|| !(SubLevelCompat.getLoadedBlockEntity(level, key.shelf().localPos())
				instanceof ChiseledBookShelfBlockEntity shelf))
			return "";
		return readPage(shelfItems(shelf), key);
	}

	static String readPage(List<ItemStack> slots, PatternPageKey key) {
		Objects.requireNonNull(slots, "slots");
		Objects.requireNonNull(key, "key");
		if (key.slot() < 0 || key.slot() >= Math.min(6, slots.size()))
			return "";
		ItemStack stack = Objects.requireNonNull(slots.get(key.slot()), "slot stack");
		if (!stack.is(Items.WRITABLE_BOOK))
			return "";
		WritableBookContent book = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
		return key.page() < book.pages().size() ? book.pages().get(key.page()).raw() : "";
	}

	private static List<ItemStack> shelfItems(ChiseledBookShelfBlockEntity shelf) {
		List<ItemStack> slots = new ArrayList<>(6);
		for (int slot = 0; slot < 6; slot++)
			slots.add(shelf.getItem(slot));
		return slots;
	}

	static int baseBudget(int level) {
		return switch (Math.max(1, Math.min(5, level))) {
			case 1 -> 8; case 2 -> 16; case 3 -> 32; case 4 -> 64; default -> 128;
		};
	}

	int librarianLevel() { return librarianProjection().level(); }

	private LibrarianProjection librarianProjection() {
		HolderLookup.Provider registries = level == null ? RegistryAccess.EMPTY
			: level.registryAccess();
		return decodeLibrarianProjection(librarianSnapshotBox, registries);
	}

	private static LibrarianProjection decodeLibrarianProjection(ItemStack snapshot,
		HolderLookup.Provider registries) {
		if (snapshot.isEmpty())
			return LibrarianProjection.DEFAULT;
		CompoundTag itemData = CBItemData.get(snapshot);
		if (itemData == null || !itemData.contains("CapturedEntity", Tag.TAG_COMPOUND))
			return LibrarianProjection.DEFAULT;
		CompoundTag entity = itemData.getCompound("CapturedEntity");
		if (!entity.contains("id", Tag.TAG_STRING)
			|| !"minecraft:villager".equals(entity.getString("id"))
			|| !entity.contains("VillagerData", Tag.TAG_COMPOUND))
			return LibrarianProjection.DEFAULT;
		CompoundTag data = entity.getCompound("VillagerData");
		if (!data.contains("type", Tag.TAG_STRING)
			|| !data.contains("profession", Tag.TAG_STRING)
			|| !data.contains("level", Tag.TAG_INT)
			|| !"minecraft:librarian".equals(data.getString("profession")))
			return LibrarianProjection.DEFAULT;
		ResourceLocation type = ResourceLocation.tryParse(data.getString("type"));
		int level = data.getInt("level");
		if (type == null || !isKnownVillagerType(type) || level < 1 || level > 5)
			return LibrarianProjection.DEFAULT;
		String customName = null;
		if (entity.contains("CustomName", Tag.TAG_STRING)) {
			try {
				Component decoded = Component.Serializer.fromJson(entity.getString("CustomName"), registries);
				if (decoded != null)
					customName = boundedPlainText(decoded.getString());
			} catch (RuntimeException ignored) {
				// A malformed optional name does not invalidate otherwise usable librarian metadata.
			}
		}
		return new LibrarianProjection(true, type, level, customName);
	}

	private static boolean isKnownVillagerType(ResourceLocation type) {
		BuiltInRegistries.VILLAGER_TYPE.getKey(VillagerType.PLAINS);
		return BuiltInRegistries.VILLAGER_TYPE.containsKey(type);
	}

	private static String boundedPlainText(String value) {
		int codePoints = value.codePointCount(0, value.length());
		return codePoints <= 64 ? value : value.substring(0, value.offsetByCodePoints(0, 64));
	}

	public PatternLibraryScanner.StructureState structureState() { return structureState; }
	@Nullable public PatternStructureSnapshot structureSnapshot() { return structureSnapshot; }
	public int searchBudget() { return searchBudget; }
	public int queueCount() { return queueCount; }
	public List<PatternPageError> pageErrors() { return libraryIndex.pageErrors(); }
	public PatternLibrarySummary summary() {
		int capacity = structureSnapshot == null ? 0
			: Math.multiplyExact(structureSnapshot.chiseledShelves().size(), 600);
		return libraryIndex.summary(structureState, capacity);
	}

	public boolean enqueueQuery(PatternQuery query) {
		if (!queryAccessReady() || !bindingAllowsQuery(ClusterBindingService.bindingAccess(server(), this), bindingState,
			query.logisticsId())) return false;
		libraryIndex.enqueue(query);
		setChanged();
		return true;
	}

	public List<PatternReply> drainReplies(UUID requesterComputerId, int maxReplies) {
		Objects.requireNonNull(requesterComputerId, "requesterComputerId");
		if (!queryAccessReady() || level == null)
			return List.of();
		int allowed = replyDispatchAllowance.available(level.getGameTime(), queueCount, maxReplies);
		List<PatternReply> replies = libraryIndex.drainReplies(requesterComputerId, allowed);
		if (!replies.isEmpty()) {
			replyDispatchAllowance.consume(replies.size());
			setChanged();
		}
		return replies;
	}

	static boolean bindingAllowsQuery(ClusterBindingService.BindingAccess access,
		@Nullable ClusterBinding state, UUID logisticsId) {
		return access == ClusterBindingService.BindingAccess.READY && state != null
			&& state.logisticsBindings().stream().anyMatch(binding -> binding.logisticsId().equals(logisticsId));
	}

	static boolean queryOperationAllowed(PatternLibraryScanner.StructureState structureState,
		ClusterBindingService.BindingAccess access) {
		return structureState == PatternLibraryScanner.StructureState.VALID
			&& access == ClusterBindingService.BindingAccess.READY;
	}

	boolean queryAccessReady() {
		return structureState == PatternLibraryScanner.StructureState.VALID
			&& !topologyRefreshPending && authorityAccessReady();
	}

	private boolean authorityAccessReady() {
		MinecraftServer server = server();
		return server != null && bindingState != null && bindingStateValid
			&& !ClusterMemberIndex.conflicts(server, bindingState.clusterId()).blocksNewTasks()
			&& ClusterBindingService.bindingAccess(server, this)
				== ClusterBindingService.BindingAccess.READY;
	}

	@Override public UUID memberId() { return libraryId; }
	@Override public @Nullable ClusterBinding bindingState() { return bindingState; }
	@Override public boolean hasValidBindingState() { return bindingStateValid; }
	@Override public ClusterMemberType memberType() { return ClusterMemberType.PATTERN_CORE; }
	@Override public SpaceAddress memberAddress() { return SpaceAddress.capture(Objects.requireNonNull(level), worldPosition); }
	@Override public boolean canRebind() { return bindingStateValid && libraryIndex.activeQueryCount() == 0 && libraryIndex.readyReplyCount() == 0; }

	@Override
	public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed) {
		if (!bindingStateValid || proposed == null || proposed.authority() == null) return ClusterBindingPreparation.IDENTITY;
		if (!canRebind()) return ClusterBindingPreparation.ACTIVE;
		if (proposed.logisticsBindings().size() > ClusterBinding.MAX_BINDINGS) return ClusterBindingPreparation.CAPACITY;
		if (bindingState != null && (proposed.revision() < bindingState.revision()
			|| proposed.revision() == bindingState.revision() && !proposed.equals(bindingState))) return ClusterBindingPreparation.REVISION;
		return ClusterBindingPreparation.READY;
	}

	@Override
	public void commitClusterBinding(ClusterBinding prepared) {
		MinecraftServer server = server();
		if (server == null) applyBindingState(prepared);
		else ClusterMemberIndex.rebind(server, this, () -> applyBindingState(prepared));
		setChanged();
		sendData();
	}

	private void applyBindingState(ClusterBinding prepared) { bindingState = prepared; bindingStateValid = true; }

	public void installLibrarianSnapshot(ItemStack snapshot) { librarianSnapshotBox = snapshot.copy(); rawLibrarianSnapshotBox = null; pendingSafeRelease = false; setChanged(); }
	ItemStack snapshot() { return librarianSnapshotBox.copy(); }
	boolean hasSnapshot() { return !librarianSnapshotBox.isEmpty() && CapturedEntityBoxHelper.hasCapturedEntity(librarianSnapshotBox); }
	void clearSnapshot() { librarianSnapshotBox = ItemStack.EMPTY; rawLibrarianSnapshotBox = null; pendingSafeRelease = false; setChanged(); }
	@Nullable CompoundTag rawLibrarianSnapshot() {
		return rawLibrarianSnapshotBox == null ? null : rawLibrarianSnapshotBox.copy();
	}
	void installRawLibrarianSnapshot(CompoundTag rawSnapshot) {
		librarianSnapshotBox = ItemStack.EMPTY;
		rawLibrarianSnapshotBox = Objects.requireNonNull(rawSnapshot, "rawSnapshot").copy();
		pendingSafeRelease = true;
		setChanged();
	}
	boolean pendingSafeRelease() { return pendingSafeRelease; }
	void markPendingSafeRelease() { pendingSafeRelease = true; setChanged(); }
	PatternLibraryIndex libraryIndex() { return libraryIndex; }

	public PatternCoreClientState clientState() { return clientState; }

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (clientPacket) {
			tag.putUUID(LIBRARY_ID, libraryId);
			tag.put(CLIENT_STATE, currentClientState().save());
			return;
		}
		super.write(tag, registries, false);
		CompoundTag pattern = new CompoundTag();
		pattern.putUUID(LIBRARY_ID, libraryId);
		if (bindingState != null)
			pattern.put(BINDING_STATE, bindingState.save());
		pattern.putBoolean(BINDING_STATE_INVALID, !bindingStateValid);
		CompoundTag server = new CompoundTag();
		if (rawLibrarianSnapshotBox != null)
			server.put(LIBRARIAN_SNAPSHOT, rawLibrarianSnapshotBox.copy());
		else if (!librarianSnapshotBox.isEmpty())
			server.put(LIBRARIAN_SNAPSHOT, librarianSnapshotBox.save(registries));
		server.putBoolean(PENDING_SAFE_RELEASE, pendingSafeRelease);
		server.putString(STRUCTURE_STATE, structureState.name());
		if (structureSnapshot != null) server.put(STRUCTURE_SNAPSHOT, structureSnapshot.save());
		server.put(PAGE_TOPOLOGIES, savePageTopologies(pageTopologies));
		server.put(LIBRARY_INDEX, libraryIndex.save(registries));
		pattern.put(SERVER_STATE, server);
		tag.put(PATTERN_LIBRARY, pattern);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (clientPacket) { readClient(tag); return; }
		super.read(tag, registries, false);
		if (!hasType(tag, PATTERN_LIBRARY, Tag.TAG_COMPOUND))
			return;
		CompoundTag pattern = tag.getCompound(PATTERN_LIBRARY);
		if (!validServerProjection(pattern))
			return;
		CompoundTag server = pattern.getCompound(SERVER_STATE);
		int memberLimit = configuredMemberLimit();
		Optional<ServerStateSchema> schema = serverStateSchema(server, memberLimit);
		if (schema.isEmpty())
			return;

		LoadedBinding nextBinding = loadBinding(pattern);
		boolean corruption = nextBinding.hadCorruption();
		boolean nextPendingSafeRelease = server.getBoolean(PENDING_SAFE_RELEASE);
		ItemStack nextLibrarianSnapshot = ItemStack.EMPTY;
		CompoundTag nextRawLibrarianSnapshot = null;
		if (server.contains(LIBRARIAN_SNAPSHOT, Tag.TAG_COMPOUND)) {
			CompoundTag rawSnapshot = server.getCompound(LIBRARIAN_SNAPSHOT).copy();
			try {
				ItemStack parsed = ItemStack.parseOptional(registries, rawSnapshot);
				if (!parsed.isEmpty() && CapturedEntityBoxHelper.hasCapturedEntity(parsed)
					&& decodeLibrarianProjection(parsed, registries).valid()) {
					nextLibrarianSnapshot = parsed;
				} else {
					nextLibrarianSnapshot = librarianSnapshotBox;
					nextRawLibrarianSnapshot = rawSnapshot;
					nextPendingSafeRelease = true;
					corruption = true;
				}
			} catch (RuntimeException invalidSnapshot) {
				nextLibrarianSnapshot = librarianSnapshotBox;
				nextRawLibrarianSnapshot = rawSnapshot;
				nextPendingSafeRelease = true;
				corruption = true;
			}
		}

		PatternStructureSnapshot nextStructureSnapshot = null;
		boolean replaceStructureSnapshot = true;
		if (server.contains(STRUCTURE_SNAPSHOT, Tag.TAG_COMPOUND)) {
			var decoded = PatternStructureSnapshot.load(server.getCompound(STRUCTURE_SNAPSHOT));
			if (decoded.isPresent())
				nextStructureSnapshot = decoded.orElseThrow();
			else {
				replaceStructureSnapshot = false;
				corruption = true;
			}
		}
		PatternLibraryScanner.StructureState nextStructureState =
			PatternLibraryScanner.StructureState.valueOf(server.getString(STRUCTURE_STATE));
		Optional<Map<BlockPos, Integer>> loadedTopologies =
			schema.orElseThrow() == ServerStateSchema.PRIOR_NESTED
				? Optional.of(Map.of())
				: loadPageTopologies((ListTag) server.get(PAGE_TOPOLOGIES), memberLimit);
		boolean topologyDecoded = loadedTopologies.isPresent();
		Map<BlockPos, Integer> nextTopologies = loadedTopologies.orElse(Map.of());
		corruption |= !topologyDecoded;
		PatternLibraryIndex.LoadResult loadedIndex = PatternLibraryIndex.load(
			server.getCompound(LIBRARY_INDEX), registries);
		corruption |= loadedIndex.hadCorruption();
		boolean nextTopologyRefreshPending = schema.orElseThrow()
			== ServerStateSchema.PRIOR_NESTED || !topologyDecoded
			|| loadedIndex.hadCorruption() || nextStructureSnapshot == null
			|| nextStructureState != PatternLibraryScanner.StructureState.VALID
			|| !persistedTopologyMatches(nextStructureSnapshot, nextTopologies,
				loadedIndex.index().pageOrder(), memberLimit, currentLibrarySpace());

		libraryId = pattern.getUUID(LIBRARY_ID);
		bindingState = nextBinding.binding();
		bindingStateValid = nextBinding.valid();
		librarianSnapshotBox = nextLibrarianSnapshot;
		rawLibrarianSnapshotBox = nextRawLibrarianSnapshot;
		pendingSafeRelease = nextPendingSafeRelease;
		structureState = nextStructureState;
		if (replaceStructureSnapshot)
			structureSnapshot = nextStructureSnapshot;
		pageTopologies = nextTopologies;
		topologyRefreshPending = nextTopologyRefreshPending;
		libraryIndex = loadedIndex.index();
		queueCount = structureSnapshot == null ? 1 : structureSnapshot.queueCount();
		if (corruption || nextTopologyRefreshPending)
			setChanged();
	}

	private static boolean validServerProjection(CompoundTag tag) {
		Set<String> expected = tag.contains(BINDING_STATE)
			? Set.of(LIBRARY_ID, BINDING_STATE, BINDING_STATE_INVALID, SERVER_STATE)
			: Set.of(LIBRARY_ID, BINDING_STATE_INVALID, SERVER_STATE);
		return tag.getAllKeys().equals(expected) && tag.hasUUID(LIBRARY_ID)
			&& hasType(tag, BINDING_STATE_INVALID, Tag.TAG_BYTE)
			&& booleanValue(tag, BINDING_STATE_INVALID)
			&& (!tag.contains(BINDING_STATE) || hasType(tag, BINDING_STATE, Tag.TAG_COMPOUND))
			&& hasType(tag, SERVER_STATE, Tag.TAG_COMPOUND);
	}

	private static Optional<ServerStateSchema> serverStateSchema(CompoundTag server,
		int memberLimit) {
		boolean hasTopologies = server.contains(PAGE_TOPOLOGIES);
		Set<String> expected = new HashSet<>(Set.of(PENDING_SAFE_RELEASE, STRUCTURE_STATE,
			LIBRARY_INDEX));
		if (hasTopologies)
			expected.add(PAGE_TOPOLOGIES);
		if (server.contains(LIBRARIAN_SNAPSHOT))
			expected.add(LIBRARIAN_SNAPSHOT);
		if (server.contains(STRUCTURE_SNAPSHOT))
			expected.add(STRUCTURE_SNAPSHOT);
		if (!server.getAllKeys().equals(expected)
			|| !hasType(server, PENDING_SAFE_RELEASE, Tag.TAG_BYTE)
			|| !booleanValue(server, PENDING_SAFE_RELEASE)
			|| !hasType(server, STRUCTURE_STATE, Tag.TAG_STRING)
			|| !hasType(server, LIBRARY_INDEX, Tag.TAG_COMPOUND)
			|| (server.contains(LIBRARIAN_SNAPSHOT)
				&& !hasType(server, LIBRARIAN_SNAPSHOT, Tag.TAG_COMPOUND))
			|| (server.contains(STRUCTURE_SNAPSHOT)
				&& !hasType(server, STRUCTURE_SNAPSHOT, Tag.TAG_COMPOUND))
			|| (hasTopologies && (!hasType(server, PAGE_TOPOLOGIES, Tag.TAG_LIST)
				|| ((ListTag) server.get(PAGE_TOPOLOGIES)).size() > memberLimit
				|| !isCompoundList((ListTag) server.get(PAGE_TOPOLOGIES)))))
			return Optional.empty();
		try {
			PatternLibraryScanner.StructureState.valueOf(server.getString(STRUCTURE_STATE));
			return Optional.of(hasTopologies ? ServerStateSchema.CURRENT
				: ServerStateSchema.PRIOR_NESTED);
		} catch (IllegalArgumentException invalidState) {
			return Optional.empty();
		}
	}

	private static boolean hasType(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}

	private static ListTag savePageTopologies(Map<BlockPos, Integer> topologies) {
		ListTag saved = new ListTag();
		topologies.entrySet().stream()
			.sorted(java.util.Comparator.comparingLong(entry -> entry.getKey().asLong()))
			.forEach(entry -> {
				CompoundTag child = new CompoundTag();
				child.putLong("Pos", entry.getKey().asLong());
				child.putInt("WritableSlots", entry.getValue());
				saved.add(child);
			});
		return saved;
	}

	static Optional<Map<BlockPos, Integer>> loadPageTopologies(ListTag saved,
		int memberLimit) {
		Objects.requireNonNull(saved, "saved");
		int effectiveLimit = effectiveMemberLimit(memberLimit);
		if (saved.size() > effectiveLimit)
			return Optional.empty();
		Map<BlockPos, Integer> loaded = new LinkedHashMap<>();
		for (Tag value : saved) {
			if (!(value instanceof CompoundTag child)
				|| !child.getAllKeys().equals(Set.of("Pos", "WritableSlots"))
				|| !hasType(child, "Pos", Tag.TAG_LONG)
				|| !hasType(child, "WritableSlots", Tag.TAG_INT))
				return Optional.empty();
			int slots = child.getInt("WritableSlots");
			BlockPos pos = BlockPos.of(child.getLong("Pos")).immutable();
			if (slots < 0 || slots > 0x3f || loaded.put(pos, slots) != null)
				return Optional.empty();
		}
		return Optional.of(Map.copyOf(loaded));
	}

	static boolean persistedTopologyMatches(PatternStructureSnapshot snapshot,
		Map<BlockPos, Integer> topologies, List<PatternPageKey> pageOrder,
		int memberLimit) {
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(topologies, "topologies");
		Objects.requireNonNull(pageOrder, "pageOrder");
		int effectiveLimit = effectiveMemberLimit(memberLimit);
		if (snapshot.state() != PatternLibraryScanner.StructureState.VALID
			|| snapshot.members().size() > effectiveLimit
			|| topologies.size() > effectiveLimit
			|| !new LinkedHashSet<>(snapshot.chiseledShelves()).equals(topologies.keySet())
			|| !PatternLibraryIndex.isCanonicalPageOrder(pageOrder))
			return false;

		long expectedPages = 0;
		for (int mask : topologies.values()) {
			if (mask < 0 || mask > 0x3f)
				return false;
			expectedPages += (long) Integer.bitCount(mask) * 100L;
		}
		if (expectedPages != pageOrder.size())
			return false;

		Map<BlockPos, SpaceAddress> shelfAddresses = new HashMap<>();
		SpaceAddress commonSpace = null;
		for (PatternPageKey key : pageOrder) {
			BlockPos shelfPos = key.shelf().localPos();
			Integer mask = topologies.get(shelfPos);
			if (mask == null || (mask & (1 << key.slot())) == 0)
				return false;
			SpaceAddress previous = shelfAddresses.putIfAbsent(shelfPos, key.shelf());
			if (previous != null && !previous.equals(key.shelf()))
				return false;
			if (commonSpace == null)
				commonSpace = key.shelf();
			else if (!commonSpace.dimension().equals(key.shelf().dimension())
				|| !Objects.equals(commonSpace.subLevelId(), key.shelf().subLevelId()))
				return false;
		}
		return true;
	}

	static boolean persistedTopologyMatches(PatternStructureSnapshot snapshot,
		Map<BlockPos, Integer> topologies, List<PatternPageKey> pageOrder,
		int memberLimit, @Nullable SpaceAddress currentSpace) {
		if (currentSpace == null
			|| !snapshot.members().getFirst().equals(currentSpace.localPos())
			|| !persistedTopologyMatches(snapshot, topologies, pageOrder, memberLimit))
			return false;
		for (PatternPageKey key : pageOrder) {
			if (!currentSpace.dimension().equals(key.shelf().dimension())
				|| !Objects.equals(currentSpace.subLevelId(), key.shelf().subLevelId()))
				return false;
		}
		return true;
	}

	@Nullable
	SpaceAddress currentLibrarySpace() {
		return level == null ? null : SpaceAddress.capture(level, worldPosition);
	}

	private static int effectiveMemberLimit(int configured) {
		return Math.max(1, Math.min(MAX_STRUCTURE_MEMBERS, configured));
	}

	private static int configuredMemberLimit() {
		try {
			return effectiveMemberLimit(CBConfigs.SERVER.factoryCluster.libraryMaxMembers.get());
		} catch (IllegalStateException configNotLoaded) {
			return MAX_STRUCTURE_MEMBERS;
		}
	}

	private static boolean isCompoundList(ListTag list) {
		return list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND;
	}

	private static boolean booleanValue(CompoundTag tag, String key) {
		byte value = tag.getByte(key);
		return value == 0 || value == 1;
	}

	private static LoadedBinding loadBinding(CompoundTag tag) {
		boolean explicitlyInvalid = tag.getBoolean(BINDING_STATE_INVALID);
		if (!tag.contains(BINDING_STATE))
			return new LoadedBinding(null, !explicitlyInvalid, false);
		var loaded = ClusterBinding.tryLoad(tag.getCompound(BINDING_STATE));
		return new LoadedBinding(loaded.orElse(null),
			!explicitlyInvalid && loaded.isPresent(), loaded.isEmpty());
	}

	private void readClient(CompoundTag tag) {
		if (!tag.getAllKeys().equals(java.util.Set.of(LIBRARY_ID, CLIENT_STATE)) || !tag.hasUUID(LIBRARY_ID)
			|| !tag.contains(CLIENT_STATE, Tag.TAG_COMPOUND)) return;
		var decoded = PatternCoreClientState.load(tag.getCompound(CLIENT_STATE));
		if (decoded.isEmpty()) return;
		libraryId = tag.getUUID(LIBRARY_ID);
		clientState = decoded.get();
		structureState = clientState.structureState(); searchBudget = clientState.searchBudget(); queueCount = clientState.queueCount();
	}

	private PatternCoreClientState currentClientState() {
		int members = structureSnapshot == null ? 0 : Math.min(1024, structureSnapshot.members().size());
		int ordinary = structureSnapshot == null ? 0 : Math.min(1024, structureSnapshot.ordinaryShelves().size());
		int chiseled = structureSnapshot == null ? 0 : Math.min(1024, structureSnapshot.chiseledShelves().size());
		LibrarianProjection librarian = librarianProjection();
		return new PatternCoreClientState(librarian.valid(), librarian.type(), librarian.level(), librarian.customName(),
			structureState, pendingSafeRelease, members, ordinary, chiseled, Math.min(10000, searchBudget), Math.min(1024, queueCount));
	}

	private static PatternCoreClientState defaultClientState() { return new PatternCoreClientState(false, ResourceLocation.withDefaultNamespace("plains"), 1, null, PatternLibraryScanner.StructureState.UNFORMED, false, 0, 0, 0, 0, 1); }
	@Nullable MinecraftServer server() { return level == null || level.isClientSide ? null : level.getServer(); }

	static void writeAuthoritativeProjection(CompoundTag tag, boolean clientPacket, Consumer<CompoundTag> writer) { if (!clientPacket) writer.accept(tag); }
	static void readAuthoritativeProjection(CompoundTag tag, boolean clientPacket, Consumer<CompoundTag> reader) { if (!clientPacket) reader.accept(tag); }

	private static final class ScannerView implements PatternLibraryScanner.View {
		private final Level level; private ScannerView(Level level) { this.level = level; }
		@Override public boolean isLoaded(BlockPos pos) { return CBMultiBlockLifecycle.isLoaded(level, pos); }
		@Override public boolean sameSpace(BlockPos first, BlockPos second) { return SubLevelCompat.sameSpace(level, first, second); }
		@Override public PatternLibraryScanner.MemberKind memberAt(BlockPos pos) {
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof PatternStorageCoreBlock) return state.getValue(PatternStorageCoreBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? PatternLibraryScanner.MemberKind.CORE_LOWER : PatternLibraryScanner.MemberKind.CORE_UPPER;
			if (state.is(Blocks.BOOKSHELF)) return PatternLibraryScanner.MemberKind.BOOKSHELF;
			return state.is(Blocks.CHISELED_BOOKSHELF) ? PatternLibraryScanner.MemberKind.CHISELED_BOOKSHELF : PatternLibraryScanner.MemberKind.NONE;
		}
	}

	private record LibrarianProjection(boolean valid, ResourceLocation type, int level,
		@Nullable String customName) {
		private static final LibrarianProjection DEFAULT = new LibrarianProjection(false,
			ResourceLocation.withDefaultNamespace("plains"), 1, null);
	}

	private enum ServerStateSchema { CURRENT, PRIOR_NESTED }

	private record LoadedBinding(@Nullable ClusterBinding binding, boolean valid,
		boolean hadCorruption) {}

	static record ShelfInspection(boolean complete, Map<BlockPos, Integer> topologies,
		int efficiencyBonus) {
		private static final ShelfInspection INCOMPLETE =
			new ShelfInspection(false, Map.of(), 0);
		ShelfInspection {
			topologies = Map.copyOf(topologies);
		}
	}
}
