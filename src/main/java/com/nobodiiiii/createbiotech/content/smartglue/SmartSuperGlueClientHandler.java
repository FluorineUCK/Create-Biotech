package com.nobodiiiii.createbiotech.content.smartglue;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public class SmartSuperGlueClientHandler {

	private static final SmartSuperGlueSelectionHandler HANDLER = new SmartSuperGlueSelectionHandler();

	private SmartSuperGlueClientHandler() {}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		HANDLER.tick();
	}

	@SubscribeEvent
	public static void onLevelUnload(LevelEvent.Unload event) {
		if (event.getLevel().isClientSide())
			HANDLER.clear();
	}

	@SubscribeEvent
	public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null)
			return;

		KeyMapping keyMapping = event.getKeyMapping();
		if (keyMapping != minecraft.options.keyUse && keyMapping != minecraft.options.keyAttack)
			return;

		boolean attack = keyMapping == minecraft.options.keyAttack;
		if (HANDLER.onMouseInput(attack))
			event.setCanceled(true);
	}
}
