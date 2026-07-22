package com.nobodiiiii.createbiotech.content.giantfrog;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;

public class GiantFrogEatPacket {
	private final BlockPos pos;

	public GiantFrogEatPacket(BlockPos pos) {
		this.pos = pos.immutable();
	}

	public GiantFrogEatPacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readBlockPos());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
	}

	public void handle(LocalPlayer player) {
		if (player == null || player.level() == null)
			return;

		BlockEntity blockEntity = player.level()
			.getBlockEntity(pos);
		if (blockEntity instanceof GiantFrogBlockEntity giantFrog)
			giantFrog.startEatAnimation();
	}
}
