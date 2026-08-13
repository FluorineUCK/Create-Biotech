package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
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

	private UUID panelId = UUID.randomUUID();
	private UUID clusterId = UUID.randomUUID();
	private List<LogisticsBinding> logisticsBindings = List.of();
	@Nullable
	private UUID selectedNetwork;

	public FactoryPanelBlockEntity(BlockPos pos, BlockState state) {
		this(CBBlockEntityTypes.FACTORY_PANEL.get(), pos, state);
	}

	FactoryPanelBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
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
	public UUID clusterId() {
		return clusterId;
	}

	@Override
	public List<LogisticsBinding> logisticsBindings() {
		return logisticsBindings;
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
		return true;
	}

	@Override
	public void applyClusterBinding(UUID clusterId, List<LogisticsBinding> bindings) {
		Objects.requireNonNull(clusterId, "clusterId");
		List<LogisticsBinding> normalizedBindings = LogisticsBinding.normalize(bindings);
		MinecraftServer server = server();
		if (server == null) {
			applyBindingState(clusterId, normalizedBindings);
		} else {
			ClusterMemberIndex.rebind(server, this,
				() -> applyBindingState(clusterId, normalizedBindings));
		}
		setChanged();
		sendData();
	}

	private void applyBindingState(UUID clusterId, List<LogisticsBinding> bindings) {
		this.clusterId = clusterId;
		this.logisticsBindings = bindings;
		if (selectedNetwork != null && bindings.stream()
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
		tag.putUUID(CLUSTER_ID_KEY, clusterId);
		ListTag bindings = new ListTag();
		logisticsBindings.forEach(binding -> bindings.add(binding.save()));
		tag.put(LOGISTICS_BINDINGS_KEY, bindings);
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
		if (tag.hasUUID(CLUSTER_ID_KEY))
			clusterId = tag.getUUID(CLUSTER_ID_KEY);

		List<LogisticsBinding> loadedBindings = new ArrayList<>();
		ListTag bindings = tag.getList(LOGISTICS_BINDINGS_KEY, Tag.TAG_COMPOUND);
		for (int index = 0; index < bindings.size(); index++)
			LogisticsBinding.load(bindings.getCompound(index)).ifPresent(loadedBindings::add);
		logisticsBindings = LogisticsBinding.normalize(loadedBindings);
		selectedNetwork = tag.hasUUID(SELECTED_NETWORK_KEY)
			? tag.getUUID(SELECTED_NETWORK_KEY) : null;
	}

	@Nullable
	private MinecraftServer server() {
		return level == null || level.isClientSide ? null : level.getServer();
	}
}
