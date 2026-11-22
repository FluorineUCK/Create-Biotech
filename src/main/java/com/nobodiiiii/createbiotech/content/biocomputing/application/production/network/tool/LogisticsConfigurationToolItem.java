package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import java.util.List;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortMenu;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.Map;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.BoundLogisticsTooltip;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.BoundLogisticsTooltipData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.ClientBoundLogisticsTooltip;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Carries a native Create network selection. Only the production port menu can grant access. */
public final class LogisticsConfigurationToolItem extends LogisticallyLinkedBlockItem {
	public LogisticsConfigurationToolItem(Properties properties) {
		// Create's linked blocks explicitly yield only to LogisticallyLinkedBlockItem.
		// This is a non-placeable tool, so AIR is never registered into Block.BY_ITEM.
		super(Blocks.AIR, properties);
	}

	@Override public void registerBlocks(Map<Block, net.minecraft.world.item.Item> blockToItemMap,
		net.minecraft.world.item.Item item) {}

	@Override public String getDescriptionId() {
		return Util.makeDescriptionId("item", BuiltInRegistries.ITEM.getKey(this));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        var target = level.getBlockEntity(context.getClickedPos());
        if (target instanceof SmartBlockEntity host && host.getBehaviour(ProductionPortBehaviour.TYPE) != null) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            return context.getPlayer() instanceof ServerPlayer player && ProductionPortMenu.open(player,host,"ok")
                ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        var linked = BlockEntityBehaviour.get(level, context.getClickedPos(), LogisticallyLinkedBehaviour.TYPE);
        if (linked == null) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player) || !linked.mayInteractMessage(player))
            return InteractionResult.FAIL;
        LogisticallyLinkedBlockItem.assignFrequency(context.getItemInHand(),player,linked.freqId);
        return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
		InteractionHand usedHand) {
		ItemStack stack = player.getItemInHand(usedHand);
		boolean tuned = LogisticallyLinkedBlockItem.isTuned(stack);
		InteractionResultHolder<ItemStack> result = super.use(level, player, usedHand);
		if (tuned && !level.isClientSide) LogisticsToolData.clear(stack);
		return result;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context,
		List<net.minecraft.network.chat.Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltip, flag);
		tooltip.addAll(BoundLogisticsTooltip.fromStack(stack).build(LogisticsToolData.read(stack)
			.networkId().stream().toList(), shiftDown(), false));
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
		if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel server
			&& Math.floorMod(level.getGameTime(), 20) == 0)
			BoundLogisticsTooltipData.refreshServer(stack, LogisticsToolData.read(stack)
				.networkId().stream().toList(), server);
	}

	private static boolean shiftDown() {
		return net.neoforged.fml.loading.FMLEnvironment.dist != null
			&& net.neoforged.fml.loading.FMLEnvironment.dist.isClient()
			&& ClientBoundLogisticsTooltip.shiftDown();
	}
	@Override
	public boolean isFoil(ItemStack stack) {
		return hasCapturedNetwork(stack);
	}

	static boolean hasCapturedNetwork(ItemStack stack) {
		return LogisticsToolData.read(stack).networkId().isPresent();
	}

}
