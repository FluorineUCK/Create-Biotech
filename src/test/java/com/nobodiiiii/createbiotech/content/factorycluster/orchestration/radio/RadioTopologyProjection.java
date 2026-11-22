package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import net.minecraft.world.phys.Vec3;

/**
 * Validates value-only live-world evidence before it becomes a worker-visible topology point.
 *
 * <p>The server thread is responsible for gathering {@link Evidence} with non-loading chunk and
 * block-entity queries. This class intentionally accepts neither a Level nor a Sable structure,
 * keeping that mutable state on the server-thread side of the boundary.</p>
 */
public final class RadioTopologyProjection {
	private RadioTopologyProjection() {}

	/** Returns no point unless the loaded Computer evidence exactly matches the observed endpoint. */
	@Nullable
	public static RadioTopologyTracker.ResolvedPose resolve(RadioEndpoint endpoint,
		@Nullable Evidence evidence) {
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		if (evidence == null || !endpoint.key().equals(evidence.key())
			|| endpoint.generation() != evidence.generation()
			|| !endpoint.address().equals(evidence.source())
			|| endpoint.listenerCapacity() != evidence.profile().listenerCapacity()
			|| !endpoint.capabilities().equals(evidence.profile().capabilities())
			|| !evidence.physicalStructureId().equals(
				Optional.ofNullable(endpoint.address().subLevelId()))) return null;
		return new RadioTopologyTracker.ResolvedPose(evidence.worldCenter(),
			evidence.physicalStructureId());
	}

	/** Immutable evidence captured from one actually loaded Computer block entity. */
	public record Evidence(RadioEndpoint.Key key, long generation, SpaceAddress source,
		Vec3 worldCenter, Optional<UUID> physicalStructureId, ProfileProof profile) {
		public Evidence {
			key = Objects.requireNonNull(key, "key");
			if (generation < 0) throw new IllegalArgumentException("negative endpoint generation");
			source = Objects.requireNonNull(source, "source");
			worldCenter = Objects.requireNonNull(worldCenter, "worldCenter");
			physicalStructureId = Objects.requireNonNull(physicalStructureId,
				"physicalStructureId");
			profile = Objects.requireNonNull(profile, "profile");
			if (!physicalStructureId.equals(Optional.ofNullable(source.subLevelId())))
				throw new IllegalArgumentException("evidence physical structure differs from source");
			worldCenter = new Vec3(worldCenter.x, worldCenter.y, worldCenter.z);
		}
	}

	/** Exact radio fields derived from the Computer's loaded installed profile. */
	public record ProfileProof(int listenerCapacity, Set<ProductionCapability> capabilities) {
		public ProfileProof {
			if (listenerCapacity < 0) throw new IllegalArgumentException("negative listener capacity");
			capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
		}
	}
}
