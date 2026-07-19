package com.nobodiiiii.createbiotech.content.universaljoint;

import net.minecraft.core.HolderLookup;

import java.util.List;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class UniversalJointBlockEntity extends KineticBlockEntity {

	private static final double ENDPOINT_INNER_OFFSET = 4 / 16d;
	private static final double SHAFT_SIDE_EPSILON = 1.0E-7d;
	private static final double SHAFT_RADIUS = 4 / 16d / 2d;
	private static final Vec3 SHAFT_STUCK_MULTIPLIER = new Vec3(0.4d, 0.4d, 0.4d);

	@Nullable
	private BlockPos linkedPos;

	public UniversalJointBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.UNIVERSAL_JOINT.get(), pos, state);
	}

	@Override
	protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
		if (linkedPos != null)
			compound.put("LinkedJoint", NbtUtils.writeBlockPos(linkedPos));
		super.write(compound, registries, clientPacket);
	}

	@Override
	protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(compound, registries, clientPacket);
		linkedPos = NbtUtils.readBlockPos(compound, "LinkedJoint").orElse(null);
		invalidateRenderBoundingBox();
	}

	public void setLinkedPos(BlockPos linkedPos) {
		this.linkedPos = linkedPos.immutable();
		updateSpeed = true;
		invalidateRenderBoundingBox();
		notifyUpdate();
	}

	@Nullable
	public BlockPos getLinkedPos() {
		return linkedPos;
	}

	public void clearLink() {
		linkedPos = null;
		invalidateRenderBoundingBox();
		if (level != null && !level.isClientSide) {
			detachKinetics();
			removeSource();
			updateSpeed = true;
		}
		notifyUpdate();
	}

	@Override
	public void tick() {
		super.tick();
		applyShaftSlowEffect();
	}

	private void applyShaftSlowEffect() {
		if (level == null || linkedPos == null)
			return;
		if (!isPrimaryEndpoint(getBlockPos(), linkedPos))
			return;
		if (!level.isLoaded(linkedPos))
			return;

		BlockEntity linkedBlockEntity = level.getBlockEntity(linkedPos);
		if (!(linkedBlockEntity instanceof UniversalJointBlockEntity linkedJoint))
			return;
		if (!getBlockPos().equals(linkedJoint.getLinkedPos()))
			return;

		BlockState state = getBlockState();
		BlockState linkedState = linkedJoint.getBlockState();
		if (!state.hasProperty(UniversalJointBlock.FACING) || !linkedState.hasProperty(UniversalJointBlock.FACING))
			return;

		Vec3 start = getInnerEndpoint(getBlockPos(), state);
		Vec3 end = getInnerEndpoint(linkedPos, linkedState);
		if (start.distanceToSqr(end) < 1.0E-8d)
			return;

		AABB queryBox = new AABB(start, end).inflate(SHAFT_RADIUS);
		for (Player player : level.getEntitiesOfClass(Player.class, queryBox)) {
			if (player.isSpectator())
				continue;
			AABB inflated = player.getBoundingBox().inflate(SHAFT_RADIUS);
			if (segmentIntersectsBox(start, end, inflated))
				player.makeStuckInBlock(state, SHAFT_STUCK_MULTIPLIER);
		}
	}

	private static boolean isPrimaryEndpoint(BlockPos first, BlockPos second) {
		if (first.getX() != second.getX())
			return first.getX() < second.getX();
		if (first.getY() != second.getY())
			return first.getY() < second.getY();
		return first.getZ() < second.getZ();
	}

	private static boolean segmentIntersectsBox(Vec3 from, Vec3 to, AABB box) {
		if (box.contains(from) || box.contains(to))
			return true;

		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;

		double[] range = { 0d, 1d };
		if (!clipSlab(from.x, dx, box.minX, box.maxX, range))
			return false;
		if (!clipSlab(from.y, dy, box.minY, box.maxY, range))
			return false;
		return clipSlab(from.z, dz, box.minZ, box.maxZ, range);
	}

	private static boolean clipSlab(double origin, double direction, double min, double max, double[] range) {
		if (Math.abs(direction) < 1.0E-8d)
			return origin >= min && origin <= max;
		double t1 = (min - origin) / direction;
		double t2 = (max - origin) / direction;
		if (t1 > t2) {
			double tmp = t1;
			t1 = t2;
			t2 = tmp;
		}
		range[0] = Math.max(range[0], t1);
		range[1] = Math.min(range[1], t2);
		return range[0] <= range[1];
	}

	@Override
	public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
		if (linkedPos != null && level != null && level.isLoaded(linkedPos))
			neighbours.add(linkedPos);
		return neighbours;
	}

	@Override
	public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
		return isPairedWith(other);
	}

	@Override
	public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom, BlockState stateTo, BlockPos diff,
		boolean connectedViaAxes, boolean connectedViaCogs) {
		return isPairedWith(target) ? getRotationModifier(stateFrom, stateTo, diff) : 0;
	}

	@Override
	public AABB createRenderBoundingBox() {
		if (linkedPos == null)
			return super.createRenderBoundingBox();

		int minX = Math.min(worldPosition.getX(), linkedPos.getX());
		int minY = Math.min(worldPosition.getY(), linkedPos.getY());
		int minZ = Math.min(worldPosition.getZ(), linkedPos.getZ());
		int maxX = Math.max(worldPosition.getX(), linkedPos.getX()) + 1;
		int maxY = Math.max(worldPosition.getY(), linkedPos.getY()) + 1;
		int maxZ = Math.max(worldPosition.getZ(), linkedPos.getZ()) + 1;
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(1);
	}

	private boolean isPairedWith(KineticBlockEntity target) {
		if (!(target instanceof UniversalJointBlockEntity other))
			return false;
		return linkedPos != null && linkedPos.equals(other.getBlockPos())
			&& other.linkedPos != null && other.linkedPos.equals(getBlockPos());
	}

	public static Vec3 getInnerEndpoint(BlockPos pos, BlockState state) {
		Direction facing = state.getValue(UniversalJointBlock.FACING);
		return Vec3.atLowerCornerOf(pos)
			.add(.5d + facing.getStepX() * ENDPOINT_INNER_OFFSET,
				.5d + facing.getStepY() * ENDPOINT_INNER_OFFSET,
				.5d + facing.getStepZ() * ENDPOINT_INNER_OFFSET);
	}

	public static float getShaftRotationModifier(BlockState state, BlockState linkedState, BlockPos diffToLinked) {
		if (!state.hasProperty(UniversalJointBlock.FACING) || !linkedState.hasProperty(UniversalJointBlock.FACING))
			return 1;

		Vec3 shaft = getShaftVector(state, linkedState, diffToLinked);
		return getEndpointShaftModifier(state.getValue(UniversalJointBlock.FACING), shaft);
	}

	public static float getRotationModifier(BlockState stateFrom, BlockState stateTo, BlockPos diff) {
		if (!stateFrom.hasProperty(UniversalJointBlock.FACING) || !stateTo.hasProperty(UniversalJointBlock.FACING))
			return 0;

		Direction fromFacing = stateFrom.getValue(UniversalJointBlock.FACING);
		Direction toFacing = stateTo.getValue(UniversalJointBlock.FACING);
		if (fromFacing.getAxis() == toFacing.getAxis())
			return fromFacing == toFacing ? -1 : 1;

		Vec3 shaft = getShaftVector(stateFrom, stateTo, diff);
		float fromModifier = getEndpointShaftModifier(fromFacing, shaft);
		float toModifier = getEndpointShaftModifier(toFacing, shaft);
		return fromModifier * toModifier;
	}

	private static Vec3 getShaftVector(BlockState stateFrom, BlockState stateTo, BlockPos diff) {
		Direction fromFacing = stateFrom.getValue(UniversalJointBlock.FACING);
		Direction toFacing = stateTo.getValue(UniversalJointBlock.FACING);
		return new Vec3(diff.getX() + (toFacing.getStepX() - fromFacing.getStepX()) * ENDPOINT_INNER_OFFSET,
			diff.getY() + (toFacing.getStepY() - fromFacing.getStepY()) * ENDPOINT_INNER_OFFSET,
			diff.getZ() + (toFacing.getStepZ() - fromFacing.getStepZ()) * ENDPOINT_INNER_OFFSET);
	}

	private static float getEndpointShaftModifier(Direction facing, Vec3 shaft) {
		int axisSign = facing.getAxisDirection().getStep();
		double side = shaft.x * facing.getStepX() + shaft.y * facing.getStepY() + shaft.z * facing.getStepZ();
		if (Math.abs(side) < SHAFT_SIDE_EPSILON)
			return axisSign;
		return (float) (axisSign * Math.signum(side));
	}
}
