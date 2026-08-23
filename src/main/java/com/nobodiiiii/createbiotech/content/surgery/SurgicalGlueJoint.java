package com.nobodiiiii.createbiotech.content.surgery;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** A persistent glue connection between two source-model cubes on a surgical table. */
public record SurgicalGlueJoint(Endpoint first, Endpoint second) {
	private static final String FIRST_SUBJECT_TAG = "FirstSubject";
	private static final String FIRST_CUBE_TAG = "FirstCube";
	private static final String SECOND_SUBJECT_TAG = "SecondSubject";
	private static final String SECOND_CUBE_TAG = "SecondCube";

	public SurgicalGlueJoint {
		if (first == null || second == null)
			throw new IllegalArgumentException("A surgical glue joint requires two endpoints");
	}

	public static SurgicalGlueJoint of(Endpoint first, Endpoint second) {
		return compare(first, second) <= 0 ? new SurgicalGlueJoint(first, second)
			: new SurgicalGlueJoint(second, first);
	}

	public boolean touches(UUID subjectKey, int cubeId) {
		return first.matches(subjectKey, cubeId) || second.matches(subjectKey, cubeId);
	}

	public boolean touches(UUID subjectKey) {
		return first.subjectKey.equals(subjectKey) || second.subjectKey.equals(subjectKey);
	}

	@Nullable
	public Endpoint other(Endpoint endpoint) {
		if (first.equals(endpoint))
			return second;
		if (second.equals(endpoint))
			return first;
		return null;
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID(FIRST_SUBJECT_TAG, first.subjectKey);
		tag.putInt(FIRST_CUBE_TAG, first.cubeId);
		tag.putUUID(SECOND_SUBJECT_TAG, second.subjectKey);
		tag.putInt(SECOND_CUBE_TAG, second.cubeId);
		return tag;
	}

	@Nullable
	static SurgicalGlueJoint load(CompoundTag tag) {
		if (!tag.hasUUID(FIRST_SUBJECT_TAG) || !tag.hasUUID(SECOND_SUBJECT_TAG)
			|| !tag.contains(FIRST_CUBE_TAG, Tag.TAG_ANY_NUMERIC)
			|| !tag.contains(SECOND_CUBE_TAG, Tag.TAG_ANY_NUMERIC))
			return null;
		Endpoint first = new Endpoint(tag.getUUID(FIRST_SUBJECT_TAG), tag.getInt(FIRST_CUBE_TAG));
		Endpoint second = new Endpoint(tag.getUUID(SECOND_SUBJECT_TAG), tag.getInt(SECOND_CUBE_TAG));
		return first.equals(second) ? null : of(first, second);
	}

	private static int compare(Endpoint first, Endpoint second) {
		int most = Long.compareUnsigned(first.subjectKey.getMostSignificantBits(),
			second.subjectKey.getMostSignificantBits());
		if (most != 0)
			return most;
		int least = Long.compareUnsigned(first.subjectKey.getLeastSignificantBits(),
			second.subjectKey.getLeastSignificantBits());
		return least != 0 ? least : Integer.compare(first.cubeId, second.cubeId);
	}

	public record Endpoint(UUID subjectKey, int cubeId) {
		public Endpoint {
			if (subjectKey == null || cubeId < 0)
				throw new IllegalArgumentException("Invalid surgical glue endpoint");
		}

		public boolean matches(UUID expectedSubject, int expectedCube) {
			return subjectKey.equals(expectedSubject) && cubeId == expectedCube;
		}
	}
}
