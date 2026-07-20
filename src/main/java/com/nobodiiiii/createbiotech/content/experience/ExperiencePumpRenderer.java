package com.nobodiiiii.createbiotech.content.experience;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

public class ExperiencePumpRenderer extends KineticBlockEntityRenderer<ExperiencePumpBlockEntity> {

	public static final ResourceLocation COG_MODEL_LOCATION = CreateBiotech.asResource("block/experience_pump/cog");
	public static final PartialModel COG = PartialModel.of(COG_MODEL_LOCATION);

	public ExperiencePumpRenderer(BlockEntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	protected SuperByteBuffer getRotatedModel(ExperiencePumpBlockEntity be, BlockState state) {
		return CachedBuffers.partialFacing(COG, state);
	}
}
