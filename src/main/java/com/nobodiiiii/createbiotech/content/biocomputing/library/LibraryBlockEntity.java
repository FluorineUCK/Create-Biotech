package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleInformation;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleMetadata;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleTarget;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserService;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryServiceBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryPlayerAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibraryRouting;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibraryArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageIndex;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageIndex.PageReader;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibrarySummary;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeIndexStorage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibraryClientView;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibraryDisplay;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryResidentRenderHost;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeBookPages;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeShelfSource;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibraryMaintenance;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeMaintenanceArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeBookEditing;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeIndexSnapshot;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryRemoval;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryRemovalHost;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryIdentityLifecycle;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryMovementLeases;


import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageError;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryShelfReader;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryResident;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryResidentCapture;

import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registered physical Library: owns identity/resident lifecycle and composes external
 * book storage with the recipe application. Historical network restrictions affect local edits only. Data stays on
 * the physical books; neither a Computer snapshot nor a production queue owns it.
 */
public class LibraryBlockEntity extends SmartBlockEntity
	implements com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.RecipeBrowserHost, FactoryGoggleInformation,
	LibraryCoreHost, LibraryResidentRenderHost {
	private FactoryGoggleState normalGoggles=FactoryGoggleState.EMPTY;
	private final FactoryGoggleMetadata advancedGoggles=new FactoryGoggleMetadata();
	@Override public FactoryGoggleMetadata advancedGoggleMetadata() { return advancedGoggles; }
	@Override public FactoryGoggleState normalGoggles() { return normalGoggles; }
	private void refreshNormalGoggles() {
		if(level==null || level.isClientSide || level.getGameTime()%FactoryGoggleState.SYNC_INTERVAL_TICKS!=0) return;
		var next=captureNormalGoggles();
		if(!next.equals(normalGoggles)||advancedGoggles.changed(this)) { normalGoggles=next; sendData(); }
	}

	private static final int MAX_STRUCTURE_MEMBERS = RecipeMaintenanceArchive.MAX_MEMBERS;

	private final LibraryIdentityLifecycle identity = new LibraryIdentityLifecycle(this);
    // A rejected cold archive must not turn into a newly authorized empty device on the next save.
    @Nullable private Tag rejectedDiskArchive;
    private boolean restoredArchive;
    private LibraryEditRestriction editRestriction = LibraryEditRestriction.UNBOUND;
    private final LibraryPlayerAccess playerAccess = new LibraryPlayerAccess(this, player ->
        rejectedDiskArchive == null && editRestriction.mayEdit(network -> com.simibubi.create.Create.LOGISTICS.mayAdministrate(network, player)));
	private final LibraryResident resident = new LibraryResident();
	private final RecipeLibraryMaintenance recipes = new RecipeLibraryMaintenance();
	private RecipeIndexStorage indexStorage = RecipeIndexStorage.EMPTY;
	private ProductionLibraryRouting routingConfiguration = new ProductionLibraryRouting(CommunicationFrequency.UNCONFIGURED, 0, 0);
	@Nullable private RecipeShelfSource recipeShelfSource;
	private final RecipeLibraryDisplay display = new RecipeLibraryDisplay(Set.of(FactoryGoggleState.ROOT),
		Set.of(FactoryGoggleTarget.ROOT));

	public LibraryBlockEntity(BlockPos pos, BlockState state) {
	this(CBBlockEntityTypes.PATTERN_STORAGE_CORE.get(), pos, state);
}

	protected LibraryBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
	super(type, pos, state);
}

	@Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		behaviours.add(new LibraryServiceBehaviour(this));
		behaviours.add(new com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour(this));
		behaviours.add(new com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour(this));
		behaviours.add(new com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibrarySerialBehaviour(this));
	}

	@Override
	public void initialize() {
		closeRecipeShelfSource();
		super.initialize();
		MinecraftServer server = server();
		if (server != null) LibraryMovementLeases.get(server);
		if (rejectedDiskArchive == null) identity.initialize(server);
	}

	@Override
	public void invalidate() {
		closeRecipeShelfSource();
		advancedGoggles.reset();
		try {
			identity.invalidate();
		} finally { super.invalidate(); }
	}

	@Override
	public void tick() {
		super.tick();
		refreshNormalGoggles();
		if (level == null || level.isClientSide) return;
		if (recipeShelfSource().needsRefresh(CBConfigs.SERVER.library.libraryMaxMembers.get(),
			CBConfigs.SERVER.library.libraryMaxSpan.get())) refreshStructure();
		if (recipes.topologyRefreshPending() && recipes.state() == LibraryStructureScanner.State.VALID
			&& localIndexIdentityReady()) refreshStructure();
		Predicate<BlockPos> loaded = pos -> CBMultiBlockLifecycle.isLoaded(level, pos);
		RecipeLibraryClientView previous = currentClientState();
		var priorState = recipes.state();
		if (!recipes.ensureLoaded(loaded)) {
			notifyMaintenanceChange(previous, priorState != recipes.state());
			return;
		}
		recipes.updateBudget(librarianLevel(), CBConfigs.SERVER.library.libraryScanBatch.get());
		if (!previous.equals(currentClientState())) sendData();
		if (!indexAccessReady()) return;
		serviceIndexIfMembersLoaded(loaded, this::readPage, new PatternJsonParser(level.registryAccess()), recipes.searchBudget());
	}

	private void refreshStructure() {
		var observed = recipeShelfSource().scan(CBConfigs.SERVER.library.libraryMaxMembers.get(),
			CBConfigs.SERVER.library.libraryMaxSpan.get());
		if (observed.structure().state() != LibraryStructureScanner.State.VALID || observed.structure().snapshot() == null) {
			applyPhysicalScan(observed.structure(), null, 0, localIndexIdentityReady()); return;
		}
		if (!observed.books().complete()) { markShelfInspectionIncomplete(); return; }
		applyPhysicalScan(observed.structure(), observed.books().topologies(), observed.books().efficiencyBonus(),
			localIndexIdentityReady());
	}

	void applyStructureScan(LibraryStructureScanner.ScanResult result) {
		applyStructureScan(result, null, result.state() == LibraryStructureScanner.State.VALID
			? recipes.efficiencyBonus() : 0);
	}

	void applyStructureScan(LibraryStructureScanner.ScanResult result,
		@Nullable Map<BlockPos, Integer> currentTopologies, int currentEfficiencyBonus) {
		applyPhysicalScan(result, currentTopologies, currentEfficiencyBonus, true);
	}

	private void applyPhysicalScan(LibraryStructureScanner.ScanResult result, @Nullable Map<BlockPos, Integer> topologies,
		int bonus, boolean admitted) {
		RecipeLibraryClientView previous = currentClientState();
		boolean changed = recipes.apply(result, topologies, bonus, admitted, pos -> {
			var space = Objects.requireNonNull(currentLibrarySpace(), "Library source space");
			return new RecipeSourceLocation(space.dimension(), space.subLevelId(), pos);
		});
		notifyMaintenanceChange(previous, changed);
	}

	private void notifyMaintenanceChange(RecipeLibraryClientView previous, boolean changed) {
		if (changed) setChanged();
		if (!previous.equals(currentClientState())) sendData();
	}

	boolean serviceIndexIfMembersLoaded(Predicate<BlockPos> loaded, PageReader pages, PatternJsonParser parser, int budget) {
		RecipeLibraryClientView previous = currentClientState();
		var state = recipes.state(); boolean pending = recipes.topologyRefreshPending();
		boolean advanced = recipes.advance(indexAccessReady(), loaded, this::shelfEntityReadable, pages, parser, budget);
		if (advanced) {
			if (recipes.index().lastFingerprintUnits() > 0) setChanged();
		}
		notifyMaintenanceChange(previous, state != recipes.state() || pending != recipes.topologyRefreshPending());
		if (recipes.topologyRefreshPending() && recipeShelfSource != null) recipeShelfSource.invalidate();
		return advanced;
	}

	boolean shelfEntityReadable(BlockPos shelfPos) {
		return level != null && SubLevelCompat.getLoadedBlockEntity(level, shelfPos) instanceof ChiseledBookShelfBlockEntity;
	}

	private void markShelfInspectionIncomplete() {
		RecipeLibraryClientView previous = currentClientState();
		boolean changed = recipes.state() != LibraryStructureScanner.State.PARTIAL || !recipes.topologyRefreshPending();
		recipes.markIncomplete();
		notifyMaintenanceChange(previous, changed);
		if (recipeShelfSource != null) recipeShelfSource.invalidate();
	}

	private RecipeShelfSource recipeShelfSource() {
		if (recipeShelfSource == null) recipeShelfSource = new RecipeShelfSource(Objects.requireNonNull(level), worldPosition);
		return recipeShelfSource;
	}

	private void closeRecipeShelfSource() {
		if (recipeShelfSource != null) recipeShelfSource.close();
		recipeShelfSource = null;
	}

	static List<PatternPageKey> pageKeys(SpaceAddress shelfAddress, List<ItemStack> slots) {
		return RecipeBookPages.pageKeys(new RecipeSourceLocation(shelfAddress.dimension(),
			shelfAddress.subLevelId(), shelfAddress.localPos()), slots);
	}

	static int pageTopology(List<ItemStack> slots) { return RecipeShelfSource.pageTopology(slots); }

	static boolean synchronizePageOrder(RecipePageIndex index,
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
		return RecipeShelfSource.efficiencyBonus(slots, efficiency);
	}

	/** World lookup stays behind this host's existing loaded-source and edit-authorization checks. */
	private static SpaceAddress sourceWorldAddress(RecipeSourceLocation source) {
		return new SpaceAddress(source.dimension(), source.subLevelId(), source.localPos());
	}

	private String readPage(PatternPageKey key) {
		if (!sourceWorldAddress(key.shelf()).matches(level, key.shelf().localPos())
			|| !(SubLevelCompat.getLoadedBlockEntity(level, key.shelf().localPos())
				instanceof ChiseledBookShelfBlockEntity shelf))
			return "";
		return readPage(shelfItems(shelf), key);
	}

	static String readPage(List<ItemStack> slots, PatternPageKey key) {
		return RecipeBookPages.readPage(slots, key);
	}

	private static List<ItemStack> shelfItems(ChiseledBookShelfBlockEntity shelf) {
		return LibraryShelfReader.copyContents(shelf);
	}

	static int baseBudget(int level) { return RecipeLibraryMaintenance.baseBudget(level); }

	int librarianLevel() { return librarianProjection().level(); }

	private LibraryResidentCapture.Projection librarianProjection() {
        return resident.projection(level == null ? RegistryAccess.EMPTY : level.registryAccess());
    }

	public LibraryStructureScanner.State structureState() {
		return level != null && level.isClientSide ? display.view().structureState() : recipes.state();
	}
	/** Retained immutable geometry is diagnostic history, not proof of current access. */
	@Nullable public LibraryStructureScanner.Snapshot structureSnapshot() {
		var layout = recipes.layout();
		return layout == null ? null : layout.snapshot();
	}
	public boolean diagnosticTopologyRefreshPending() { return recipes.topologyRefreshPending(); }
	public long diagnosticGeneration() { return recipes.index().generation(); }
 public java.util.List<String> diagnosticIndexRows() {
  LibraryGoggleReadout.requireServerThread(this);
  return java.util.List.of("indexGeneration="+recipes.index().generation()+" fingerprintCursor="+recipes.index().fingerprintCursor(),
   "cachedPages="+recipes.index().cachedPageCount()+" queuedPages="+summary().queuedPages());
 }
 public int searchBudget() { return level != null && level.isClientSide ? display.view().searchBudget() : recipes.searchBudget(); }
	public int queueCount() { return level != null && level.isClientSide ? display.view().queueCount() : recipes.queueCount(); }
	public List<PatternPageError> pageErrors() { return recipes.index().pageErrors(); }
	public RecipeLibrarySummary summary() {
		int capacity = structureSnapshot() == null ? 0
			: Math.multiplyExact(structureSnapshot().mediaShelves().size(), 600);
		return RecipeLibrarySummary.capture(recipes.index(), structureState(), capacity);
	}

	/** Local book/catalog maintenance does not grant production access or advance production queries. */
	boolean indexAccessReady() {
		return structureState() == LibraryStructureScanner.State.VALID
			&& !recipes.topologyRefreshPending() && localIndexIdentityReady();
	}

	private boolean localIndexIdentityReady() {
		MinecraftServer server = server();
		return identity.isServing(server);
	}

    public UUID memberId() { return identity.id(); }
    @Override public UUID libraryDeviceId() { return identity.id(); }
    public SpaceAddress memberAddress() { return SpaceAddress.capture(Objects.requireNonNull(level), worldPosition); }

	public void installLibrarianSnapshot(ItemStack snapshot) { resident.restoreSnapshot(snapshot); setChanged(); }
    ItemStack snapshot() { return resident.snapshot(); }
    boolean hasSnapshot() { return resident.hasSnapshot(); }
    void clearSnapshot() { resident.clear(); setChanged(); }
    @Nullable CompoundTag rawLibrarianSnapshot() { return resident.rawSnapshot(); }
    void installRawLibrarianSnapshot(CompoundTag rawSnapshot) { resident.preserveRawSnapshot(rawSnapshot); setChanged(); }
    boolean pendingSafeRelease() { return resident.pendingSafeRelease(); }
    void markPendingSafeRelease() { resident.markPendingSafeRelease(); setChanged(); }
    /** Narrow physical-page reader for the local mechanical drive; never exposes a query/index capability. */
    public com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedReadSource.RecipeReader recipePageReader(
        com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveLimits limits) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageRomAdapter(recipes.index(), pos -> {
            var address = SpaceAddress.capture(Objects.requireNonNull(level), pos);
            return new RecipeSourceLocation(address.dimension(), address.subLevelId(), address.localPos());
        }, Objects.requireNonNull(level).registryAccess(), limits);
    }
	RecipePageIndex libraryIndex() { return recipes.index(); }

	public ProductionLibraryRouting routingConfiguration() { return routingConfiguration; }

	/** Placement-only transfer; after the first successful claim all routing is immutable. */
	@Override
	public boolean applyPlacementData(LibraryCoreItemData itemData) {
		MinecraftServer exactServer = server();
		if (exactServer != null && level != null && !level.isClientSide)
			LibraryMovementLeases.get(exactServer);
		return exactServer != null && claimItemConfiguration(exactServer, itemData);
	}

	@Override
	public void rollbackLibraryPlacement() {
		if (level == null || level.isClientSide || level.getBlockEntity(worldPosition) != this
			|| !identity.canAcceptPlacement())
			throw new IllegalStateException("Only an unclaimed placement may be rolled back");
		LibraryRemoval.withRemovalGuard(level, worldPosition, () ->
			level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(),
				Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS));
	}

	/**
	 * Claims and applies a validated held-stack value without consuming the stack.
	 * The placement transaction owns consumption in the placement lifecycle.
	 */
	boolean claimItemConfiguration(MinecraftServer server, LibraryCoreItemData itemData) {
		Objects.requireNonNull(server, "server");
		itemData = Objects.requireNonNull(itemData, "itemData");
		UUID previousId = identity.id();
		if (rejectedDiskArchive != null || !identity.claimPlacement(server, itemData.preservedLibraryId()))
			return false;
		ProductionLibraryRouting nextRouting = new ProductionLibraryRouting(itemData.frequency(),
			itemData.libraryPriority(), itemData.revision());
		boolean changed = !previousId.equals(identity.id()) || !routingConfiguration.equals(nextRouting);
		routingConfiguration = nextRouting;
		if (changed) setChanged();
		return true;
	}

	/** Produces one claiming Core item and never exposes an unowned copied identity. */
	public ItemStack asConfiguredStack() {
		return asConfiguredStack(new ItemStack(CBItems.PATTERN_STORAGE_CORE.get()));
	}

	ItemStack asConfiguredStack(ItemStack baseStack) {
		Objects.requireNonNull(baseStack, "baseStack");
		LibraryCoreItemData itemData = claimedItemData().orElse(null);
		if (itemData == null)
			return ItemStack.EMPTY;
		ItemStack configured = baseStack.copyWithCount(1);
		LibraryCoreItemData.write(configured, itemData);
		LibraryServiceBehaviour service = getBehaviour(LibraryServiceBehaviour.TYPE);
		var carried = service == null ? Optional.<LibraryServiceItemData>empty() : service.captureCarriedService(itemData.preservedLibraryId().orElseThrow());
		var control = getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour.TYPE);
		var controlData = control == null ? Optional.<net.minecraft.nbt.Tag>empty() : control.archive();
		var drive = getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour.TYPE);
		var driveData = drive == null ? Optional.<net.minecraft.nbt.Tag>empty() : drive.archive();
		var serial = getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibrarySerialBehaviour.TYPE);
		var serialData = serial == null ? Optional.<net.minecraft.nbt.Tag>empty() : serial.archive();
		if (controlData.isPresent() || driveData.isPresent() || serialData.isPresent()) carried = Optional.of(new LibraryServiceItemData(itemData.preservedLibraryId().orElseThrow(),
			carried.map(LibraryServiceItemData::archive).orElse(null), controlData, driveData, serialData));
		carried.ifPresent(data -> configured.set(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get(), data.encode()));
		return configured;
	}

	public LibraryResident.InstallResult installLibrarian(Player player,
        net.minecraft.world.InteractionHand hand) {
        Objects.requireNonNull(player, "player");
        return installLibrarian(player.getItemInHand(hand),
            new LibraryResidentCapture(Objects.requireNonNull(level, "Core has no level")), () -> {
                if (!level.isClientSide) sendData();
            });
    }

    LibraryResident.InstallResult installLibrarian(ItemStack held, LibraryResident.CaptureAccess access,
        Runnable synchronize) {
        RecipeLibraryClientView before = display.view();
        LibraryResident.InstallResult result = resident.install(held, access, () -> {
            display.replace(currentClientState());
            setChanged();
            synchronize.run();
        });
        if (result == LibraryResident.InstallResult.COMMIT_FAILED) {
            display.replace(before);
            setChanged();
        }
        return result;
    }

	/** Returns an item value only for the Core that currently owns the server claim. */
	Optional<LibraryCoreItemData> claimedItemData() {
		if (!identity.ownsCurrentIdentity())
			return Optional.empty();
        return Optional.of(recoveredItemData());
    }

    private LibraryCoreItemData recoveredItemData() {
        return routingConfiguration.frequency().configured()
            ? LibraryCoreItemData.configured(routingConfiguration.frequency(), routingConfiguration.priority(), identity.id(), routingConfiguration.revision())
            : LibraryCoreItemData.identityOnly(identity.id());
    }

	/** Keeps the exact claim token-gated while Create's contraption owns the serialized block. */
	boolean retainLibraryIdentityForMovement(Object movementOwner) {
		return identity.retainForMovement(movementOwner);
	}

	UUID movementClaimToken() { return identity.movementToken(); }

	/** Take over Create's exact lease before normal first-tick production registration. */
	boolean acceptMovementLeaseBeforeFirstTick(MinecraftServer server, Object movementOwner) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(movementOwner, "movementOwner");
		return rejectedDiskArchive == null && identity.acceptMovementLease(server, movementOwner);
	}


	@Override
	public boolean libraryIdentityReady() { return identity.isServing(server()); }

	void releaseLibraryIdentityClaim() { identity.release(); }

	/** Player dispatch only; generic block geometry has no production dependency. */
	@Override
	public net.minecraft.world.InteractionResult useLibrary(Player player) {
		if (player.getMainHandItem().getItem() instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsConfigurationToolItem
			|| player.getOffhandItem().getItem() instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsConfigurationToolItem)
			return net.minecraft.world.InteractionResult.PASS;
		if (level == null) return net.minecraft.world.InteractionResult.FAIL;
		if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
		if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer))
			return net.minecraft.world.InteractionResult.PASS;
		if (!canPlayerUse(player)) return net.minecraft.world.InteractionResult.FAIL;
		return openPatternBrowser(serverPlayer)
			? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.PASS;
	}

	@Override
	public boolean installLibraryResident(Player player, net.minecraft.world.InteractionHand hand) {
		return installLibrarian(player, hand) == LibraryResident.InstallResult.SUCCESS;
	}

	@Override
	public LibraryRemovalHost.State captureLibraryRemoval() {
		return new LibraryRemovalHost.State(resident.snapshot(), resident.rawSnapshot() != null,
			asConfiguredStack());
	}

	@Override
	public void completeLibraryResidentRelease() { clearSnapshot(); }

	@Override
	public void deferLibraryResidentRelease() { markPendingSafeRelease(); }

	@Override
	public boolean reclaimLibraryAfterRemoval() {
		return applyPlacementData(recoveredItemData());
	}

	public boolean canPlayerUse(Player player) {
		return playerAccess.canUse(player);
	}

	/** Local read-only access is independent of production Cluster admission. */
	public boolean openPatternBrowser(net.minecraft.server.level.ServerPlayer player) {
		return openPatternBrowser(player, java.util.Optional.empty());
	}

	/** Generic data is owned by book components, never the recipe index or this BE's save. */
	public boolean openBookLibrary(net.minecraft.server.level.ServerPlayer player) {
		return com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookManagement.open(
			playerAccess, player);
	}

	public boolean openPatternBrowser(net.minecraft.server.level.ServerPlayer player, java.util.Optional<UUID> editorPattern) {
        return com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.RecipeBrowserMenus.open(this, player, editorPattern);
    }
    @Override public UUID libraryId() { return identity.id(); }
    @Override public RecipeSourceLocation libraryLocation() {
        var address = memberAddress();
        return new RecipeSourceLocation(address.dimension(), address.subLevelId(), address.localPos());
    }

	public RecipeIndexSnapshot recipeSnapshot() {
		return recipes.index().snapshot();
	}

	/** Server-authoritative edit gate. The request carries no shelf, slot, page, or expected text. */
	public boolean canPlayerEdit(net.minecraft.server.level.ServerPlayer player) {
		return playerAccess.canEdit(player);
	}

	public String editPattern(net.minecraft.server.level.ServerPlayer player,
		BrowserFence fence,
		UUID patternId, long expectedGeneration, String editedJson) {
		var access = recipeEditingAccess(fence);
		if (!access.mayEdit(player)) return "denied";
		if (fence.generation() != expectedGeneration) return "stale_generation";
		return RecipeBookEditing.replace(this, player, access, patternId, expectedGeneration, editedJson);
	}

	/** Server-authoritative creation gate. No source coordinate is accepted from the client. */
	public String createPattern(net.minecraft.server.level.ServerPlayer player,
		BrowserFence fence,
		String json) {
		return RecipeBookEditing.create(this, player, recipeEditingAccess(fence), fence.generation(), json);
	}

	/** Temporary session/index adapter only; the recipe application owns all physical page mutation. */
	private RecipeBookEditing.Access recipeEditingAccess(
		BrowserFence fence) {
		return new RecipeBookEditing.Access() {
			@Override public boolean mayEdit(net.minecraft.server.level.ServerPlayer player) {
				return canPlayerEdit(player)
					&& player.containerMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu menu
					&& menu.ownsServerCore(LibraryBlockEntity.this) && fence.sameSession(menu.openingFence());
			}
			@Override public RecipeIndexSnapshot snapshot() { return recipeSnapshot(); }
			@Override public List<PatternPageKey> pageOrder() { return recipes.index().pageOrder(); }
			@Override public boolean structureKnown() { return structureSnapshot() != null; }
			@Override public void pagesChanged(List<PatternPageKey> pages) {
				recipes.index().rebuildPageOrder(pages);
				setChanged();
			}
		};
	}

	public com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserDiagnostics browserDiagnostics() {
		
		var state = currentClientState();
        return new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserDiagnostics(
            state.structureState(), state.ordinaryBookshelves(),
            state.chiseledBookshelves(), state.searchBudget(), state.queueCount(), 0, 0);
	}

	public PatternBrowserService browserService(
		BrowserFence fence) {
        return new PatternBrowserService(fence, this::recipeSnapshot, routingConfiguration.priority());
    }

	public RecipeLibraryClientView clientState() { return display.view(); }
	@Override public LibraryResidentCapture.Projection libraryResidentView() { return display.view().residentView(); }

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (clientPacket) {
			display.writeFresh(tag, identity.id(), currentClientState());
			normalGoggles=captureNormalGoggles(); tag.put(FactoryGoggleState.ROOT,normalGoggles.save()); advancedGoggles.write(this,tag);
			return;
		}
		super.write(tag, registries, false);
		if (rejectedDiskArchive != null) {
			tag.put(RecipeLibraryArchive.ROOT, rejectedDiskArchive.copy());
			return;
		}
		var archive = RecipeLibraryArchive.save(identity.id(), identity.movementToken(), editRestriction,
			resident, recipes, indexStorage.save(recipes.index(), registries), routingConfiguration, registries);
		tag.put(RecipeLibraryArchive.ROOT, archive.getCompound(RecipeLibraryArchive.ROOT));
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (clientPacket) { readClient(tag); return; }
		advancedGoggles.reset();
		closeRecipeShelfSource();
		super.read(tag, registries, false);
        var candidate = RecipeLibraryArchive.prepare(tag, identity.movementToken(), resident, registries,
            configuredMemberLimit(), recipeSpace(currentLibrarySpace()), RecipeIndexStorage.CODEC);
        if (candidate.isEmpty()) {
            Tag raw = tag.get(RecipeLibraryArchive.ROOT);
            if (!restoredArchive && identity.canAcceptPlacement() && raw != null)
                rejectedDiskArchive = raw.copy();
            return;
        }
        restoredArchive = true;
        rejectedDiskArchive = null;
        var saved = candidate.orElseThrow();
        identity.restore(saved.libraryId(), saved.movementToken());
        editRestriction = saved.restriction();
        resident.commitLoad(saved.resident());
        saved.recipe().commitTo(recipes);
        indexStorage = saved.indexState();
        routingConfiguration = saved.routing();
        if (saved.needsSave()) setChanged();
    }

    // Temporary historical test seams; all validation is owned by the new archive.
    static Optional<Map<BlockPos, Integer>> loadPageTopologies(ListTag saved, int memberLimit) {
        return RecipeMaintenanceArchive.loadTopologies(saved, memberLimit);
    }

    @Nullable private static RecipeSourceLocation recipeSpace(@Nullable SpaceAddress space) {
        return space == null ? null : new RecipeSourceLocation(space.dimension(), space.subLevelId(), space.localPos());
    }

	@Nullable
	SpaceAddress currentLibrarySpace() {
		return level == null ? null : SpaceAddress.capture(level, worldPosition);
	}

	private static int effectiveMemberLimit(int configured) {
		return RecipeMaintenanceArchive.effectiveMemberLimit(configured);
	}

	private static int configuredMemberLimit() {
		try {
			return effectiveMemberLimit(CBConfigs.SERVER.library.libraryMaxMembers.get());
		} catch (IllegalStateException configNotLoaded) {
			return MAX_STRUCTURE_MEMBERS;
		}
	}


	private void readClient(CompoundTag tag) {
		var acceptedId = display.readPacket(tag);
		if (acceptedId.isEmpty()) return;
		identity.restoreClientId(acceptedId.orElseThrow());
		advancedGoggles.read(tag);
		normalGoggles=FactoryGoggleState.load(tag.getCompound(FactoryGoggleState.ROOT)).orElse(FactoryGoggleState.EMPTY);
		// Client projections never mutate authoritative maintenance state.
	}

	private RecipeLibraryClientView currentClientState() {
		if (level != null && level.isClientSide) return display.view();
		var layout = recipes.layout();
		return RecipeLibraryClientView.capture(librarianProjection(), recipes.state(), resident.pendingSafeRelease(),
			layout == null ? null : layout.snapshot(), recipes.searchBudget(), recipes.queueCount());
	}

	@Nullable MinecraftServer server() { return level == null || level.isClientSide ? null : level.getServer(); }

	static void writeAuthoritativeProjection(CompoundTag tag, boolean clientPacket, Consumer<CompoundTag> writer) { if (!clientPacket) writer.accept(tag); }
	static void readAuthoritativeProjection(CompoundTag tag, boolean clientPacket, Consumer<CompoundTag> reader) { if (!clientPacket) reader.accept(tag); }



	private FactoryGoggleState captureNormalGoggles() {
		LibraryGoggleReadout.requireServerThread(this);
		return LibraryGoggleReadout.capture(structureState(), routingConfiguration.frequency().value(),
			routingConfiguration.priority(), summary());
	}
}
