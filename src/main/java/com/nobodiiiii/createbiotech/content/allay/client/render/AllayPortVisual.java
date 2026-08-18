package com.nobodiiiii.createbiotech.content.allay.client.render;

import java.util.function.Consumer;

import org.joml.Matrix4f;

import com.simibubi.create.content.logistics.FlapStuffs;
import com.nobodiiiii.createbiotech.content.allay.block.allayport.AllayPortBlock;
import com.nobodiiiii.createbiotech.content.allay.block.allayport.AllayPortBlockEntity;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

public class AllayPortVisual extends AbstractBlockEntityVisual<AllayPortBlockEntity>
	implements SimpleDynamicVisual {

	private final Matrix4f commonTransform;
	private final TransformedInstance[] curtains;

	public AllayPortVisual(VisualizationContext context, AllayPortBlockEntity blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);

		Direction facing = blockState.getValue(AllayPortBlock.FACING);
		float horizontalAngle = AngleHelper.horizontalAngle(facing.getOpposite());
		commonTransform = new Matrix4f()
			.translate(getVisualPosition().getX(), getVisualPosition().getY(), getVisualPosition().getZ())
			.translate(0.5f, 0.5f, 0.5f)
			.rotateY(Mth.DEG_TO_RAD * horizontalAngle)
			.translate(-0.5f, -0.5f, -0.5f)
			.translate((float) AllayPortRenderer.CURTAIN_PIVOT.x,
				(float) AllayPortRenderer.CURTAIN_PIVOT.y,
				(float) AllayPortRenderer.CURTAIN_PIVOT.z);

		curtains = new TransformedInstance[AllayPortRenderer.CURTAIN_SEGMENT_COUNT];
		for (int segment = 0; segment < curtains.length; segment++) {
			curtains[segment] = instancerProvider()
				.instancer(InstanceTypes.TRANSFORMED,
					Models.partial(AllayPortRenderer.CURTAIN_SEGMENTS.get(segment)))
				.createInstance();
		}
		updateCurtain(blockEntity.getFlap(partialTick));
	}

	@Override
	public void beginFrame(Context ctx) {
		updateCurtain(blockEntity.getFlap(ctx.partialTick()));
	}

	@Override
	public void updateLight(float partialTick) {
		int light = computePackedLight();
		for (TransformedInstance curtain : curtains) {
			curtain.light(light)
				.setChanged();
		}
	}

	@Override
	protected void _delete() {
		for (TransformedInstance curtain : curtains) {
			curtain.delete();
		}
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		for (TransformedInstance curtain : curtains) {
			consumer.accept(curtain);
		}
	}

	private void updateCurtain(float flapness) {
		for (int segment = 0; segment < curtains.length; segment++) {
			curtains[segment]
				.setTransform(commonTransform)
				.rotateXDegrees(FlapStuffs.flapAngle(flapness, segment))
				.translateBack(AllayPortRenderer.CURTAIN_PIVOT)
				.setChanged();
		}
	}
}
