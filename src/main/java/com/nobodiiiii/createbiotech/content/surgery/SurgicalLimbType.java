package com.nobodiiiii.createbiotech.content.surgery;

import org.jetbrains.annotations.Nullable;

/**
 * The anatomical joints a surgical body can carry.
 *
 * <p>Each joint pins one cube group to another and declares how many of its kind one body may
 * own: a body has a single neck, and up to two shoulders and two hips.</p>
 */
public enum SurgicalLimbType {
	NECK("neck", 1),
	SHOULDER("shoulder", 2),
	HIP("hip", 2);

	private final String id;
	private final int maxPerBody;

	SurgicalLimbType(String id, int maxPerBody) {
		this.id = id;
		this.maxPerBody = maxPerBody;
	}

	public String id() {
		return id;
	}

	public int maxPerBody() {
		return maxPerBody;
	}

	@Nullable
	public static SurgicalLimbType byId(String id) {
		for (SurgicalLimbType type : values())
			if (type.id.equals(id))
				return type;
		return null;
	}

	@Nullable
	public static SurgicalLimbType byOrdinal(int ordinal) {
		return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : null;
	}
}
