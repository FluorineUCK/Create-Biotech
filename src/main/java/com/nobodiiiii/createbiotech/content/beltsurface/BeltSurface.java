package com.nobodiiiii.createbiotech.content.beltsurface;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One side of a belt that a funnel can attach to.
 * <p>
 * Local frame is built from {@link #outwardNormal} alone, using a canonical {@code localForward} rule:
 * if {@code outwardNormal.axis == Y} then {@code localForward = world NORTH}, otherwise {@code localForward = world UP}.
 * This makes {@code localize}/{@code worldize} pure functions of {@code outwardNormal} (independent of belt motion or
 * any other run-time state).
 * <ul>
 *   <li>{@code outwardNormal == UP} (the vanilla case — funnel sits on top of a horizontal belt) yields an identity
 *       mapping between local and world frames, so existing vanilla / Ponder code that writes {@code HORIZONTAL_FACING}
 *       in world frame keeps working unchanged.</li>
 *   <li>{@code outwardNormal} on a horizontal axis (vertical / sideways belts) maps {@code local NORTH ↔ world UP},
 *       so e.g. {@code HORIZONTAL_FACING = SOUTH} means "funnel mouth points world DOWN" for a vertical belt's lateral
 *       attachment — the canonical "items go up belt, into chest above" geometry.</li>
 * </ul>
 * {@link #movementFacing} is retained for run-time behaviour (mode determination, blocking-vs-perpendicular checks);
 * it is <em>not</em> used by {@link #localize}, {@link #worldize}, {@link #transformShape}, or {@link #surfaceToWorld}.
 */
public record BeltSurface(
	BeltSurfaceHost host,
	BlockPos beltPos,
	int segmentIndex,
	Direction outwardNormal,
	Direction movementFacing,
	Quaternionf surfaceToWorld
) {
	private static final Quaternionf[] SURFACE_TO_WORLD = createSurfaceRotations();

	public BeltSurface {
		if (outwardNormal.getAxis() == movementFacing.getAxis())
			throw new IllegalArgumentException(
				"outwardNormal (" + outwardNormal + ") and movementFacing (" + movementFacing + ") share an axis");
	}

	public static BeltSurface of(BeltSurfaceHost host, BlockPos beltPos, int segmentIndex,
		Direction outwardNormal, Direction movementFacing) {
		return new BeltSurface(host, beltPos, segmentIndex, outwardNormal, movementFacing,
			SURFACE_TO_WORLD[outwardNormal.ordinal()]);
	}

	/** The canonical {@code localForward} direction (in world frame) determined solely by {@code outwardNormal}. */
	public static Direction canonicalForward(Direction outwardNormal) {
		return outwardNormal.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
	}

	/** The canonical {@code localRight} direction (in world frame): {@code canonicalForward × outwardNormal}. */
	public static Direction canonicalRight(Direction outwardNormal) {
		Direction fwd = canonicalForward(outwardNormal);
		Vec3i f = fwd.getNormal();
		Vec3i u = outwardNormal.getNormal();
		int x = f.getY() * u.getZ() - f.getZ() * u.getY();
		int y = f.getZ() * u.getX() - f.getX() * u.getZ();
		int z = f.getX() * u.getY() - f.getY() * u.getX();
		for (Direction d : Direction.values()) {
			Vec3i n = d.getNormal();
			if (n.getX() == x && n.getY() == y && n.getZ() == z)
				return d;
		}
		throw new IllegalStateException("canonicalRight produced non-cardinal vector for outward=" + outwardNormal);
	}

	/** The block position where a funnel attached to this surface sits. */
	public BlockPos funnelPos() {
		return beltPos.relative(outwardNormal);
	}

	/** Map a surface-local direction to world, using the canonical frame. */
	public Direction worldize(Direction localDir) {
		return worldizeCanonical(localDir, outwardNormal);
	}

	/** Map a world direction to surface-local frame, using the canonical frame. */
	public Direction localize(Direction worldDir) {
		return localizeCanonical(worldDir, outwardNormal);
	}

	/** Stateless canonical {@code localDir → worldDir} mapping. */
	public static Direction worldizeCanonical(Direction localDir, Direction outwardNormal) {
		return switch (localDir) {
			case UP -> outwardNormal;
			case DOWN -> outwardNormal.getOpposite();
			case NORTH -> canonicalForward(outwardNormal);
			case SOUTH -> canonicalForward(outwardNormal).getOpposite();
			case EAST -> canonicalRight(outwardNormal);
			case WEST -> canonicalRight(outwardNormal).getOpposite();
		};
	}

	/** Stateless canonical {@code worldDir → localDir} mapping. */
	public static Direction localizeCanonical(Direction worldDir, Direction outwardNormal) {
		if (worldDir == outwardNormal)
			return Direction.UP;
		if (worldDir == outwardNormal.getOpposite())
			return Direction.DOWN;
		Direction fwd = canonicalForward(outwardNormal);
		if (worldDir == fwd)
			return Direction.NORTH;
		if (worldDir == fwd.getOpposite())
			return Direction.SOUTH;
		Direction right = canonicalRight(outwardNormal);
		if (worldDir == right)
			return Direction.EAST;
		if (worldDir == right.getOpposite())
			return Direction.WEST;
		throw new IllegalStateException(
			"Cannot localize " + worldDir + " under outward=" + outwardNormal);
	}

	/** Surface +X axis in world (canonical). */
	public Direction worldRight() {
		return canonicalRight(outwardNormal);
	}

	/** Rotate a position around the block centre {@code (.5, .5, .5)} by {@link #surfaceToWorld}. */
	public Vec3 transformPosition(Vec3 position) {
		return transformPosition(position, outwardNormal);
	}

	/** Rotate a position using only a surface normal, without constructing a {@link BeltSurface}. */
	public static Vec3 transformPosition(Vec3 position, Direction outwardNormal) {
		Vec3 local = position.subtract(.5d, .5d, .5d);
		Vector3f rotated = new Vector3f((float) local.x, (float) local.y, (float) local.z)
			.rotate(surfaceToWorld(outwardNormal));
		return new Vec3(rotated.x() + .5d, rotated.y() + .5d, rotated.z() + .5d);
	}

	/** Rotate a direction vector (no translation) by {@link #surfaceToWorld}. */
	public Vec3 transformDirection(Vec3 direction) {
		return transformDirection(direction, outwardNormal);
	}

	/** Rotate a direction using only a surface normal, without constructing a {@link BeltSurface}. */
	public static Vec3 transformDirection(Vec3 direction, Direction outwardNormal) {
		Vector3f rotated = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z)
			.rotate(surfaceToWorld(outwardNormal));
		return new Vec3(rotated.x(), rotated.y(), rotated.z());
	}

	/** Cardinal direction whose unit normal best aligns with the given vector. */
	public static Direction nearestDirection(Vec3 normal) {
		Direction nearest = Direction.UP;
		double bestAlignment = Double.NEGATIVE_INFINITY;
		for (Direction direction : Direction.values()) {
			double alignment = normal.dot(Vec3.atLowerCornerOf(direction.getNormal()));
			if (alignment > bestAlignment) {
				bestAlignment = alignment;
				nearest = direction;
			}
		}
		return nearest;
	}

	/** Rotate a VoxelShape around the block centre by {@link #surfaceToWorld}, then re-aabb each box. */
	public VoxelShape transformShape(VoxelShape base) {
		return transformShape(base, outwardNormal);
	}

	/** Rotate a shape using only a surface normal, without constructing a {@link BeltSurface}. */
	public static VoxelShape transformShape(VoxelShape base, Direction outwardNormal) {
		if (base.isEmpty())
			return base;
		Quaternionf rotation = surfaceToWorld(outwardNormal);
		VoxelShape[] out = { Shapes.empty() };
		base.forAllBoxes((x1, y1, z1, x2, y2, z2) -> out[0] =
			Shapes.or(out[0], rotateBoxAabb(x1, y1, z1, x2, y2, z2, rotation)));
		return out[0].optimize();
	}

	/**
	 * Shared, read-only-by-convention rotation for an outward normal. Callers must never mutate the returned value.
	 */
	public static Quaternionf surfaceToWorld(Direction outwardNormal) {
		return SURFACE_TO_WORLD[outwardNormal.ordinal()];
	}

	private static VoxelShape rotateBoxAabb(double x1, double y1, double z1, double x2, double y2, double z2,
		Quaternionf q) {
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		for (int c = 0; c < 8; c++) {
			double x = (c & 1) != 0 ? x2 : x1;
			double y = (c & 2) != 0 ? y2 : y1;
			double z = (c & 4) != 0 ? z2 : z1;
			Vector3f v = new Vector3f((float) (x - .5d), (float) (y - .5d), (float) (z - .5d)).rotate(q);
			double rx = v.x + .5d;
			double ry = v.y + .5d;
			double rz = v.z + .5d;
			if (rx < minX) minX = rx;
			if (ry < minY) minY = ry;
			if (rz < minZ) minZ = rz;
			if (rx > maxX) maxX = rx;
			if (ry > maxY) maxY = ry;
			if (rz > maxZ) maxZ = rz;
		}
		return Shapes.create(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static Quaternionf computeSurfaceToWorld(Direction outwardNormal) {
		Direction forward = canonicalForward(outwardNormal);
		Vec3i u = outwardNormal.getNormal();
		Vec3i f = forward.getNormal();
		// right = forward × up (right-hand rule)
		float rx = f.getY() * u.getZ() - f.getZ() * u.getY();
		float ry = f.getZ() * u.getX() - f.getX() * u.getZ();
		float rz = f.getX() * u.getY() - f.getY() * u.getX();
		// Columns (JOML Matrix3f constructor is column-major): right, up, back (= -forward)
		Matrix3f m = new Matrix3f(
			rx, ry, rz,
			u.getX(), u.getY(), u.getZ(),
			-f.getX(), -f.getY(), -f.getZ()
		);
		return new Quaternionf().setFromUnnormalized(m);
	}

	private static Quaternionf[] createSurfaceRotations() {
		Direction[] directions = Direction.values();
		Quaternionf[] rotations = new Quaternionf[directions.length];
		for (Direction direction : directions)
			rotations[direction.ordinal()] = computeSurfaceToWorld(direction);
		return rotations;
	}
}
