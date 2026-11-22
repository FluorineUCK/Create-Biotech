package com.nobodiiiii.createbiotech.content.factorycluster.diagnostics;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioLinkPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyFrame;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.NetworkNamesSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Server-thread capture only; never invoked by the tooltip formatter. */
public final class FactoryGoggleCapture {
	private FactoryGoggleCapture() {}

	public static void requireServerThread(BlockEntity be) {
		// Detached construction/serialization contains no live world.
		if (be.getLevel() == null) return;
		if (!(be.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread())
			throw new IllegalStateException("normal Goggles capture requires Minecraft server thread");
	}
	public static String shortId(UUID id) { return id == null ? "—" : id.toString().substring(0, 8); }

	/** Only the existing authoritative directory resolves a name; absence is not a fabricated alias. */
	public static String alias(ServerLevel level, UUID network) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("alias capture off server thread");
		if (network == null) return "@NONE";
		return NetworkNamesSavedData.directory(level.getServer()).flatMap(names -> names.find(network))
				.map(entry -> entry.customAlias().map(value -> "=" + value)
						.orElse("@network:" + entry.ordinal())).orElse("@UNAVAILABLE");
	}

	/** Pure reduction of the production policy and current frame, never a new route search. */
	public static Optional<FactoryGoggleProjection.Radio> radio(RadioEndpoint source,
			Collection<RadioEndpoint> endpoints, RadioTopologyFrame frame, RadioLinkPolicy policy, long now) {
		var sample = frame.find(source.key()).filter(value -> value.loaded()
				&& value.generation() == source.generation() && value.source().equals(source.address())).orElse(null);
		if (sample == null) return Optional.empty();
		RadioEndpoint nearest = null;
		double nearestDistance = Double.POSITIVE_INFINITY;
		var type = RadioLinkPolicy.EdgeResult.UNAVAILABLE;
		// Same member-first stable order as the production relay selection; Cluster breaks exact ties.
		var ordered = endpoints.stream().sorted(Comparator.comparing(RadioEndpoint::memberId)
				.thenComparing(RadioEndpoint::clusterId)).toList();
		for (var peer : ordered) {
			if (peer.key().equals(source.key())) continue;
			var target = frame.find(peer.key()).filter(value -> value.loaded()
					&& value.generation() == peer.generation() && value.source().equals(peer.address())
					&& value.dimension().equals(sample.dimension())).orElse(null);
			if (target == null) continue;
			var edge = policy.edge(source, peer, frame);
			if (!edge.reachable()) continue;
			double distance = frame.dynamic() ? sample.worldCenter().distanceTo(target.worldCenter())
					: Math.sqrt(sample.source().localPos().distSqr(target.source().localPos()));
			if (distance < nearestDistance) {
				nearest = peer;
				nearestDistance = distance;
				type = edge;
			}
		}
		var point = sample.worldCenter();
		return Optional.of(new FactoryGoggleProjection.Radio(
				String.format(Locale.ROOT, "%.1f, %.1f, %.1f", point.x, point.y, point.z), frame.dynamic(),
				Math.max(0, now - frame.sampleTick()), nearest == null ? "@UNAVAILABLE"
						: String.format(Locale.ROOT, "%.1f", nearestDistance), type.name()));
	}
}
