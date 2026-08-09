package com.nobodiiiii.createbiotech.client.render;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.foundation.block.CBBeltPlacementSegment;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;
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

/** Shared Flywheel implementation for magma, slime and power belts. */
public abstract class CBBeltVisual<T extends KineticBlockEntity & CBBeltPlacementSegment>
	extends KineticBlockEntityVisual<T> {
	private static final float MAGIC_SCROLL_MULTIPLIER = 1f / (31.5f * 16f);
	private static final float SCROLL_FACTOR_DIAGONAL = 3f / 8f;
	private static final float SCROLL_FACTOR_OTHERWISE = .5f;

	@FunctionalInterface
	public interface SpriteProvider {
		SpriteShiftEntry get(boolean diagonal, boolean bottom);
	}

	private final SpriteProvider sprites;
	private final float verticalSurfaceOffset;
	protected final ScrollInstance[] belts;
	@Nullable
	protected final RotatingInstance pulley;

	protected CBBeltVisual(VisualizationContext context, T blockEntity, float partialTick,
		SpriteProvider sprites, float verticalSurfaceOffset) {
		super(context, blockEntity, partialTick);
		this.sprites = sprites;
		this.verticalSurfaceOffset = verticalSurfaceOffset;
		CBBeltRenderState state = CBBeltRenderState.of(blockState);
		belts = new ScrollInstance[state.diagonal() ? 1 : 2];
		for (boolean bottom : Iterate.trueAndFalse) {
			PartialModel partial = BeltRenderer.getBeltPartial(state.diagonal(), state.start(), state.end(), bottom);
			Instancer<ScrollInstance> model =
				instancerProvider().instancer(AllInstanceTypes.SCROLLING, Models.partial(partial));
			belts[bottom ? 0 : 1] = setup(model.createInstance(), bottom, state);
			if (state.diagonal())
				break;
		}

		if (blockEntity.createBiotech$hasPulley()) {
			pulley = instancerProvider().instancer(AllInstanceTypes.ROTATING, getPulleyModel(state)).createInstance();
			pulley.setup(blockEntity).setPosition(getVisualPosition()).setChanged();
		} else {
			pulley = null;
		}
	}

	@Override
	public void update(float partialTick) {
		CBBeltRenderState state = CBBeltRenderState.of(blockState);
		boolean bottom = true;
		for (ScrollInstance belt : belts) {
			setup(belt, bottom, state);
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

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		if (pulley != null)
			consumer.accept(pulley);
		for (ScrollInstance belt : belts)
			consumer.accept(belt);
	}

	private ScrollInstance setup(ScrollInstance belt, boolean bottom, CBBeltRenderState state) {
		belt.setSpriteShift(sprites.get(state.diagonal(), bottom), 1f,
			state.diagonal() ? SCROLL_FACTOR_DIAGONAL : SCROLL_FACTOR_OTHERWISE)
			.position(getVisualPosition())
			.shift(0, state.surfaceYOffset(verticalSurfaceOffset), 0)
			.rotation(state.flywheelRotation())
			.speed(0, state.flywheelSpeed(blockEntity.getSpeed()) * MAGIC_SCROLL_MULTIPLIER)
			.offset(0, bottom ? .5f : 0)
			.colorRgb(RotatingInstance.colorFromBE(blockEntity))
			.setChanged();
		return belt;
	}

	private Model getPulleyModel(CBBeltRenderState state) {
		Direction direction = state.pulleyOrientation();
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
}
