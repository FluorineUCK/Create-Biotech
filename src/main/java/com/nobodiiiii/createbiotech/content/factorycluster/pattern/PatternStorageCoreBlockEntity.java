package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
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
	private static final String LIBRARY_ID = "LibraryId";
	private static final String BINDING_STATE = "BindingState";
	private static final String BINDING_STATE_INVALID = "BindingStateInvalid";
	private static final String SERVER_STATE = "ServerState";
	private static final String CLIENT_STATE = "ClientState";
	private static final String LIBRARIAN_SNAPSHOT = "LibrarianSnapshotBox";
	private static final String PENDING_SAFE_RELEASE = "PendingSafeRelease";
	private static final String STRUCTURE_SNAPSHOT = "StructureSnapshot";
	private static final String STRUCTURE_STATE = "StructureState";
	private static final String LIBRARY_INDEX = "PatternIndex";

	private UUID libraryId = UUID.randomUUID();
	@Nullable private ClusterBinding bindingState;
	private boolean bindingStateValid = true;
	private ItemStack librarianSnapshotBox = ItemStack.EMPTY;
	private boolean pendingSafeRelease;
	@Nullable private PatternStructureSnapshot structureSnapshot;
	private PatternLibraryScanner.StructureState structureState = PatternLibraryScanner.StructureState.UNFORMED;
	private PatternLibraryIndex libraryIndex = new PatternLibraryIndex();
	private int searchBudget;
	private int queueCount = 1;
	private int efficiencyBonus;
	private long lastStructureCheck = Long.MIN_VALUE;
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
		if (structureState == PatternLibraryScanner.StructureState.PARTIAL)
			return;
		searchBudget = Math.min(CBConfigs.SERVER.factoryCluster.patternMaxPagesPerTick.get(),
			baseBudget(librarianLevel()) + efficiencyBonus);
		queueCount = structureSnapshot == null ? 1 : structureSnapshot.queueCount();
		if (!queryAccessReady())
			return;
		libraryIndex.tick(this::readPage, new PatternJsonParser(level.registryAccess()), searchBudget);
		libraryIndex.pollReplies(queueCount);
		setChanged();
	}

	private void refreshStructure() {
		lastStructureCheck = level.getGameTime();
		PatternLibraryScanner.ScanResult result = PatternLibraryScanner.scan(new ScannerView(level), worldPosition,
			CBConfigs.SERVER.factoryCluster.libraryMaxMembers.get(), CBConfigs.SERVER.factoryCluster.libraryMaxSpan.get());
		structureState = result.state();
		if (result.state() == PatternLibraryScanner.StructureState.PARTIAL)
			return;
		PatternStructureSnapshot next = result.snapshot();
		if (!Objects.equals(structureSnapshot, next)) {
			structureSnapshot = next;
			libraryIndex.rebuildPageOrder(next == null ? List.of() : pageKeys(next));
		}
		if (next != null)
			efficiencyBonus = efficiencyBonus(next);
		setChanged();
		sendData();
	}

	private List<PatternPageKey> pageKeys(PatternStructureSnapshot snapshot) {
		List<PatternPageKey> keys = new ArrayList<>();
		for (BlockPos shelfPos : snapshot.chiseledShelves()) {
			if (!(SubLevelCompat.getLoadedBlockEntity(level, shelfPos) instanceof ChiseledBookShelfBlockEntity shelf))
				continue;
			for (int slot = 0; slot < 6; slot++) {
				ItemStack stack = shelf.getItem(slot);
				if (stack.is(Items.WRITABLE_BOOK))
					for (int page = 0; page < 100; page++)
						keys.add(new PatternPageKey(SpaceAddress.capture(level, shelfPos), slot, page));
			}
		}
		return keys;
	}

	private int efficiencyBonus(PatternStructureSnapshot snapshot) {
		int result = 0;
		for (BlockPos shelfPos : snapshot.chiseledShelves()) {
			if (!(SubLevelCompat.getLoadedBlockEntity(level, shelfPos) instanceof ChiseledBookShelfBlockEntity shelf))
				continue;
			for (int slot = 0; slot < 6; slot++) {
				ItemStack stack = shelf.getItem(slot);
				if (!stack.is(Items.ENCHANTED_BOOK)) continue;
				int enchantment = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY)
					.getLevel(level.registryAccess().holderOrThrow(Enchantments.EFFICIENCY));
				result += enchantment * enchantment + (enchantment > 0 ? 1 : 0);
			}
		}
		return result;
	}

	private String readPage(PatternPageKey key) {
		if (!(SubLevelCompat.getLoadedBlockEntity(level, key.shelf().localPos()) instanceof ChiseledBookShelfBlockEntity shelf))
			return "";
		ItemStack stack = shelf.getItem(key.slot());
		if (!stack.is(Items.WRITABLE_BOOK)) return "";
		WritableBookContent book = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
		return key.page() < book.pages().size() ? book.pages().get(key.page()).raw() : "";
	}

	private static int baseBudget(int level) {
		return switch (Math.max(1, Math.min(5, level))) {
			case 1 -> 8; case 2 -> 16; case 3 -> 32; case 4 -> 64; default -> 128;
		};
	}

	private int librarianLevel() { return 1; }

	public PatternLibraryScanner.StructureState structureState() { return structureState; }
	@Nullable public PatternStructureSnapshot structureSnapshot() { return structureSnapshot; }
	public int searchBudget() { return searchBudget; }
	public int queueCount() { return queueCount; }
	public List<PatternPageError> pageErrors() { return libraryIndex.pageErrors(); }

	public boolean enqueueQuery(PatternQuery query) {
		if (!queryAccessReady() || !bindingAllowsQuery(ClusterBindingService.bindingAccess(server(), this), bindingState,
			query.logisticsId())) return false;
		libraryIndex.enqueue(query);
		setChanged();
		return true;
	}

	public List<PatternReply> drainReplies(int maxReplies) { return libraryIndex.pollReplies(maxReplies); }

	static boolean bindingAllowsQuery(ClusterBindingService.BindingAccess access,
		@Nullable ClusterBinding state, UUID logisticsId) {
		return access == ClusterBindingService.BindingAccess.READY && state != null
			&& state.logisticsBindings().stream().anyMatch(binding -> binding.logisticsId().equals(logisticsId));
	}

	private boolean queryAccessReady() {
		MinecraftServer server = server();
		return server != null && bindingState != null && bindingStateValid
			&& !ClusterMemberIndex.conflicts(server, bindingState.clusterId()).blocksNewTasks()
			&& ClusterBindingService.bindingAccess(server, this) == ClusterBindingService.BindingAccess.READY;
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

	public void installLibrarianSnapshot(ItemStack snapshot) { librarianSnapshotBox = snapshot.copy(); pendingSafeRelease = false; setChanged(); }
	ItemStack snapshot() { return librarianSnapshotBox.copy(); }
	boolean hasSnapshot() { return !librarianSnapshotBox.isEmpty() && com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper.hasCapturedEntity(librarianSnapshotBox); }
	void clearSnapshot() { librarianSnapshotBox = ItemStack.EMPTY; pendingSafeRelease = false; setChanged(); }
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
		tag.putUUID(LIBRARY_ID, libraryId);
		if (bindingState != null) tag.put(BINDING_STATE, bindingState.save());
		tag.putBoolean(BINDING_STATE_INVALID, !bindingStateValid);
		CompoundTag server = new CompoundTag();
		if (!librarianSnapshotBox.isEmpty()) server.put(LIBRARIAN_SNAPSHOT, librarianSnapshotBox.save(registries));
		server.putBoolean(PENDING_SAFE_RELEASE, pendingSafeRelease);
		server.putString(STRUCTURE_STATE, structureState.name());
		if (structureSnapshot != null) server.put(STRUCTURE_SNAPSHOT, structureSnapshot.save());
		server.put(LIBRARY_INDEX, libraryIndex.save(registries));
		tag.put(SERVER_STATE, server);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (clientPacket) { readClient(tag); return; }
		super.read(tag, registries, false);
		if (tag.hasUUID(LIBRARY_ID)) libraryId = tag.getUUID(LIBRARY_ID);
		readBinding(tag);
		if (!tag.contains(SERVER_STATE, Tag.TAG_COMPOUND)) return;
		CompoundTag server = tag.getCompound(SERVER_STATE);
		if (server.contains(LIBRARIAN_SNAPSHOT, Tag.TAG_COMPOUND)) {
			ItemStack snapshot = ItemStack.parseOptional(registries, server.getCompound(LIBRARIAN_SNAPSHOT));
			if (snapshot.isEmpty()) pendingSafeRelease = true; else librarianSnapshotBox = snapshot;
		}
		pendingSafeRelease |= server.getBoolean(PENDING_SAFE_RELEASE);
		try { structureState = PatternLibraryScanner.StructureState.valueOf(server.getString(STRUCTURE_STATE)); }
		catch (IllegalArgumentException ignored) { structureState = PatternLibraryScanner.StructureState.UNFORMED; }
		structureSnapshot = server.contains(STRUCTURE_SNAPSHOT, Tag.TAG_COMPOUND)
			? PatternStructureSnapshot.load(server.getCompound(STRUCTURE_SNAPSHOT)).orElse(null) : null;
		if (server.contains(LIBRARY_INDEX, Tag.TAG_COMPOUND)) {
			PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(server.getCompound(LIBRARY_INDEX), registries);
			libraryIndex = loaded.index(); if (loaded.hadCorruption()) setChanged();
		}
	}

	private void readBinding(CompoundTag tag) {
		if (tag.contains(BINDING_STATE_INVALID) && (!tag.contains(BINDING_STATE_INVALID, Tag.TAG_BYTE) || tag.getBoolean(BINDING_STATE_INVALID))) bindingStateValid = false;
		else if (tag.contains(BINDING_STATE)) {
			if (!tag.contains(BINDING_STATE, Tag.TAG_COMPOUND)) bindingStateValid = false;
			else {
				var loaded = ClusterBinding.tryLoad(tag.getCompound(BINDING_STATE));
				if (loaded.isPresent()) { bindingState = loaded.get(); bindingStateValid = true; }
				else bindingStateValid = false;
			}
		}
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
		int members = structureSnapshot == null ? 0 : structureSnapshot.members().size();
		int ordinary = structureSnapshot == null ? 0 : structureSnapshot.ordinaryShelves().size();
		int chiseled = structureSnapshot == null ? 0 : structureSnapshot.chiseledShelves().size();
		return new PatternCoreClientState(hasSnapshot(), ResourceLocation.withDefaultNamespace("plains"), librarianLevel(), null,
			structureState, pendingSafeRelease, members, ordinary, chiseled, Math.min(10000, searchBudget), Math.min(1024, queueCount));
	}

	private static PatternCoreClientState defaultClientState() { return new PatternCoreClientState(false, ResourceLocation.withDefaultNamespace("plains"), 1, null, PatternLibraryScanner.StructureState.UNFORMED, false, 0, 0, 0, 0, 1); }
	@Nullable private MinecraftServer server() { return level == null || level.isClientSide ? null : level.getServer(); }

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
}
