package com.nobodiiiii.createbiotech.content.surgery;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * A persistent anatomical joint between two surgical cubes.
 *
 * <p>Unlike {@link SurgicalGlueJoint} the two endpoints are not interchangeable: click order keeps
 * the selected child part rotating around the selected parent part, while each stored endpoint is
 * the real cube at their shared physical connection.</p>
 */
public record SurgicalLimbJoint(SurgicalLimbType type, SurgicalGlueJoint.Endpoint child,
	SurgicalGlueJoint.Endpoint parent) {
	private static final String TYPE_TAG = "Type";
	private static final String CHILD_SUBJECT_TAG = "ChildSubject";
	private static final String CHILD_CUBE_TAG = "ChildCube";
	private static final String PARENT_SUBJECT_TAG = "ParentSubject";
	private static final String PARENT_CUBE_TAG = "ParentCube";

	public SurgicalLimbJoint {
		if (type == null || child == null || parent == null || child.equals(parent))
			throw new IllegalArgumentException("A surgical limb joint requires two distinct endpoints");
	}

	public boolean touches(UUID subjectKey, int cubeId) {
		return child.matches(subjectKey, cubeId) || parent.matches(subjectKey, cubeId);
	}

	public boolean touches(UUID subjectKey) {
		return child.subjectKey().equals(subjectKey) || parent.subjectKey().equals(subjectKey);
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString(TYPE_TAG, type.id());
		tag.putUUID(CHILD_SUBJECT_TAG, child.subjectKey());
		tag.putInt(CHILD_CUBE_TAG, child.cubeId());
		tag.putUUID(PARENT_SUBJECT_TAG, parent.subjectKey());
		tag.putInt(PARENT_CUBE_TAG, parent.cubeId());
		return tag;
	}

	@Nullable
	static SurgicalLimbJoint load(CompoundTag tag) {
		if (!tag.contains(TYPE_TAG, Tag.TAG_STRING) || !tag.hasUUID(CHILD_SUBJECT_TAG)
			|| !tag.hasUUID(PARENT_SUBJECT_TAG) || !tag.contains(CHILD_CUBE_TAG, Tag.TAG_ANY_NUMERIC)
			|| !tag.contains(PARENT_CUBE_TAG, Tag.TAG_ANY_NUMERIC))
			return null;
		SurgicalLimbType type = SurgicalLimbType.byId(tag.getString(TYPE_TAG));
		int childCube = tag.getInt(CHILD_CUBE_TAG);
		int parentCube = tag.getInt(PARENT_CUBE_TAG);
		if (type == null || childCube < 0 || parentCube < 0)
			return null;
		SurgicalGlueJoint.Endpoint child = new SurgicalGlueJoint.Endpoint(tag.getUUID(CHILD_SUBJECT_TAG),
			childCube);
		SurgicalGlueJoint.Endpoint parent = new SurgicalGlueJoint.Endpoint(tag.getUUID(PARENT_SUBJECT_TAG),
			parentCube);
		return child.equals(parent) ? null : new SurgicalLimbJoint(type, child, parent);
	}
}
