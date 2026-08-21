package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.processing.basin.BasinEntityProcessing;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

@Mixin(BasinBlock.class)
public abstract class BasinBlockMixin {

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void createBiotech$keepCapturedSlimesOutOfHandOutput(ItemStack stack, BlockState state, Level level,
		BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit,
		CallbackInfoReturnable<ItemInteractionResult> cir) {
		if (!stack.isEmpty())
			return;
		if (!(level.getBlockEntity(pos) instanceof BasinBlockEntity basin))
			return;
		if (!BasinEntityProcessing.hasCapturedSmallSlimes(basin))
			return;

		if (!level.isClientSide) {
			boolean success = moveNonSlimeItemsToPlayer(basin.getInputInventory(), player)
				| moveNonSlimeItemsToPlayer(basin.getOutputInventory(), player);
			if (success)
				level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, .2f,
					1f + level.random.nextFloat());
			basin.onEmptied();
		}

		cir.setReturnValue(ItemInteractionResult.SUCCESS);
	}

	private static boolean moveNonSlimeItemsToPlayer(IItemHandlerModifiable inventory, Player player) {
		boolean success = false;
		for (int slot = 0; slot < inventory.getSlots(); slot++) {
			ItemStack stackInSlot = inventory.getStackInSlot(slot);
			if (stackInSlot.isEmpty() || BasinEntityProcessing.isCapturedSmallSlimeItem(stackInSlot))
				continue;
			player.getInventory()
				.placeItemBackInInventory(stackInSlot);
			inventory.setStackInSlot(slot, ItemStack.EMPTY);
			success = true;
		}
		return success;
	}
}
