package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;
import com.nobodiiiii.createbiotech.foundation.render.EntityGeometry;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Measures the real preview renderer and chooses the lowest deterministic recumbent pose. */
public final class SurgicalTablePoseResolver {
	private static final int MAX_MEASURED_VERTICES = 200_000;
	private static final float TABLE_CLEARANCE = 1.0f / 1024.0f;
	private static final float HEIGHT_EPSILON = 1.0e-5f;
	private static final int[] CARDINAL_YAWS = { 0, 90, 180, 270 };
	private static final Map<Object, CachedPose> CACHE = new WeakHashMap<>();

	private SurgicalTablePoseResolver() {}

	public static SurgicalPose resolve(Object owner, MimicProfile profile, LivingEntity preview, Direction facing) {
		CachedPose cached = CACHE.get(owner);
		if (cached != null && cached.profile.equals(profile) && cached.facing == facing)
			return cached.pose;

		EntityGeometry.Collector geometry = EntityGeometry.Collector.caching(MAX_MEASURED_VERTICES);
		EntityGeometry.measureWithFallback(preview, geometry);
		SurgicalPose selected = createPose(geometry, RotationAxis.NONE, facing);
		SurgicalPose xPose = createPose(geometry, RotationAxis.X, facing);
		SurgicalPose zPose = createPose(geometry, RotationAxis.Z, facing);
		if (xPose.height + HEIGHT_EPSILON < selected.height)
			selected = xPose;
		if (zPose.height + HEIGHT_EPSILON < selected.height)
			selected = zPose;
		CACHE.put(owner, new CachedPose(profile, facing, selected));
		return selected;
	}

	public static void clear() {
		CACHE.clear();
	}

	private static SurgicalPose createPose(EntityGeometry.Collector geometry, RotationAxis axis,
		Direction facing) {
		int yaw = footFacingYaw(axis, facing);
		Matrix4f rotation = rotation(axis, yaw);
		EntityGeometry.Bounds bounds = geometry.transformBounds(rotation);
		return new SurgicalPose(axis, yaw,
			0.5f - bounds.centerX(), 1.0f + TABLE_CLEARANCE - bounds.minY(),
			0.5f - bounds.centerZ(), bounds.sizeY());
	}

	private static int footFacingYaw(RotationAxis axis, Direction facing) {
		if (axis == RotationAxis.NONE)
			return 0;
		Vector3f target = new Vector3f(facing.getStepX(), 0.0f, facing.getStepZ());
		int bestYaw = 0;
		float bestDot = Float.NEGATIVE_INFINITY;
		for (int yaw : CARDINAL_YAWS) {
			Vector3f foot = rotation(axis, yaw).transformDirection(new Vector3f(0.0f, -1.0f, 0.0f));
			float dot = foot.dot(target);
			if (dot > bestDot + 1.0e-6f) {
				bestDot = dot;
				bestYaw = yaw;
			}
		}
		return bestYaw;
	}

	private static Matrix4f rotation(RotationAxis axis, int yaw) {
		Matrix4f transform = new Matrix4f().rotateY((float) Math.toRadians(yaw));
		return switch (axis) {
		case NONE -> transform;
		case X -> transform.rotateX((float) (-Math.PI / 2.0d));
		case Z -> transform.rotateZ((float) (-Math.PI / 2.0d));
		};
	}

	public record SurgicalPose(RotationAxis axis, int yaw, float translateX, float translateY,
		float translateZ, float height) {
		public void apply(PoseStack poseStack) {
			poseStack.translate(translateX, translateY, translateZ);
			if (axis == RotationAxis.NONE)
				return;
			poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
			poseStack.mulPose((axis == RotationAxis.X ? Axis.XP : Axis.ZP).rotationDegrees(-90.0f));
		}
	}

	public enum RotationAxis {
		NONE,
		X,
		Z
	}

	private record CachedPose(MimicProfile profile, Direction facing, SurgicalPose pose) {}
}
