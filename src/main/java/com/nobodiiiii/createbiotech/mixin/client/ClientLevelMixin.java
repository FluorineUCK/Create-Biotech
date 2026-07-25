package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.nobodiiiii.createbiotech.content.shulkerpackager.ShulkerPackagerConnectionHandler;
import com.nobodiiiii.createbiotech.content.shulkerpackager.ShulkerPackagerPlacementCapture;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin implements ShulkerPackagerPlacementCapture {

	@Shadow
	@Final
	private BlockStatePredictionHandler blockStatePredictionHandler;

	@Override
	public void createBiotech$capturePlacementSelection(InteractionHand hand, ItemStack placedStack) {
		ShulkerPackagerConnectionHandler.capturePlacementSelection((ClientLevel) (Object) this, hand, placedStack,
			blockStatePredictionHandler.currentSequence());
	}
}
