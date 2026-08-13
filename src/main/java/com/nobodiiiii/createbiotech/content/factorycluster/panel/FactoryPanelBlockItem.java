package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import java.util.ArrayList;
import java.util.List;

import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
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
		LogisticallyLinkedBehaviour link = BlockEntityBehaviour.get(context.getLevel(),
			context.getClickedPos(), LogisticallyLinkedBehaviour.TYPE);
		if (link == null)
			return super.useOn(context);

		Player player = context.getPlayer();
		if (player == null)
			return InteractionResult.PASS;
		if (!link.mayInteractMessage(player))
			return InteractionResult.SUCCESS;
		ItemStack stack = context.getItemInHand();
		List<LogisticsBinding> bindings = new ArrayList<>(readBindings(stack));
		bindings.add(new LogisticsBinding(link.freqId, link.freqId.toString().substring(0, 8)));
		writeBindings(stack, LogisticsBinding.normalize(bindings));
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

	static void writeBindings(ItemStack stack, List<LogisticsBinding> bindings) {
		CustomData.update(DataComponents.BLOCK_ENTITY_DATA, stack,
			tag -> writeBindingsToTag(tag, bindings));
		CustomData.update(DataComponents.BLOCK_ENTITY_DATA, stack,
			tag -> BlockEntity.addEntityType(tag, CBBlockEntityTypes.FACTORY_PANEL.get()));
	}

	static List<LogisticsBinding> readBindingsFromTag(CompoundTag tag) {
		List<LogisticsBinding> bindings = new ArrayList<>();
		ListTag encoded = tag.getList(FactoryPanelBlockEntity.LOGISTICS_BINDINGS_KEY,
			Tag.TAG_COMPOUND);
		for (int index = 0; index < encoded.size(); index++)
			LogisticsBinding.load(encoded.getCompound(index)).ifPresent(bindings::add);
		return LogisticsBinding.normalize(bindings);
	}

	static void writeBindingsToTag(CompoundTag tag, List<LogisticsBinding> bindings) {
		ListTag encoded = new ListTag();
		LogisticsBinding.normalize(bindings)
			.forEach(binding -> encoded.add(binding.save()));
		tag.put(FactoryPanelBlockEntity.LOGISTICS_BINDINGS_KEY, encoded);
	}

	static void clearBindingsFromTag(CompoundTag tag) {
		tag.remove(FactoryPanelBlockEntity.LOGISTICS_BINDINGS_KEY);
	}
}
