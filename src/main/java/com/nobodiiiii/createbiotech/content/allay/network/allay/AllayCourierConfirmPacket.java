package com.nobodiiiii.createbiotech.content.allay.network.allay;

import com.nobodiiiii.createbiotech.content.allay.item.allaycourier.AllayCourierItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public class AllayCourierConfirmPacket {
	private static final int MAX_ADDRESS_LENGTH = 25;

	private final InteractionHand hand;
	private final String address;

	public AllayCourierConfirmPacket(InteractionHand hand, String address) {
		this.hand = hand;
		String normalizedAddress = address == null ? "" : address.trim();
		this.address = normalizedAddress.length() > MAX_ADDRESS_LENGTH
			? normalizedAddress.substring(0, MAX_ADDRESS_LENGTH)
			: normalizedAddress;
	}

	public AllayCourierConfirmPacket(FriendlyByteBuf buffer) {
		this(buffer.readEnum(InteractionHand.class), buffer.readUtf(MAX_ADDRESS_LENGTH));
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeEnum(hand);
		buffer.writeUtf(address, MAX_ADDRESS_LENGTH);
	}

	public void handle(ServerPlayer sender) {
		if (sender == null)
			return;
		ItemStack heldStack = sender.getItemInHand(hand);
		if (AllayCourierItem.updateCargoAddress(heldStack, address)) {
			sender.getInventory().setChanged();
			sender.containerMenu.broadcastChanges();
			sender.inventoryMenu.broadcastChanges();
		}
	}
}
