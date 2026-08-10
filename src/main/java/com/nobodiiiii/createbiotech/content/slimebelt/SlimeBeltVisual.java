package com.nobodiiiii.createbiotech.content.slimebelt;

import com.nobodiiiii.createbiotech.client.render.CBBeltVisual;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;

public final class SlimeBeltVisual extends CBBeltVisual<SlimeBeltBlockEntity> {
	public SlimeBeltVisual(VisualizationContext context, SlimeBeltBlockEntity blockEntity, float partialTick) {
		super(context, blockEntity, partialTick, SlimeBeltRenderer::getSpriteShiftEntry,
			(float) -SlimeBeltLoopGeometry.VERTICAL_BELT_DROP);
	}
}
