package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.Optional;
import java.util.UUID;

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
		Optional<Selection> decoded = decode(player.getPersistentData());
		if (decoded.isEmpty())
			return Optional.empty();

		Selection selection = decoded.get();
		if (player.serverLevel().getGameTime() >= selection.expires())
			return invalid(player);

		SpaceAddress savedAddress = selection.address();
		BlockEntity blockEntity = savedAddress.resolveBlockEntity(player.getServer());
		if (!(blockEntity instanceof ClusterMember member)
			|| !member.memberId().equals(selection.memberId())
			|| !member.memberAddress().equals(savedAddress))
			return invalid(player);
		return Optional.of(member);
	}

	static Optional<Selection> decode(CompoundTag persistentData) {
		if (!persistentData.contains(KEY))
			return Optional.empty();
		if (!persistentData.contains(KEY, Tag.TAG_COMPOUND))
			return invalid(persistentData);

		CompoundTag tag = persistentData.getCompound(KEY);
		if (!tag.hasUUID(MEMBER_ID)
			|| !tag.contains(EXPIRES, Tag.TAG_LONG)
			|| !tag.contains("Dimension", Tag.TAG_STRING)
			|| tag.getString("Dimension").isBlank()
			|| !tag.contains("Pos", Tag.TAG_LONG)
			|| (tag.contains("SubLevel") && !tag.hasUUID("SubLevel")))
			return invalid(persistentData);

		try {
			return Optional.of(new Selection(SpaceAddress.load(tag),
				tag.getUUID(MEMBER_ID), tag.getLong(EXPIRES)));
		} catch (RuntimeException invalidAddress) {
			return invalid(persistentData);
		}
	}

	public static void clear(ServerPlayer player) {
		player.getPersistentData().remove(KEY);
	}

	private static Optional<ClusterMember> invalid(ServerPlayer player) {
		clear(player);
		return Optional.empty();
	}

	private static Optional<Selection> invalid(CompoundTag persistentData) {
		persistentData.remove(KEY);
		return Optional.empty();
	}

	record Selection(SpaceAddress address, UUID memberId, long expires) {}
}
