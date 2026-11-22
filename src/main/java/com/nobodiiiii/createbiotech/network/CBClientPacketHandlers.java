package com.nobodiiiii.createbiotech.network;

import com.nobodiiiii.createbiotech.content.biopackager.BioPackagerContraptionAnimationPacket;
import com.nobodiiiii.createbiotech.content.giantfrog.GiantFrogEatPacket;
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
		} else if (packet instanceof ShulkerPackagerPlacementPacket.ClientBoundResult shulkerPlacementResult) {
			shulkerPlacementResult.handle(player);
		} else if (packet instanceof AllayPortFlapPacket allayPortFlap) {
			allayPortFlap.handle(player);
		} else if (packet instanceof AllayCourierHudPacket allayCourierHud) {
			allayCourierHud.handle(player);
		} else if (packet instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuResponse stockApplication) {
            stockApplication.handle(player);
        } else if (packet instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockControls state) {
            if (player.containerMenu.containerId == state.container()
                && net.minecraft.client.Minecraft.getInstance().screen instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockControls.Listener listener)
                listener.showStockControls(state);
        } else if (packet instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderResponse nativeOrder) {
            nativeOrder.handle(player);
        } else if (packet instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorSpeechPacket speech) {
            com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorOutputClient.receive(speech, player);
        } else if (packet instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerPlaybackPacket media) {
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerPlaybackClient.receive(media, player);
        } else if (packet instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPage media) {
            if (player.containerMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu computer) computer.accept(media);
        } else if (packet instanceof GiantFrogEatPacket giantFrogEat) {
			giantFrogEat.handle(player);
		} else {
			throw new IllegalArgumentException("Unhandled Create Biotech clientbound packet "
				+ packet.getClass().getName());
		}
	}
}
