package com.nobodiiiii.createbiotech.content.slimebelt;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.processing.burner.ScrollInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

public class SlimeBeltVisual extends KineticBlockEntityVisual<SlimeBeltBlockEntity> {

	private static final float MAGIC_SCROLL_MULTIPLIER = 1f / (31.5f * 16f);
	private static final float SCROLL_FACTOR_DIAGONAL = 3f / 8f;
	private static final float SCROLL_FACTOR_OTHERWISE = .5f;

	protected final ScrollInstance[] belts;
	@Nullable
	protected final RotatingInstance pulley;

	public SlimeBeltVisual(VisualizationContext context, SlimeBeltBlockEntity blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);
		BeltPart part = blockState.getValue(SlimeBeltBlock.PART);
		boolean start = part == BeltPart.START;
		boolean end = part == BeltPart.END;
		boolean diagonal = blockState.getValue(SlimeBeltBlock.SLOPE).isDiagonal();
		belts = new ScrollInstance[diagonal ? 1 : 2];

		for (boolean bottom : Iterate.trueAndFalse) {
			PartialModel beltPartial = getBeltPartial(diagonal, start, end, bottom);
			SpriteShiftEntry spriteShift = SlimeBeltRenderer.getSpriteShiftEntry(diagonal, bottom);
			Instancer<ScrollInstance> beltModel =
				instancerProvider().instancer(AllInstanceTypes.SCROLLING, Models.partial(beltPartial));
			belts[bottom ? 0 : 1] = setup(beltModel.createInstance(), bottom, spriteShift);
			if (diagonal)
				break;
		}

		if (blockEntity.hasPulley()) {
			pulley = instancerProvider().instancer(AllInstanceTypes.ROTATING, getPulleyModel()).createInstance();
			pulley.setup(blockEntity).setPosition(getVisualPosition()).setChanged();
		} else {
			pulley = null;
		}
	}

	@Override
	public void update(float partialTick) {
		boolean diagonal = blockState.getValue(SlimeBeltBlock.SLOPE).isDiagonal();
		boolean bottom = true;
		for (ScrollInstance belt : belts) {
			setup(belt, bottom, SlimeBeltRenderer.getSpriteShiftEntry(diagonal, bottom));
			bottom = false;
		}
		if (pulley != null)
			pulley.setup(blockEntity).setChanged();
	}

	@Override
	public void updateLight(float partialTick) {
		relight(belts);
		if (pulley != null)
			relight(pulley);
	}

	@Override
	protected void _delete() {
		for (ScrollInstance belt : belts)
			belt.delete();
		if (pulley != null)
			pulley.delete();
	}

	private PartialModel getBeltPartial(boolean diagonal, boolean start, boolean end, boolean bottom) {
		if (diagonal) {
			if (start)
				return AllPartialModels.BELT_DIAGONAL_START;
			if (end)
				return AllPartialModels.BELT_DIAGONAL_END;
			return AllPartialModels.BELT_DIAGONAL_MIDDLE;
		}
		if (bottom) {
			if (start)
				return AllPartialModels.BELT_START_BOTTOM;
			if (end)
				return AllPartialModels.BELT_END_BOTTOM;
			return AllPartialModels.BELT_MIDDLE_BOTTOM;
		}
		if (start)
			return AllPartialModels.BELT_START;
		if (end)
			return AllPartialModels.BELT_END;
		return AllPartialModels.BELT_MIDDLE;
	}

	private Model getPulleyModel() {
		Direction direction = getOrientation();
		return Models.partial(AllPartialModels.BELT_PULLEY, direction.getAxis(), (axis, modelTransform) -> {
			var transform = TransformStack.of(modelTransform);
			transform.center();
			if (axis == Direction.Axis.X)
				transform.rotateYDegrees(90);
			if (axis == Direction.Axis.Y)
				transform.rotateXDegrees(90);
			transform.rotateXDegrees(90);
			transform.uncenter();
		});
	}

	private Direction getOrientation() {
		Direction direction = blockState.getValue(SlimeBeltBlock.HORIZONTAL_FACING).getClockWise();
		if (blockState.getValue(SlimeBeltBlock.SLOPE) == BeltSlope.SIDEWAYS)
			direction = Direction.UP;
		return direction;
	}

	private ScrollInstance setup(ScrollInstance belt, boolean bottom, SpriteShiftEntry spriteShift) {
		BeltSlope slope = blockState.getValue(SlimeBeltBlock.SLOPE);
		Direction facing = blockState.getValue(SlimeBeltBlock.HORIZONTAL_FACING);
		boolean diagonal = slope.isDiagonal();
		boolean sideways = slope == BeltSlope.SIDEWAYS;
		boolean vertical = slope == BeltSlope.VERTICAL;
		boolean upward = slope == BeltSlope.UPWARD;
		boolean alongX = facing.getAxis() == Direction.Axis.X;
		boolean alongZ = facing.getAxis() == Direction.Axis.Z;
		boolean downward = slope == BeltSlope.DOWNWARD;

		float speed = blockEntity.getSpeed();
		if (facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE ^ upward
			^ (alongX && !diagonal || alongZ && diagonal))
			speed = -speed;
		if (sideways && (facing == Direction.SOUTH || facing == Direction.WEST)
			|| vertical && facing == Direction.EAST)
			speed = -speed;

		float rotX = (!diagonal && slope != BeltSlope.HORIZONTAL ? 90 : 0) + (downward ? 180 : 0)
			+ (sideways ? 90 : 0) + (vertical && alongZ ? 180 : 0);
		float rotY = facing.toYRot() + (diagonal != alongX && !downward ? 180 : 0)
			+ (sideways && alongZ ? 180 : 0) + (vertical && alongX ? 90 : 0);
		float rotZ = (sideways ? 90 : 0) + (vertical && alongX ? 90 : 0);
		Quaternionf rotation = new Quaternionf()
			.rotationXYZ(rotX * Mth.DEG_TO_RAD, rotY * Mth.DEG_TO_RAD, rotZ * Mth.DEG_TO_RAD);

		belt.setSpriteShift(spriteShift, 1f, diagonal ? SCROLL_FACTOR_DIAGONAL : SCROLL_FACTOR_OTHERWISE)
			.position(getVisualPosition())
			.rotation(rotation)
			.speed(0, speed * MAGIC_SCROLL_MULTIPLIER)
			.offset(0, bottom ? .5f : 0f)
			.colorRgb(RotatingInstance.colorFromBE(blockEntity))
			.setChanged();
		return belt;
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		if (pulley != null)
			consumer.accept(pulley);
		for (ScrollInstance belt : belts)
			consumer.accept(belt);
	}
}
