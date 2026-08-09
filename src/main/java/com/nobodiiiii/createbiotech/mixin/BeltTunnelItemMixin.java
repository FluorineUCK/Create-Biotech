package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.magmabelt.MagmaBeltBlockEntity;
import com.nobodiiiii.createbiotech.content.magmabelt.MagmaBeltHelper;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity.CasingType;
import com.simibubi.create.content.logistics.tunnel.BeltTunnelItem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(BeltTunnelItem.class)
public abstract class BeltTunnelItemMixin {

	@Inject(method = "updateCustomBlockEntityTag", at = @At("RETURN"))
	private void createBiotech$encaseMagmaBelt(BlockPos pos, Level level, Player player, ItemStack stack,
		BlockState tunnelState, CallbackInfoReturnable<Boolean> cir) {
		if (level.isClientSide)
			return;
		MagmaBeltBlockEntity belt = MagmaBeltHelper.getSegmentBE(level, pos.below());
		if (belt == null || belt.casing != CasingType.NONE)
			return;
		belt.setCasingType(AllBlocks.ANDESITE_TUNNEL.has(tunnelState)
			? CasingType.ANDESITE : CasingType.BRASS);
	}
}
