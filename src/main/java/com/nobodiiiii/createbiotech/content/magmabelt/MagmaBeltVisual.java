package com.nobodiiiii.createbiotech.content.magmabelt;

import com.nobodiiiii.createbiotech.client.render.CBBeltVisual;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;

public final class MagmaBeltVisual extends CBBeltVisual<MagmaBeltBlockEntity> {
	public MagmaBeltVisual(VisualizationContext context, MagmaBeltBlockEntity blockEntity, float partialTick) {
		super(context, blockEntity, partialTick, MagmaBeltRenderer::getSpriteShiftEntry, 0);
	}
}
