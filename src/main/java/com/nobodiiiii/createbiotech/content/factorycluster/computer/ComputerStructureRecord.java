package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;

public record ComputerStructureRecord(UUID computerStructureMemberId, long revision,
	UUID coordinatorId, ComputerStructureSnapshot snapshot) {
	private static final int VERSION = 1;
	private static final Set<String> KEYS = Set.of("Version", "StructureMemberId", "Revision",
		"CoordinatorId", "Snapshot");

	public ComputerStructureRecord {
		Objects.requireNonNull(computerStructureMemberId, "computerStructureMemberId");
		Objects.requireNonNull(coordinatorId, "coordinatorId");
		Objects.requireNonNull(snapshot, "snapshot");
		if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		tag.putUUID("StructureMemberId", computerStructureMemberId);
		tag.putLong("Revision", revision);
		tag.putUUID("CoordinatorId", coordinatorId);
		tag.put("Snapshot", snapshot.save());
		return tag;
	}

	public static Optional<ComputerStructureRecord> load(CompoundTag tag) {
		if (tag == null || !tag.getAllKeys().equals(KEYS)
			|| !has(tag, "Version", Tag.TAG_INT) || tag.getInt("Version") != VERSION
			|| !uuid(tag, "StructureMemberId") || !has(tag, "Revision", Tag.TAG_LONG)
			|| tag.getLong("Revision") < 0 || !uuid(tag, "CoordinatorId")
			|| !has(tag, "Snapshot", Tag.TAG_COMPOUND)) return Optional.empty();
		return ComputerStructureSnapshot.load(tag.getCompound("Snapshot")).map(snapshot ->
			new ComputerStructureRecord(tag.getUUID("StructureMemberId"), tag.getLong("Revision"),
				tag.getUUID("CoordinatorId"), snapshot));
	}

	private static boolean uuid(CompoundTag tag, String key) {
		return tag.get(key) instanceof IntArrayTag values && values.getAsIntArray().length == 4
			&& tag.hasUUID(key);
	}

	private static boolean has(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}
}
