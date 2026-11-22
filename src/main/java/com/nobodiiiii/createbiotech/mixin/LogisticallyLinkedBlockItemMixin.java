package com.nobodiiiii.createbiotech.mixin;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.BoundLogisticsTooltipData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsConfigurationToolItem;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsToolData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.MaterialRequestPortItem;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
@Mixin(value = LogisticallyLinkedBlockItem.class, remap = false)
public abstract class LogisticallyLinkedBlockItemMixin {
	@Inject(method = "assignFrequency(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Ljava/util/UUID;)V",
		at = @At("HEAD"), cancellable = true)
	private static void createBiotech$assignNonPlaceableToolFrequency(ItemStack stack, Player player,
		UUID frequency, CallbackInfo ci) {
		if (stack.getItem() instanceof MaterialRequestPortItem
			&& !MaterialRequestPortItem.prepareNativeSelection(stack, player, frequency)) {
			ci.cancel();
			return;
		}
		if (!(stack.getItem() instanceof LogisticsConfigurationToolItem)) return;
		LogisticsToolData.writeNativeNetwork(stack, frequency);
		player.displayClientMessage(CreateLang.translateDirect("logistically_linked.tuned"), true);
		if (player instanceof ServerPlayer server) BoundLogisticsTooltipData.selected(server, stack, frequency);
		ci.cancel();
	}

	@Inject(method = "assignFrequency(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Ljava/util/UUID;)V", at = @At("RETURN"))
	private static void createBiotech$nameSelectedPort(ItemStack stack, Player player, UUID frequency, CallbackInfo ci) {
		if (player instanceof ServerPlayer server && stack.getItem() instanceof MaterialRequestPortItem
				&& frequency.equals(LogisticallyLinkedBlockItem.networkFromStack(stack)))
			BoundLogisticsTooltipData.selected(server, stack, frequency);
	}
}
