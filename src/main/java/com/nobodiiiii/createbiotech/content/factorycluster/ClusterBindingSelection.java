package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.Optional;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ClusterBindingSelection {
	private static final String KEY = "create_biotech:factory_cluster_binding";
	private static final String MEMBER_ID = "MemberId";
	private static final String EXPIRES = "Expires";
	private static final long SELECTION_DURATION = 200;

	private ClusterBindingSelection() {}

	public static void begin(ServerPlayer player, ClusterMember source) {
		CompoundTag tag = source.memberAddress().save();
		tag.putUUID(MEMBER_ID, source.memberId());
		tag.putLong(EXPIRES,
			player.serverLevel().getGameTime() + SELECTION_DURATION);
		player.getPersistentData().put(KEY, tag);
	}

	public static Optional<ClusterMember> resolve(ServerPlayer player) {
		CompoundTag persistentData = player.getPersistentData();
		if (!persistentData.contains(KEY, Tag.TAG_COMPOUND))
			return Optional.empty();

		CompoundTag tag = persistentData.getCompound(KEY);
		if (!tag.hasUUID(MEMBER_ID)
			|| !tag.contains(EXPIRES, Tag.TAG_LONG)
			|| player.serverLevel().getGameTime() >= tag.getLong(EXPIRES))
			return invalid(player);

		try {
			SpaceAddress savedAddress = SpaceAddress.load(tag);
			BlockEntity blockEntity = savedAddress.resolveBlockEntity(player.getServer());
			if (!(blockEntity instanceof ClusterMember member)
				|| !member.memberId().equals(tag.getUUID(MEMBER_ID))
				|| !member.memberAddress().equals(savedAddress))
				return invalid(player);
			return Optional.of(member);
		} catch (RuntimeException invalidAddress) {
			return invalid(player);
		}
	}

	public static void clear(ServerPlayer player) {
		player.getPersistentData().remove(KEY);
	}

	private static Optional<ClusterMember> invalid(ServerPlayer player) {
		clear(player);
		return Optional.empty();
	}
}
