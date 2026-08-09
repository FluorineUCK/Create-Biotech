package com.nobodiiiii.createbiotech.content.slimebelt.transport;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlockEntity;
import com.nobodiiiii.createbiotech.content.beltsurface.AbstractItemHandlerBeltSegment;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

public class SlimeItemHandlerBeltSegment extends AbstractItemHandlerBeltSegment {

	private final SlimeBeltInventory beltInventory;
	private final int offset;
	private final Direction side;

	public SlimeItemHandlerBeltSegment(SlimeBeltInventory beltInventory, int offset, Direction side) {
		this.beltInventory = beltInventory;
		this.offset = offset;
		this.side = side;
	}

	@Override
	protected TransportedItemStack getTransportedStack() {
		return beltInventory.getStackAtOffset(offset, side);
	}

	@Override
	protected boolean canInsert() {
		return beltInventory.canInsertAtFromSide(offset, side);
	}

	@Override
	protected void insertTransportedStack(ItemStack stack) {
		TransportedItemStack transported = new TransportedItemStack(stack);
		beltInventory.prepareInsertedItem(transported, offset, side);
		beltInventory.addItem(transported);
		SlimeBeltBlockEntity belt = beltInventory.belt;
		belt.setChanged();
		belt.sendData();
	}

	@Override
	protected void onExtracted(TransportedItemStack transported, boolean emptied) {
		if (emptied)
			beltInventory.toRemove.add(transported);
		else
			beltInventory.belt.notifyUpdate();
	}
}
