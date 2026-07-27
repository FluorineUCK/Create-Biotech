package com.nobodiiiii.createbiotech.content.slimebelt;

import java.util.Map;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltLoopGeometry.Track;
import com.simibubi.create.AllTags.AllItemTags;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Block-entity lookups and movement-direction-aware helpers for slime belts. All
 * direction-independent loop math lives in {@link SlimeBeltLoopGeometry}, obtained from the
 * controller via {@code getLoop()}; the geometry delegations kept here preserve the
 * historical static call sites.
 */
public class SlimeBeltHelper {

	public static Map<Item, Boolean> uprightCache = new Object2BooleanOpenHashMap<>();
	public static final ResourceManagerReloadListener LISTENER = resourceManager -> uprightCache.clear();

	public static boolean isItemUpright(ItemStack stack) {
		return uprightCache.computeIfAbsent(
			stack.getItem(),
			item -> {
				boolean isFluidHandler = stack.getCapability(Capabilities.FluidHandler.ITEM) != null;
				boolean useUpright = AllItemTags.UPRIGHT_ON_BELT.matches(stack);
				boolean forceDisableUpright = !AllItemTags.NOT_UPRIGHT_ON_BELT.matches(stack);

				return (isFluidHandler || useUpright) && forceDisableUpright;
			}
		);
	}

	public static SlimeBeltBlockEntity getSegmentBE(BlockGetter world, BlockPos pos) {
		if (world instanceof Level l && !l.isLoaded(pos))
			return null;
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (!(blockEntity instanceof SlimeBeltBlockEntity))
			return null;
		return (SlimeBeltBlockEntity) blockEntity;
	}

	public static SlimeBeltBlockEntity getControllerBE(BlockGetter world, BlockPos pos) {
		SlimeBeltBlockEntity segment = getSegmentBE(world, pos);
		if (segment == null)
			return null;
		BlockPos controllerPos = segment.getController();
		if (controllerPos == null)
			return null;
		return getSegmentBE(world, controllerPos);
	}

	// --- Geometry delegations ---

	public static BlockPos getPositionForOffset(SlimeBeltBlockEntity controller, int offset) {
		return controller.getLoop()
			.blockAt(offset);
	}

	public static float getLoopLength(SlimeBeltBlockEntity controller) {
		return controller.getLoop()
			.loopLength();
	}

	public static float getConnectorLength(SlimeBeltBlockEntity controller) {
		return controller.getLoop()
			.connectorLength();
	}

	public static float normalizeLoopPosition(SlimeBeltBlockEntity controller, float loopPosition) {
		return controller.getLoop()
			.normalize(loopPosition);
	}

	public static SlimeBeltLoopGeometry.LoopSection getLoopSection(SlimeBeltBlockEntity controller, float loopPosition) {
		return controller.getLoop()
			.section(loopPosition);
	}

	public static float getFrontOffsetForLoopPosition(SlimeBeltBlockEntity controller, float loopPosition) {
		return controller.getLoop()
			.frontOffset(loopPosition);
	}

	public static Vec3 getVectorForOffset(SlimeBeltBlockEntity controller, float loopPosition) {
		return controller.getLoop()
			.worldPos(loopPosition);
	}

	public static Vec3 getTrackNormal(SlimeBeltBlockEntity controller, float loopPosition) {
		return controller.getLoop()
			.normal(loopPosition);
	}

	public static Vec3 getTrackNormal(SlimeBeltBlockEntity controller, int segment, Track track) {
		return controller.getLoop()
			.trackNormal(segment, track);
	}

	public static Vec3 getTrackCenterVector(SlimeBeltBlockEntity controller, int segment, Track track) {
		return controller.getLoop()
			.trackCenter(segment, track);
	}

	public static Direction getRepresentativeSideForTrack(SlimeBeltBlockEntity controller, int segment, Track track) {
		return controller.getLoop()
			.representativeSide(segment, track);
	}

	public static boolean isTrackClosestToInputSide(SlimeBeltBlockEntity controller, int segment, Track track,
		Direction inputSide) {
		return controller.getLoop()
			.isTrackClosestToInputSide(segment, track, inputSide);
	}

	public static Direction.Axis getChainBlockAxis(SlimeBeltBlockEntity controller) {
		return controller.getLoop()
			.chainAxis();
	}

	public static Vec3 getPathAxis(SlimeBeltBlockEntity controller) {
		return controller.getLoop()
			.pathAxis();
	}

	// --- Movement-direction-aware resolution ---

	/** Track an insert from the given side addresses, or null when the side is rejected. */
	public static Track resolveIOTrack(SlimeBeltBlockEntity controller, int segment, Direction side) {
		return resolveIOTrack(controller, segment, side, false);
	}

	public static Track resolveIOTrack(SlimeBeltBlockEntity controller, int segment, Direction side,
		boolean preferEndpointEntryTrack) {
		if (controller == null || controller.beltLength <= 0)
			return null;

		// A direct handoff from a vertical belt must enter the horizontal loop at
		// this endpoint's ingress track. Keep this opt-in so ordinary UP/DOWN
		// capability and funnel access can still address the physical FRONT/BACK
		// surfaces away from belt-to-belt handoffs.
		if (preferEndpointEntryTrack && side != null && side.getAxis().isVertical()
			&& controller.getBlockState().getValue(SlimeBeltBlock.SLOPE) == BeltSlope.HORIZONTAL) {
			Track entryTrack = getEndpointEntryTrack(controller, segment);
			if (entryTrack != null)
				return entryTrack;
		}

		if (side != null && side.getAxis() == getChainBlockAxis(controller)) {
			Track entryTrack = getEndpointEntryTrack(controller, segment);
			if (entryTrack == null)
				return null;
			// Chain-axis input at an endpoint is a chain-continuation: the entering
			// item's motion direction (side) must match the motion of whichever track
			// has its entry at this segment. Otherwise reject (e.g. antiparallel
			// belts pointing at each other).
			Direction entryMotion = entryTrack == Track.FRONT
				? controller.getMovementFacing()
				: controller.getMovementFacing().getOpposite();
			if (side != entryMotion)
				return null;
			return entryTrack;
		}

		Track track = SlimeBeltLoopGeometry.resolveInputTrack(controller.getBlockState(), side);
		if (side != null && !isTrackClosestToInputSide(controller, segment, track, side))
			return null;
		return track;
	}

	public static Track getEndpointEntryTrack(SlimeBeltBlockEntity controller, int segment) {
		if (controller == null || controller.beltLength <= 0)
			return null;
		boolean atStart = segment == 0;
		boolean atEnd = segment == controller.beltLength - 1;
		if (!atStart && !atEnd)
			return null;
		boolean positiveBelt = controller.getDirectionAwareBeltMovementSpeed() > 0;
		if (atStart)
			return positiveBelt ? Track.FRONT : Track.BACK;
		return positiveBelt ? Track.BACK : Track.FRONT;
	}

	public static Track getEndpointOutputTrack(SlimeBeltBlockEntity controller, int segment) {
		Track entryTrack = getEndpointEntryTrack(controller, segment);
		if (entryTrack == null)
			return null;
		return entryTrack == Track.FRONT ? Track.BACK : Track.FRONT;
	}

	public static Direction getMovementFacingForTrack(SlimeBeltBlockEntity controller, Track track) {
		Direction frontMovement = controller.getMovementFacing();
		return track == Track.FRONT ? frontMovement : frontMovement.getOpposite();
	}

	public static Vec3 getBeltVector(BlockState state) {
		BeltSlope slope = state.getValue(SlimeBeltBlock.SLOPE);
		int verticality = slope == BeltSlope.DOWNWARD ? -1 : slope == BeltSlope.UPWARD ? 1 : 0;
		Vec3 horizontalMovement = Vec3.atLowerCornerOf(state.getValue(SlimeBeltBlock.HORIZONTAL_FACING)
			.getNormal());
		if (slope == BeltSlope.VERTICAL)
			return new Vec3(0, state.getValue(SlimeBeltBlock.HORIZONTAL_FACING)
				.getAxisDirection()
				.getStep(), 0);
		return new Vec3(0, verticality, 0).add(horizontalMovement);
	}

}
