package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@net.neoforged.fml.common.EventBusSubscriber(modid = "create_biotech", value = Dist.CLIENT)
public final class ClientBoundLogisticsTooltip {
	private ClientBoundLogisticsTooltip() {}
	/** Display-only adapter for saved network aliases. No authority or runtime callbacks into the port. */
	@net.neoforged.bus.api.SubscribeEvent
	public static void portTooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) {
		var stack = event.getItemStack();
		if (!(stack.getItem() instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.MaterialRequestPortItem)) return;
		event.getToolTip().addAll(BoundLogisticsTooltip.forNativePort(stack, shiftDown()));
	}

	public static boolean shiftDown() { return Screen.hasShiftDown(); }
}
