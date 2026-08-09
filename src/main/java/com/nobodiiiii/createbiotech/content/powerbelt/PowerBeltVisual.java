package com.nobodiiiii.createbiotech.content.powerbelt;

import com.nobodiiiii.createbiotech.client.render.CBBeltVisual;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;

public final class PowerBeltVisual extends CBBeltVisual<PowerBeltBlockEntity> {
	public PowerBeltVisual(VisualizationContext context, PowerBeltBlockEntity blockEntity, float partialTick) {
		super(context, blockEntity, partialTick, PowerBeltRenderer::getSpriteShiftEntry, 0);
	}
}
