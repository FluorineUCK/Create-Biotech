package com.nobodiiiii.createbiotech.content.magmabelt.transport;

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.nobodiiiii.createbiotech.content.beltsurface.AbstractItemHandlerBeltSegment;

import net.minecraft.world.item.ItemStack;

public class MagmaItemHandlerBeltSegment extends AbstractItemHandlerBeltSegment {

	private final MagmaBeltInventory beltInventory;
	int offset;

	public MagmaItemHandlerBeltSegment(MagmaBeltInventory beltInventory, int offset) {
		this.beltInventory = beltInventory;
		this.offset = offset;
	}

	@Override
	protected TransportedItemStack getTransportedStack() {
		return beltInventory.getStackAtOffset(offset);
	}

	@Override
	protected boolean canInsert() {
		return beltInventory.canInsertAt(offset);
	}

	@Override
	protected void insertTransportedStack(ItemStack stack) {
		TransportedItemStack transported = new TransportedItemStack(stack);
		transported.insertedAt = offset;
		transported.beltPosition = offset + .5f + (beltInventory.beltMovementPositive ? -1 : 1) / 16f;
		transported.prevBeltPosition = transported.beltPosition;
		beltInventory.addItem(transported);
		beltInventory.belt.setChanged();
		beltInventory.belt.sendData();
	}

	@Override
	protected void onExtracted(TransportedItemStack transported, boolean emptied) {
		if (emptied)
			beltInventory.toRemove.add(transported);
		else
			beltInventory.belt.notifyUpdate();
	}
}
