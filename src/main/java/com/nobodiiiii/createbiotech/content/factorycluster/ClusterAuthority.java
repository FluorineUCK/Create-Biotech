package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public record ClusterAuthority(ClusterMemberType type, UUID memberId) {
	private static final String TYPE_KEY = "Type";
	private static final String MEMBER_ID_KEY = "MemberId";

	public ClusterAuthority {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(memberId, "memberId");
	}

	public boolean identifies(ClusterMember member) {
		return type == member.memberType() && memberId.equals(member.memberId());
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString(TYPE_KEY, type.name());
		tag.putUUID(MEMBER_ID_KEY, memberId);
		return tag;
	}

	public static Optional<ClusterAuthority> tryLoad(CompoundTag tag) {
		if (!tag.contains(TYPE_KEY, Tag.TAG_STRING) || !tag.hasUUID(MEMBER_ID_KEY))
			return Optional.empty();
		try {
			return Optional.of(new ClusterAuthority(
				ClusterMemberType.valueOf(tag.getString(TYPE_KEY)),
				tag.getUUID(MEMBER_ID_KEY)));
		} catch (IllegalArgumentException invalidType) {
			return Optional.empty();
		}
	}
}
