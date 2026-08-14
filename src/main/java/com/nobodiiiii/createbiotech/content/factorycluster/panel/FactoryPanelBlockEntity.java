package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class FactoryPanelBlockEntity extends SmartBlockEntity implements ClusterMember {
	static final String PANEL_ID_KEY = "PanelId";
	static final String CLUSTER_ID_KEY = "ClusterId";
	static final String LOGISTICS_BINDINGS_KEY = "LogisticsBindings";
	static final String SELECTED_NETWORK_KEY = "SelectedNetwork";
	static final String BINDING_STATE_KEY = "BindingState";
	static final String BINDING_STATE_INVALID_KEY = "BindingStateInvalid";

	private UUID panelId = UUID.randomUUID();
	private ClusterBinding bindingState = new ClusterBinding(UUID.randomUUID(), 0,
		new ClusterAuthority(ClusterMemberType.PANEL, panelId), List.of());
	private boolean bindingStateValid = true;
	@Nullable
	private UUID selectedNetwork;

	public FactoryPanelBlockEntity(BlockPos pos, BlockState state) {
		this(CBBlockEntityTypes.FACTORY_PANEL.get(), pos, state);
	}

	protected FactoryPanelBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

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
	public UUID memberId() {
		return panelId;
	}

	@Override
	public ClusterBinding bindingState() {
		return bindingState;
	}

	@Override
	public boolean hasValidBindingState() {
		return bindingStateValid;
	}

	@Override
	public ClusterMemberType memberType() {
		return ClusterMemberType.PANEL;
	}

	@Override
	public SpaceAddress memberAddress() {
		return SpaceAddress.capture(Objects.requireNonNull(level, "Panel has no level"), worldPosition);
	}

	@Override
	public boolean canRebind() {
		return bindingStateValid;
	}

	@Override
	public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed) {
		if (!bindingStateValid || proposed.authority() == null)
			return ClusterBindingPreparation.IDENTITY;
		if (!canRebind())
			return ClusterBindingPreparation.ACTIVE;
		if (proposed.logisticsBindings().size() > ClusterBinding.MAX_BINDINGS)
			return ClusterBindingPreparation.CAPACITY;
		if (proposed.revision() < bindingState.revision()
			|| (proposed.revision() == bindingState.revision()
				&& !proposed.equals(bindingState)))
			return ClusterBindingPreparation.REVISION;
		return ClusterBindingPreparation.READY;
	}

	@Override
	public void commitClusterBinding(ClusterBinding prepared) {
		MinecraftServer server = server();
		if (server == null) {
			applyBindingState(prepared);
		} else {
			ClusterMemberIndex.rebind(server, this,
				() -> applyBindingState(prepared));
		}
		setChanged();
		sendData();
	}

	private void applyBindingState(ClusterBinding prepared) {
		bindingState = prepared;
		bindingStateValid = true;
		if (selectedNetwork != null && prepared.logisticsBindings().stream()
			.noneMatch(binding -> binding.logisticsId().equals(selectedNetwork)))
			selectedNetwork = null;
	}

	@Nullable
	public UUID selectedNetwork() {
		return selectedNetwork;
	}

	public void setSelectedNetwork(@Nullable UUID selectedNetwork) {
		this.selectedNetwork = selectedNetwork;
		setChanged();
		sendData();
	}

	@Override
	public boolean canPlayerUse(Player player) {
		if (level == null || level.getBlockEntity(worldPosition) != this)
			return false;
		return SubLevelCompat.canEntityInteractWith(level, worldPosition, player)
			&& SubLevelCompat.distanceSquared(level, Vec3.atCenterOf(worldPosition), player.position()) <= 64.0;
	}

	public ItemStack asConfiguredStack(HolderLookup.Provider registries) {
		ItemStack stack = new ItemStack(CBItems.FACTORY_PANEL.get());
		BlockItem.setBlockEntityData(stack, CBBlockEntityTypes.FACTORY_PANEL.get(),
			saveWithoutMetadata(registries));
		return stack;
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.putUUID(PANEL_ID_KEY, panelId);
		tag.put(BINDING_STATE_KEY, bindingState.save());
		tag.putBoolean(BINDING_STATE_INVALID_KEY, !bindingStateValid);
		if (selectedNetwork != null)
			tag.putUUID(SELECTED_NETWORK_KEY, selectedNetwork);
		else
			tag.remove(SELECTED_NETWORK_KEY);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		if (tag.hasUUID(PANEL_ID_KEY))
			panelId = tag.getUUID(PANEL_ID_KEY);

		if (tag.contains(BINDING_STATE_INVALID_KEY)
			&& (!tag.contains(BINDING_STATE_INVALID_KEY, Tag.TAG_BYTE)
				|| tag.getBoolean(BINDING_STATE_INVALID_KEY))) {
			bindingStateValid = false;
		} else if (tag.contains(BINDING_STATE_KEY)) {
			if (!tag.contains(BINDING_STATE_KEY, Tag.TAG_COMPOUND)) {
				bindingStateValid = false;
			} else {
				java.util.Optional<ClusterBinding> decoded = ClusterBinding.tryLoad(
					tag.getCompound(BINDING_STATE_KEY));
				if (decoded.isPresent()) {
					bindingState = decoded.get();
					bindingStateValid = true;
				} else {
					bindingStateValid = false;
				}
			}
		} else if (tag.hasUUID(CLUSTER_ID_KEY) || tag.contains(LOGISTICS_BINDINGS_KEY)) {
			readLegacyBindingState(tag);
		}
		selectedNetwork = tag.hasUUID(SELECTED_NETWORK_KEY)
			? tag.getUUID(SELECTED_NETWORK_KEY) : null;
		if (selectedNetwork != null && logisticsBindings().stream()
			.noneMatch(binding -> binding.logisticsId().equals(selectedNetwork)))
			selectedNetwork = null;
	}

	private void readLegacyBindingState(CompoundTag tag) {
		if (tag.contains(LOGISTICS_BINDINGS_KEY)
			&& !tag.contains(LOGISTICS_BINDINGS_KEY, Tag.TAG_LIST)) {
			bindingStateValid = false;
			return;
		}
		ListTag bindings = tag.getList(LOGISTICS_BINDINGS_KEY, Tag.TAG_COMPOUND);
		if (bindings.size() > ClusterBinding.MAX_BINDINGS
			|| (!bindings.isEmpty() && bindings.getElementType() != Tag.TAG_COMPOUND)) {
			bindingStateValid = false;
			return;
		}
		List<LogisticsBinding> loadedBindings = new ArrayList<>();
		for (int index = 0; index < bindings.size(); index++) {
			java.util.Optional<LogisticsBinding> binding =
				LogisticsBinding.load(bindings.getCompound(index));
			if (binding.isEmpty() || loadedBindings.stream().anyMatch(existing ->
				existing.logisticsId().equals(binding.get().logisticsId()))) {
				bindingStateValid = false;
				return;
			}
			loadedBindings.add(binding.get());
		}
		UUID clusterId = tag.hasUUID(CLUSTER_ID_KEY)
			? tag.getUUID(CLUSTER_ID_KEY) : bindingState.clusterId();
		bindingState = new ClusterBinding(clusterId, 0,
			new ClusterAuthority(ClusterMemberType.PANEL, panelId), loadedBindings);
		bindingStateValid = true;
	}

	@Nullable
	private MinecraftServer server() {
		return level == null || level.isClientSide ? null : level.getServer();
	}
}
