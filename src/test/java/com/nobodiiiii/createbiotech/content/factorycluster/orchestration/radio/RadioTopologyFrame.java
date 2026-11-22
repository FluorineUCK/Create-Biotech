package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One complete, immutable radio-topology sample published by the server thread.
 *
 * <p>The frame deliberately contains values only. It never retains a {@code Level}, Sable
 * structure, block entity, or another mutable world object, so it is safe to publish to workers.</p>
 */
public record RadioTopologyFrame(long sampleTick, boolean dynamic,
	Map<RadioEndpoint.Key, Sample> samples) {
	public RadioTopologyFrame {
		LinkedHashMap<RadioEndpoint.Key, Sample> copied = new LinkedHashMap<>();
		for (Map.Entry<RadioEndpoint.Key, Sample> entry : Objects.requireNonNull(samples,
			"samples").entrySet()) {
			RadioEndpoint.Key key = Objects.requireNonNull(entry.getKey(), "sample key");
			Sample sample = Objects.requireNonNull(entry.getValue(), "sample");
			copied.put(key, sample);
		}
		samples = Map.copyOf(copied);
	}

	public Optional<Sample> find(RadioEndpoint.Key key) {
		return Optional.ofNullable(samples.get(Objects.requireNonNull(key, "key")));
	}

	public Sample sample(RadioEndpoint.Key key) {
		return find(key).orElseThrow(() -> new NoSuchElementException("missing radio topology sample"));
	}

	/** One endpoint's frozen availability and position within the complete frame. */
	public record Sample(ResourceKey<Level> dimension, SpaceAddress source, Vec3 worldCenter,
		boolean loaded, long generation, Optional<UUID> physicalStructureId) {
		public Sample {
			dimension = Objects.requireNonNull(dimension, "dimension");
			source = Objects.requireNonNull(source, "source");
			worldCenter = Objects.requireNonNull(worldCenter, "worldCenter");
			if (generation < 0) throw new IllegalArgumentException("negative endpoint generation");
			physicalStructureId = Objects.requireNonNull(physicalStructureId,
				"physicalStructureId");
			if (!dimension.equals(source.dimension()))
				throw new IllegalArgumentException("topology sample dimension differs from source");
			if (!physicalStructureId.equals(Optional.ofNullable(source.subLevelId())))
				throw new IllegalArgumentException("topology sample physical structure differs from source");
			worldCenter = new Vec3(worldCenter.x, worldCenter.y, worldCenter.z);
		}

		public Sample withLoaded(boolean nextLoaded) {
			return new Sample(dimension, source, worldCenter, nextLoaded, generation,
				physicalStructureId);
		}
	}
}
