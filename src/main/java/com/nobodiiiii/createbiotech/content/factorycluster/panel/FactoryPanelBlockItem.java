package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

public class FactoryPanelBlockItem extends BlockItem {
	public FactoryPanelBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player != null && player.isShiftKeyDown())
			return super.useOn(context);
		LogisticallyLinkedBehaviour link = BlockEntityBehaviour.get(context.getLevel(),
			context.getClickedPos(), LogisticallyLinkedBehaviour.TYPE);
		if (link == null)
			return super.useOn(context);

		if (player == null)
			return InteractionResult.PASS;
		if (!link.mayInteractMessage(player))
			return InteractionResult.SUCCESS;
		ItemStack stack = context.getItemInHand();
		if (!canEditBindings(stack)) {
			player.displayClientMessage(Component.translatable(
				"create_biotech.factory_cluster.binding.authority_offline")
				.withStyle(ChatFormatting.RED), true);
			return InteractionResult.FAIL;
		}
		List<LogisticsBinding> bindings = new ArrayList<>(readBindings(stack));
		bindings.add(new LogisticsBinding(link.freqId, link.freqId.toString().substring(0, 8)));
		if (!writeBindings(stack, LogisticsBinding.normalize(bindings))) {
			player.displayClientMessage(Component.translatable(
				"create_biotech.factory_cluster.binding.too_many_bindings")
				.withStyle(ChatFormatting.RED), true);
			return InteractionResult.FAIL;
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		List<LogisticsBinding> bindings = readBindings(stack);
		if (bindings.isEmpty())
			return InteractionResultHolder.pass(stack);
		if (level.isClientSide)
			return InteractionResultHolder.success(stack);
		if (!canEditBindings(stack)) {
			player.displayClientMessage(Component.translatable(
				"create_biotech.factory_cluster.binding.authority_offline")
				.withStyle(ChatFormatting.RED), true);
			return InteractionResultHolder.fail(stack);
		}

		boolean permitted = bindings.stream()
			.allMatch(binding -> Create.LOGISTICS.mayAdministrate(binding.logisticsId(), player));
		if (!permitted) {
			player.displayClientMessage(Component.translatable(
				"create_biotech.factory_cluster.binding.permission")
				.withStyle(ChatFormatting.RED), true);
			return InteractionResultHolder.fail(stack);
		}

		CustomData.update(DataComponents.BLOCK_ENTITY_DATA, stack,
			FactoryPanelBlockItem::clearBindingsFromTag);
		return InteractionResultHolder.success(stack);
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return !readBindings(stack).isEmpty();
	}

	static List<LogisticsBinding> readBindings(ItemStack stack) {
		CustomData blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
		return blockEntityData == null ? List.of()
			: readBindingsFromTag(blockEntityData.copyTag());
	}

	static boolean writeBindings(ItemStack stack, List<LogisticsBinding> bindings) {
		List<LogisticsBinding> normalized = LogisticsBinding.normalize(bindings);
		if (normalized.size() > ClusterBinding.MAX_BINDINGS)
			return false;
		AtomicBoolean applied = new AtomicBoolean();
		CustomData.update(DataComponents.BLOCK_ENTITY_DATA, stack,
			tag -> applied.set(writeBindingsToTag(tag, normalized)));
		if (!applied.get())
			return false;
		CustomData.update(DataComponents.BLOCK_ENTITY_DATA, stack,
			tag -> BlockEntity.addEntityType(tag, CBBlockEntityTypes.FACTORY_PANEL.get()));
		return true;
	}

	static List<LogisticsBinding> readBindingsFromTag(CompoundTag tag) {
		if (tag.getBoolean(FactoryPanelBlockEntity.BINDING_STATE_INVALID_KEY))
			return List.of();
		if (tag.contains(FactoryPanelBlockEntity.BINDING_STATE_KEY)) {
			if (!tag.contains(FactoryPanelBlockEntity.BINDING_STATE_KEY, Tag.TAG_COMPOUND))
				return List.of();
			return ClusterBinding.tryLoad(tag.getCompound(
				FactoryPanelBlockEntity.BINDING_STATE_KEY))
				.map(ClusterBinding::logisticsBindings).orElse(List.of());
		}
		Tag rawBindings = tag.get(FactoryPanelBlockEntity.LOGISTICS_BINDINGS_KEY);
		if (!(rawBindings instanceof ListTag encoded)
			|| encoded.size() > ClusterBinding.MAX_BINDINGS
			|| (!encoded.isEmpty() && encoded.getElementType() != Tag.TAG_COMPOUND))
			return List.of();
		List<LogisticsBinding> bindings = new ArrayList<>();
		for (int index = 0; index < encoded.size(); index++) {
			java.util.Optional<LogisticsBinding> binding =
				LogisticsBinding.load(encoded.getCompound(index));
			if (binding.isEmpty() || bindings.stream().anyMatch(existing ->
				existing.logisticsId().equals(binding.get().logisticsId())))
				return List.of();
			bindings.add(binding.get());
		}
		return List.copyOf(bindings);
	}

	static boolean writeBindingsToTag(CompoundTag tag, List<LogisticsBinding> bindings) {
		if (!canEditBindingsFromTag(tag))
			return false;
		List<LogisticsBinding> normalized = LogisticsBinding.normalize(bindings);
		if (normalized.size() > ClusterBinding.MAX_BINDINGS)
			return false;
		if (tag.contains(FactoryPanelBlockEntity.BINDING_STATE_KEY,
			Tag.TAG_COMPOUND)) {
			java.util.Optional<ClusterBinding> state = ClusterBinding.tryLoad(
				tag.getCompound(FactoryPanelBlockEntity.BINDING_STATE_KEY));
			if (state.isEmpty() || state.get().revision() == Long.MAX_VALUE)
				return false;
			ClusterBinding updated = new ClusterBinding(state.get().clusterId(),
				state.get().revision() + 1, state.get().authority(), normalized);
			tag.put(FactoryPanelBlockEntity.BINDING_STATE_KEY, updated.save());
			return true;
		}
		ListTag encoded = new ListTag();
		normalized
			.forEach(binding -> encoded.add(binding.save()));
		tag.put(FactoryPanelBlockEntity.LOGISTICS_BINDINGS_KEY, encoded);
		return true;
	}

	static void clearBindingsFromTag(CompoundTag tag) {
		if (tag.contains(FactoryPanelBlockEntity.BINDING_STATE_KEY,
			Tag.TAG_COMPOUND)) {
			ClusterBinding.tryLoad(tag.getCompound(FactoryPanelBlockEntity.BINDING_STATE_KEY))
				.filter(state -> state.revision() < Long.MAX_VALUE)
				.ifPresent(state -> tag.put(FactoryPanelBlockEntity.BINDING_STATE_KEY,
					new ClusterBinding(state.clusterId(), state.revision() + 1,
						state.authority(), List.of()).save()));
		}
		tag.remove(FactoryPanelBlockEntity.LOGISTICS_BINDINGS_KEY);
	}

	private static boolean canEditBindings(ItemStack stack) {
		CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
		return data == null || canEditBindingsFromTag(data.copyTag());
	}

	static boolean canEditBindingsFromTag(CompoundTag tag) {
		if (tag.getBoolean(FactoryPanelBlockEntity.BINDING_STATE_INVALID_KEY))
			return false;
		if (!tag.contains(FactoryPanelBlockEntity.BINDING_STATE_KEY))
			return true;
		if (!tag.contains(FactoryPanelBlockEntity.BINDING_STATE_KEY, Tag.TAG_COMPOUND)
			|| !tag.hasUUID(FactoryPanelBlockEntity.PANEL_ID_KEY))
			return false;
		java.util.Optional<ClusterBinding> state = ClusterBinding.tryLoad(
			tag.getCompound(FactoryPanelBlockEntity.BINDING_STATE_KEY));
		return state.isPresent() && state.get().authority() != null
			&& state.get().authority().type() == ClusterMemberType.PANEL
			&& state.get().authority().memberId()
				.equals(tag.getUUID(FactoryPanelBlockEntity.PANEL_ID_KEY));
	}
}
