package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleInformation;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleMetadata;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleTarget;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;

import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.*;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInstallation;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInspector;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentView;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentDisplay;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentRenderHost;

import com.nobodiiiii.createbiotech.content.factorycluster.diagnostics.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerPhysicalHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResident;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerConfiguration;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerArchive;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Test-only executable reference for the retired V5 codec and its historical topology tests. */
public class LegacyComputerFixture extends HistoricalProductionHost
    implements FactoryGoggleInformation, ComputerPhysicalHost, ComputerResidentInstallation.Target, ComputerResidentRenderHost {
    @Nullable private UUID computerId = UUID.randomUUID();
    private final ComputerResident resident = new ComputerResident();
    private final ProductionComputerArchive<ProductionStructure.Revision, ClusterBinding, ProductionEpoch,
        ExternalListenerConfiguration, Set<EpochFault>> archive = ProductionComputerArchiveCodecs.create();
    private final ProductionComputer production = new ProductionComputer(this, archive, () -> !resident.hadCorruption());
    private FactoryGoggleState normalGoggles = FactoryGoggleState.EMPTY;
    private final FactoryGoggleMetadata advancedGoggles = new FactoryGoggleMetadata();
    private final ComputerResidentDisplay residentDisplay = new ComputerResidentDisplay(
        Set.of(FactoryGoggleState.ROOT), Set.of(FactoryGoggleTarget.ROOT));

    public LegacyComputerFixture(BlockPos pos, BlockState state) { this(CBBlockEntityTypes.COMPUTER.get(), pos, state); }
    LegacyComputerFixture(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    public ProductionComputer production() { return production; }
    public Optional<UUID> computerId() { return Optional.ofNullable(computerId); }
    @Override public SmartBlockEntity residentOwner() { return this; }
    @Override public FactoryGoggleMetadata advancedGoggleMetadata() { return advancedGoggles; }
    @Override public FactoryGoggleState normalGoggles() { return normalGoggles; }
	@Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		// Generic resources are derived only from this physical resident.
		var computer = new ComputerBehaviour(this, () -> resident.snapshot(), () -> resident.hasSnapshot()
			&& ComputerBlock.ownsBlockEntity(getBlockState())
			&& ComputerBlock.isCompletePair(getBlockState(), level.getBlockState(worldPosition.above())));
		behaviours.add(computer);
		behaviours.add(new com.nobodiiiii.createbiotech.content.biocomputing.network.XBusWiringIndicatorBehaviour(this, computer::communicationIdentity, computer::observeExternalWiring));
	}
	public boolean isAuthoritativeLoadedInstance() {
		return !isRemoved() && level != null && !level.isClientSide && level.hasChunkAt(worldPosition)
			&& level.getBlockEntity(worldPosition) == this;
	}
	@Override public ComputerResidentView residentView() { return residentDisplay.view(); }
	@Override public ItemStack residentSnapshot() { return resident.snapshot(); }
	@Override public @Nullable Tag rawResidentTag() {
		Tag raw = resident.raw();
		if (raw == null) raw = archive.recovery().opaqueResident();
		return raw == null ? null : raw.copy();
	}
	public boolean hasResidentSource() {
		return resident.hasSource()
			|| archive.recovery().opaqueResident() != null;
	}
	private boolean installationAuthority(ServerPlayer player, InteractionHand hand, ItemStack held) {
		return level == player.serverLevel() && isAuthoritativeLoadedInstance()
			&& player.getItemInHand(hand) == held
			&& ComputerBlock.ownsBlockEntity(getBlockState())
			&& ComputerBlock.isCompletePair(getBlockState(), level.getBlockState(worldPosition.above()));
	}
	@Override public byte[] serializedResident(HolderLookup.Provider registries) {
		return resident.serialized(registries);
	}
	@Override public ComputerInstallResult installResident(ServerPlayer player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		if (!installationAuthority(player, hand, held))
			return ComputerInstallResult.INVALID_IDENTITY;
		return ComputerResidentInstallation.install(held, this, new ComputerResidentInspector(player.serverLevel()),
			() -> installationAuthority(player, hand, held));
	}

    @Override public void invalidate() {
        advancedGoggles.reset();
        production.invalidate();
        super.invalidate();
    }
    @Override public void clearResidentSource() {
        production.beforeResidentRemoval();
        resident.clear();
        production.residentRemoved();
    }
    @Override public boolean openComputerApplication(ServerPlayer player) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return false;
        var provider = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu.provider(this);
        return player.openMenu(provider, provider::writeOpeningData).isPresent();
    }
    @Override public ComputerInstallResult installationStatus() {
        if (hasResidentSource()) return ComputerInstallResult.OCCUPIED;
        if (computerId == null || archive.recovery().hasOpaque()
            || archive.recovery().contains("Version") || archive.recovery().contains("ComputerId"))
            return ComputerInstallResult.INVALID_IDENTITY;
        return production.permitsResidentInstallation() ? ComputerInstallResult.SUCCESS : ComputerInstallResult.ACTIVE_TOPOLOGY;
    }
    @Override public ComputerResidentInstallation.Prepared prepareResidentInstallation(ItemStack snapshot,
        ComputerResidentInstallation.Candidate candidate) {
        var previous = resident.snapshot();
        var installed = snapshot.copy();
        var application = production.prepareResidentInstallation(candidate);
        return new ComputerResidentInstallation.Prepared() {
            public void commit() { resident.restoreSnapshot(installed); application.commit(); }
            public void rollback() { resident.restoreSnapshot(previous); application.rollback(); }
            public void published() { application.published(); }
        };
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) {
            residentDisplay.writeFresh(tag, resident.snapshot(), level == null ? net.minecraft.core.RegistryAccess.EMPTY : level.registryAccess());
            normalGoggles = production.captureNormalGoggles();
            tag.put(FactoryGoggleState.ROOT, normalGoggles.save());
            advancedGoggles.write(this, tag);
            return;
        }
        super.write(tag, registries, false);
        tag.put(ProductionComputerArchive.ROOT, archive.save(production.archiveValues(), resident,
            production.runtimeArchive(registries), registries));
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) {
            if (!residentDisplay.readPacket(tag)) return;
            advancedGoggles.read(tag);
            normalGoggles = FactoryGoggleState.load(tag.getCompound(FactoryGoggleState.ROOT)).orElse(FactoryGoggleState.EMPTY);
            return;
        }
        advancedGoggles.reset();
        production.beforeServerRead();
        super.read(tag, registries, false);
        computerId = null;
        resident.clear();
        production.resetForServerRead(registries);
        archive.reset();
        var prepared = archive.prepare(tag.get(ProductionComputerArchive.ROOT), resident, registries);
        computerId = prepared.values().computerId();
        resident.commitLoad(prepared.resident());
        archive.commitRecovery(prepared);
        production.commitArchive(prepared, registries);
    }
}
