package com.nobodiiiii.createbiotech.mixin.compat.sable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.nobodiiiii.createbiotech.content.universaljoint.UniversalJointBlockEntity;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(UniversalJointBlockEntity.class)
public abstract class UniversalJointBlockEntitySableMixin implements BlockEntitySubLevelActor {

	@Unique
	private static final double CREATE_BIOTECH$MIN_DISTANCE = 1.0E-4d;

	@Unique
	@Nullable
	private ForceTotal createBiotech$localForces;

	@Unique
	@Nullable
	private ForceTotal createBiotech$peerForces;

	@Override
	public void sable$physicsTick(ServerSubLevel currentSubLevel, RigidBodyHandle currentHandle,
		double timeStep) {
		UniversalJointBlockEntity endpoint = (UniversalJointBlockEntity) (Object) this;
		Level level = endpoint.getLevel();
		if (level == null || level.isClientSide || !Double.isFinite(timeStep) || timeStep <= 0
			|| currentHandle == null || !currentHandle.isValid()
			|| !endpoint.isAtExpectedOwnAddress()
			|| !endpoint.hasVerifiedLink()
			|| !Objects.equals(endpoint.getContainingSubLevelId(), currentSubLevel.getUniqueId()))
			return;

		UniversalJointBlockEntity peer = endpoint.getLoadedLinkedJoint();
		if (peer == null || !peer.references(endpoint) || !endpoint.references(peer)
			|| !endpoint.shouldOwnElasticLink(peer))
			return;

		UUID peerSpaceId = peer.getContainingSubLevelId();
		ServerSubLevel peerSpace = peerSpaceId == null ? null
			: SubLevelCompat.findSubLevel(level, peerSpaceId) instanceof ServerSubLevel server
				? server : null;
		if (peerSpaceId != null && peerSpace == null)
			return;
		if (peerSpace == currentSubLevel)
			return;

		Vec3 localPoint = Vec3.atCenterOf(endpoint.getBlockPos());
		Vec3 peerLocalPoint = Vec3.atCenterOf(peer.getBlockPos());
		Vec3 worldPoint = SubLevelCompat.toWorld(currentSubLevel, localPoint);
		Vec3 peerWorldPoint = SubLevelCompat.toWorld(peerSpace, peerLocalPoint);
		Vec3 displacement = peerWorldPoint.subtract(worldPoint);
		double distance = displacement.length();
		if (!Double.isFinite(distance) || distance < CREATE_BIOTECH$MIN_DISTANCE)
			return;
		if (distance >= UniversalJointBlockEntity.getElasticDisconnectRange()) {
			endpoint.requestOverstretchBreak();
			return;
		}

		double progress = UniversalJointBlockEntity.getStretchProgress(distance);
		if (progress <= 0)
			return;

		Vec3 direction = displacement.scale(1.0d / distance);
		Vec3 localVelocity =
			SubLevelCompat.getWorldVelocity(level, currentSubLevel, localPoint);
		Vec3 peerVelocity =
			SubLevelCompat.getWorldVelocity(level, peerSpace, peerLocalPoint);
		double radialSpeed = peerVelocity.subtract(localVelocity).dot(direction);

		// Cubic smoothstep gives a zero-slope onset and reaches the configured peak exactly.
		double smoothProgress = progress * progress * (3.0d - 2.0d * progress);
		double springForce = CBConfigs.SERVER.universalJoint.peakTension.get() * smoothProgress;
		double extension = distance - UniversalJointBlockEntity.getStrainStartDistance();
		double stiffness = extension > CREATE_BIOTECH$MIN_DISTANCE
			? springForce / extension : 0.0d;
		double inverseNormalMass =
			createBiotech$inverseNormalMass(currentSubLevel, localPoint, direction);
		if (peerSpace != null)
			inverseNormalMass +=
				createBiotech$inverseNormalMass(peerSpace, peerLocalPoint, direction.reverse());
		if (!Double.isFinite(inverseNormalMass) || inverseNormalMass <= 0)
			return;

		/*
		 * Solve the radial spring and damper at the end of this substep instead of applying their
		 * explicit forces. The effective point mass includes angular inertia, so a light or
		 * off-centre structure cannot receive an impulse large enough to reverse its radial
		 * velocity and add energy. Signed damping also removes energy on the return swing.
		 */
		double damping = CBConfigs.SERVER.universalJoint.separationDamping.get();
		double response = stiffness * timeStep + damping;
		double denominator = 1.0d + inverseNormalMass * response * timeStep;
		double impulse = timeStep * (springForce + response * radialSpeed) / denominator;
		double maxImpulse = CBConfigs.SERVER.universalJoint.maxImpulse.get();
		impulse = Math.copySign(Math.min(Math.abs(impulse), maxImpulse), impulse);
		if (!Double.isFinite(impulse) || impulse == 0)
			return;

		createBiotech$applyImpulse(currentSubLevel, currentHandle, localPoint,
			peerSpace, peerLocalPoint, direction.scale(impulse));
	}

	@Override
	@Nullable
	public Iterable<SubLevel> sable$getConnectionDependencies() {
		UniversalJointBlockEntity endpoint = (UniversalJointBlockEntity) (Object) this;
		Level level = endpoint.getLevel();
		if (level == null || !endpoint.isAtExpectedOwnAddress() || !endpoint.hasLink())
			return null;
		UUID peerSpaceId = endpoint.getLinkedSubLevelId();
		if (peerSpaceId == null)
			return null;
		if (!(SubLevelCompat.findSubLevel(level, peerSpaceId) instanceof SubLevel dependency))
			return null;
		return List.of(dependency);
	}

	@Unique
	private void createBiotech$applyImpulse(ServerSubLevel currentSpace,
		RigidBodyHandle currentHandle, Vec3 localPoint, @Nullable ServerSubLevel peerSpace,
		Vec3 peerLocalPoint, Vec3 worldImpulse) {
		RigidBodyHandle peerHandle = null;
		if (peerSpace != null) {
			peerHandle = RigidBodyHandle.of(peerSpace);
			if (peerHandle == null || !peerHandle.isValid())
				return;
		}

		Vec3 localImpulse = SubLevelCompat.worldNormalToLocal(currentSpace, worldImpulse);
		if (createBiotech$localForces == null)
			createBiotech$localForces = new ForceTotal();
		createBiotech$localForces.applyImpulseAtPoint(currentSpace, createBiotech$joml(localPoint),
			createBiotech$joml(localImpulse));
		currentHandle.applyForcesAndReset(createBiotech$localForces);

		if (peerSpace == null)
			return;
		Vec3 oppositeLocal =
			SubLevelCompat.worldNormalToLocal(peerSpace, worldImpulse.reverse());
		if (createBiotech$peerForces == null)
			createBiotech$peerForces = new ForceTotal();
		createBiotech$peerForces.applyImpulseAtPoint(peerSpace, createBiotech$joml(peerLocalPoint),
			createBiotech$joml(oppositeLocal));
		peerHandle.applyForcesAndReset(createBiotech$peerForces);
	}

	@Unique
	private static double createBiotech$inverseNormalMass(ServerSubLevel subLevel,
		Vec3 localPoint, Vec3 worldDirection) {
		MassData mass = subLevel.getMassTracker();
		if (mass == null || mass.isInvalid())
			return Double.NaN;
		Vec3 localDirection = SubLevelCompat.worldNormalToLocal(subLevel, worldDirection);
		return mass.getInverseNormalMass(createBiotech$joml(localPoint),
			createBiotech$joml(localDirection));
	}

	@Unique
	private static Vector3d createBiotech$joml(Vec3 value) {
		return new Vector3d(value.x, value.y, value.z);
	}
}
