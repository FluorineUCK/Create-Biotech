package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Registered Computer: physical resident, its own program/ports, and opaque V5 save compatibility. */
public class ComputerBlockEntity extends SmartBlockEntity
        implements ComputerPhysicalHost, ComputerResidentInstallation.Target, ComputerResidentRenderHost, IHaveGoggleInformation {
    private final ComputerCaseArchive archive = new ComputerCaseArchive();
    private final ComputerResident resident = new ComputerResident();
    private final ComputerResidentDisplay residentDisplay = new ComputerResidentDisplay(Set.of(), Set.of());

    public ComputerBlockEntity(BlockPos pos, BlockState state) { this(CBBlockEntityTypes.COMPUTER.get(), pos, state); }
    ComputerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    public Optional<UUID> computerId() { return archive.identity(); }
    @Override public SmartBlockEntity residentOwner() { return this; }
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
    @Override public @Nullable Tag rawResidentTag() { return resident.raw(); }
    @Override public boolean hasResidentSource() { return resident.hasSource(); }
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


    @Override public void clearResidentSource() {
        var computer = getBehaviour(ComputerBehaviour.TYPE);
        if (computer != null) { computer.clearInstrumentCapture(); computer.removeSequencer(); computer.residentRadioRemoved(); }
        resident.clear();
        archive.occupantChanged();
        setChanged();
        sendData();
    }
    @Override public boolean openComputerApplication(ServerPlayer player) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return false;
        var provider = ComputerProgramMenu.provider(this);
        return player.openMenu(provider, provider::writeOpeningData).isPresent();
    }
    @Override public ComputerInstallResult installationStatus() {
        if (hasResidentSource()) return ComputerInstallResult.OCCUPIED;
        return archive.identity().isPresent() ? ComputerInstallResult.SUCCESS : ComputerInstallResult.INVALID_IDENTITY;
    }

    @Override public ComputerResidentInstallation.Prepared prepareResidentInstallation(ItemStack snapshot,
            ComputerResidentInstallation.Candidate candidate) {
        var previous = resident.snapshot();
        var installed = snapshot.copy();
        return new ComputerResidentInstallation.Prepared() {
            public void commit() { resident.restoreSnapshot(installed); }
            public void rollback() { resident.restoreSnapshot(previous); }
            public void published() {
                archive.occupantChanged();
                var computer = getBehaviour(ComputerBehaviour.TYPE);
                if (computer != null) computer.residentRadioInstalled();
                setChanged(); sendData();
            }
        };
    }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) {
            residentDisplay.writeFresh(tag, resident.snapshot(), registries);
            return;
        }
        super.write(tag, registries, false);
        archive.write(tag, resident, registries);
    }

    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) { residentDisplay.readPacket(tag); return; }
        super.read(tag, registries, false);
        archive.load(tag, resident, registries);
    }

    @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        var projection = residentView().resident();
        if (projection == null) return false;
        tooltip.add(Component.translatable("create_biotech.biocomputing.resident.tooltip",
            projection.entityType().getDescription(), projection.level()));
        return true;
    }
}
