package com.yision.allay.block.allayport;

import com.simibubi.create.content.logistics.packagePort.PackagePortMenu;
import com.simibubi.create.foundation.item.SmartInventory;
import com.yision.allay.registry.AllMenuTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

public class AllayPortMenu extends PackagePortMenu {
	private static final int UI_X_OFFSET = 8;

	public AllayPortMenu(int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
		this(AllMenuTypes.ALLAY_PORT.get(), id, inv, extraData);
	}

	public AllayPortMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
		super(type, id, inv, extraData);
	}

	public AllayPortMenu(MenuType<?> type, int id, Inventory inv, AllayPortBlockEntity blockEntity) {
		super(type, id, inv, blockEntity);
	}

	@Override
	protected AllayPortBlockEntity createOnClient(RegistryFriendlyByteBuf extraData) {
		BlockPos readBlockPos = extraData.readBlockPos();
		ClientLevel world = Minecraft.getInstance().level;
		BlockEntity blockEntity = world != null ? world.getBlockEntity(readBlockPos) : null;
		if (blockEntity instanceof AllayPortBlockEntity allayPortBlockEntity) {
			return allayPortBlockEntity;
		}
		return null;
	}

	public static AllayPortMenu create(int id, Inventory inv, AllayPortBlockEntity blockEntity) {
		return new AllayPortMenu(AllMenuTypes.ALLAY_PORT.get(), id, inv, blockEntity);
	}

	@Override
	protected void addSlots() {
		SmartInventory inventory = contentHolder.inventory;
		int packageSlotsX = 35;
		int packageSlotsY = 9;

		for (int row = 0; row < 2; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new SlotItemHandler(inventory, row * 9 + col, packageSlotsX + col * 18,
					packageSlotsY + row * 18));
			}
		}

		addPlayerSlots(38 + UI_X_OFFSET, 108);

		if (contentHolder instanceof AllayPortBlockEntity allayPortBlockEntity) {
			addSlot(new SlotItemHandler(allayPortBlockEntity.getCarrierInventory(), 0, 22, 59) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return AllayPortInventory.isEmptyCarrier(stack);
				}
			});
		}
	}

	@Override
	public @NotNull ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) {
			return super.quickMoveStack(player, index);
		}

		ItemStack stack = slot.getItem();
		int carrierSlotIndex = slots.size() - 1;

		if (index == carrierSlotIndex) {
			int originalCount = stack.getCount();
			if (moveItemStackTo(stack, 18, carrierSlotIndex, false)) {
				int moved = originalCount - stack.getCount();
				if (stack.isEmpty()) {
					slot.setByPlayer(ItemStack.EMPTY);
				} else {
					slot.setByPlayer(stack.copy());
				}
				ItemStack result = stack.copy();
				result.setCount(moved);
				return result;
			}
		} else if (AllayPortInventory.isEmptyCarrier(stack)) {
			Slot carrierSlot = slots.get(carrierSlotIndex);
			ItemStack targetStack = carrierSlot.getItem();

			int maxStackSize = stack.getMaxStackSize();
			int space = maxStackSize - (targetStack.isEmpty() ? 0 : targetStack.getCount());

			if (space > 0) {
				int toMove = Math.min(space, stack.getCount());
				if (targetStack.isEmpty()) {
					ItemStack moved = stack.copy();
					moved.setCount(toMove);
					carrierSlot.setByPlayer(moved);
				} else {
					targetStack.grow(toMove);
					carrierSlot.setByPlayer(targetStack.copy());
				}
				stack.shrink(toMove);
				if (stack.isEmpty()) {
					slot.setByPlayer(ItemStack.EMPTY);
				} else {
					slot.setByPlayer(stack.copy());
				}
				ItemStack result = stack.copy();
				result.setCount(toMove);
				return result;
			}
			return ItemStack.EMPTY;
		}

		return super.quickMoveStack(player, index);
	}

}
