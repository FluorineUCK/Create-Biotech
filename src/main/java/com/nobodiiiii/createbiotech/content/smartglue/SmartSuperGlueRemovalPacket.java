package com.nobodiiiii.createbiotech.content.smartglue;

import java.util.Set;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public class SmartSuperGlueRemovalPacket {

	private final int entityId;
	private final BlockPos soundSource;
	private final boolean removeConnected;

	public SmartSuperGlueRemovalPacket(int entityId, BlockPos soundSource, boolean removeConnected) {
		this.entityId = entityId;
		this.soundSource = soundSource;
		this.removeConnected = removeConnected;
	}

	public SmartSuperGlueRemovalPacket(FriendlyByteBuf buffer) {
		this(buffer.readInt(), buffer.readBlockPos(), buffer.readBoolean());
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeInt(entityId);
		buffer.writeBlockPos(soundSource);
		buffer.writeBoolean(removeConnected);
	}

	public void handle(ServerPlayer player) {
		apply(player);
	}

	private void apply(ServerPlayer player) {
		if (player == null)
			return;

		Entity entity = player.level().getEntity(entityId);
		if (!(entity instanceof SuperGlueEntity superGlue) || !SmartSuperGlueHelper.isSmartGlueCompatible(superGlue))
			return;

		double range = 32;
		if (player.distanceToSqr(superGlue.position()) > range * range)
			return;

		Set<SuperGlueEntity> glueToRemove = removeConnected
			? SmartSuperGlueHelper.findConnectedGlueEntities(player.level(), superGlue)
			: Set.of(superGlue);
		if (glueToRemove.isEmpty())
			return;

		AllSoundEvents.SLIME_ADDED.play(player.level(), null, soundSource, 0.5F, 0.5F);
		for (SuperGlueEntity glueEntity : glueToRemove) {
			glueEntity.spawnParticles();
			glueEntity.discard();
		}
	}
}
