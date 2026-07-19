package com.nobodiiiii.createbiotech.content.biopackager;

import com.nobodiiiii.createbiotech.client.BioPackagerContraptionClientAnimationHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

public class BioPackagerContraptionAnimationPacket {

	private final int entityId;
	private final BlockPos localPos;
	private final ItemStack heldBox;
	private final ItemStack previouslyUnwrapped;
	private final boolean animationInward;

	public BioPackagerContraptionAnimationPacket(int entityId, BlockPos localPos, ItemStack heldBox,
		ItemStack previouslyUnwrapped, boolean animationInward) {
		this.entityId = entityId;
		this.localPos = localPos.immutable();
		this.heldBox = heldBox.copy();
		this.previouslyUnwrapped = previouslyUnwrapped.copy();
		this.animationInward = animationInward;
	}

	public BioPackagerContraptionAnimationPacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt(), buffer.readBlockPos(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
			ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer), buffer.readBoolean());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(entityId);
		buffer.writeBlockPos(localPos);
		ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, heldBox);
		ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, previouslyUnwrapped);
		buffer.writeBoolean(animationInward);
	}

	public void handle(LocalPlayer player) {
		BioPackagerContraptionClientAnimationHandler.startAnimation(entityId, localPos, heldBox,
			previouslyUnwrapped, animationInward);
	}
}
