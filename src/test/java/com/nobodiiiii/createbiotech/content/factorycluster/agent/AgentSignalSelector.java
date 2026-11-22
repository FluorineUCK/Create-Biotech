package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Exact bounded external redstone fact selected by a player for SIGNAL completion. */
public record AgentSignalSelector(SpaceAddress source, Direction side, int minimumStrength) {
	private static final Set<String> KEYS = Set.of("source", "side", "minimumStrength");

	public AgentSignalSelector {
		source = Objects.requireNonNull(source, "source");
		side = Objects.requireNonNull(side, "side");
		if (minimumStrength < 1 || minimumStrength > 15)
			throw new IllegalArgumentException("signal strength must be between 1 and 15");
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.put("source", source.save());
		tag.putString("side", side.getName());
		tag.putInt("minimumStrength", minimumStrength);
		return tag;
	}

	public static Optional<AgentSignalSelector> load(CompoundTag tag) {
		if (tag == null || !tag.getAllKeys().equals(KEYS)
			|| !tag.contains("source", Tag.TAG_COMPOUND)
			|| !tag.contains("side", Tag.TAG_STRING)
			|| !tag.contains("minimumStrength", Tag.TAG_INT)) return Optional.empty();
		CompoundTag sourceTag = tag.getCompound("source");
		Set<String> sourceKeys = sourceTag.hasUUID("SubLevel")
			? Set.of("Dimension", "SubLevel", "Pos") : Set.of("Dimension", "Pos");
		if (!sourceTag.getAllKeys().equals(sourceKeys)) return Optional.empty();
		Direction side = Direction.byName(tag.getString("side"));
		if (side == null) return Optional.empty();
		try {
			return SpaceAddress.tryLoad(sourceTag).map(source -> new AgentSignalSelector(source,
				side, tag.getInt("minimumStrength")));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}
}
