package com.nobodiiiii.createbiotech.network;

import com.nobodiiiii.createbiotech.content.biopackager.BioPackagerContraptionAnimationPacket;
import com.nobodiiiii.createbiotech.content.powerbelt.PowerBeltEntityAnimationPacket;
import com.nobodiiiii.createbiotech.content.shulkerpackager.ShulkerPackagerPlacementPacket;
import com.yision.allay.block.allayport.AllayPortFlapPacket;
import com.yision.allay.logistics.courier.hud.AllayCourierHudPacket;

import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class CBClientPacketHandlers {

	private CBClientPacketHandlers() {}

	static void handle(Object packet, LocalPlayer player) {
		if (packet instanceof PowerBeltEntityAnimationPacket powerBeltAnimation) {
			powerBeltAnimation.handle(player);
		} else if (packet instanceof BioPackagerContraptionAnimationPacket bioPackagerAnimation) {
			bioPackagerAnimation.handle(player);
		} else if (packet instanceof ShulkerPackagerPlacementPacket.ClientBoundRequest shulkerPlacement) {
			shulkerPlacement.handle(player);
		} else if (packet instanceof AllayPortFlapPacket allayPortFlap) {
			allayPortFlap.handle(player);
		} else if (packet instanceof AllayCourierHudPacket allayCourierHud) {
			allayCourierHud.handle(player);
		} else {
			throw new IllegalArgumentException("Unhandled Create Biotech clientbound packet "
				+ packet.getClass().getName());
		}
	}
}
