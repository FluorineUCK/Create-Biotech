package com.nobodiiiii.createbiotech.content.allay.item.allaycourier;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.foundation.item.ItemHelper;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

public class AllayCourierMenu extends AbstractContainerMenu {
	private static final int PACKAGE_SLOT_COUNT = 9;
	private static final int PLAYER_SLOT_START = PACKAGE_SLOT_COUNT;
	private static final int SLOT_X = 27;
	private static final int SLOT_Y = 28;

	private boolean loadingContents;
	private final ItemStackHandler packageInventory = new ItemStackHandler(PackageItem.SLOTS) {
		@Override
		protected void onContentsChanged(int slot) {
			super.onContentsChanged(slot);
			if (!loadingContents) {
				persistCurrentState();
			}
		}
	};
	private final int ownerHotbarSlot;
	private final int ownerMenuSlot;
	private ItemStack packageTemplate = ItemStack.EMPTY;

	public final Player player;
	public final Inventory playerInventory;
	public final ItemStack openedStack;
	public final InteractionHand hand;
	public final String initialAddress;

	public AllayCourierMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
		this(CBMenuTypes.ALLAY_COURIER.get(), id, playerInventory, extraData);
	}

	public AllayCourierMenu(MenuType<?> type, int id, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
		super(type, id);
		this.player = playerInventory.player;
		this.playerInventory = playerInventory;
		this.openedStack = ItemStack.STREAM_CODEC.decode(extraData);
		this.hand = extraData.readEnum(InteractionHand.class);
		this.initialAddress = readInitialContents(openedStack);
		this.ownerHotbarSlot = hand == InteractionHand.MAIN_HAND ? playerInventory.selected : -1;
		this.ownerMenuSlot = ownerHotbarSlot >= 0 ? PLAYER_SLOT_START + 27 + ownerHotbarSlot : -1;
		addSlots();
	}

	public AllayCourierMenu(MenuType<?> type, int id, Inventory playerInventory, ItemStack openedStack, InteractionHand hand) {
		super(type, id);
		this.player = playerInventory.player;
		this.playerInventory = playerInventory;
		this.openedStack = openedStack.copy();
		this.hand = hand;
		this.initialAddress = readInitialContents(this.openedStack);
		this.ownerHotbarSlot = hand == InteractionHand.MAIN_HAND ? playerInventory.selected : -1;
		this.ownerMenuSlot = ownerHotbarSlot >= 0 ? PLAYER_SLOT_START + 27 + ownerHotbarSlot : -1;
		addSlots();
	}

	public static AllayCourierMenu create(int id, Inventory playerInventory, ItemStack openedStack, InteractionHand hand) {
		return new AllayCourierMenu(CBMenuTypes.ALLAY_COURIER.get(), id, playerInventory, openedStack, hand);
	}

	private void addSlots() {
		for (int slot = 0; slot < PACKAGE_SLOT_COUNT; slot++) {
			addSlot(new SlotItemHandler(packageInventory, slot, SLOT_X + 20 * slot, SLOT_Y) {
				@Override
				public boolean mayPlace(@NotNull ItemStack stack) {
					return !PackageItem.isPackage(stack) && !stack.is(CBItems.ALLAY_COURIER.get());
				}

				@Override
				public int getMaxStackSize(@NotNull ItemStack stack) {
					return Math.min(stack.getMaxStackSize(), getMaxStackSize());
				}

				@Override
				public void setChanged() {
					super.setChanged();
					persistCurrentState();
				}
			});
		}

		for (int row = 0; row < 3; ++row) {
			for (int col = 0; col < 9; ++col) {
				int slot = col + row * 9 + 9;
				addSlot(createPlayerSlot(slot, 5 + col * 18, 142 + row * 18));
			}
		}
		for (int hotbarSlot = 0; hotbarSlot < 9; ++hotbarSlot) {
			addSlot(createPlayerSlot(hotbarSlot, 5 + hotbarSlot * 18, 200));
		}
	}

	private Slot createPlayerSlot(int index, int x, int y) {
		return new Slot(playerInventory, index, x, y) {
			@Override
			public boolean mayPickup(Player player) {
				return hand != InteractionHand.MAIN_HAND || index != ownerHotbarSlot;
			}

			@Override
			public boolean mayPlace(ItemStack stack) {
				return hand != InteractionHand.MAIN_HAND || index != ownerHotbarSlot;
			}
		};
	}

	private String readInitialContents(ItemStack stack) {
		if (!AllayCourierItem.hasCargo(stack)) {
			return "";
		}

		ItemStack box = AllayCourierItem.copyCargoPackage(stack);
		if (!PackageItem.isPackage(box)) {
			return "";
		}
		packageTemplate = box.copy();

		ItemStackHandler contents = CapturedEntityBoxHelper.getVisiblePackageContents(box);
		loadingContents = true;
		try {
			for (int slot = 0; slot < Math.min(packageInventory.getSlots(), contents.getSlots()); slot++) {
				packageInventory.setStackInSlot(slot, contents.getStackInSlot(slot).copy());
			}
		} finally {
			loadingContents = false;
		}
		return PackageItem.getAddress(box);
	}

	private boolean persistCurrentState() {
		if (player.level().isClientSide) {
			return false;
		}

		ItemStack heldStack = player.getItemInHand(hand);
		if (!heldStack.is(CBItems.ALLAY_COURIER.get()) || heldStack.isEmpty()) {
			return false;
		}

		ItemStack packageBox = createCurrentPackage(AllayCourierItem.copyCargoPackage(heldStack));
		if (packageBox.isEmpty()) {
			AllayCourierItem.clearCargo(heldStack);
		} else {
			packageTemplate = packageBox.copy();
			AllayCourierItem.loadCargo(heldStack, packageBox);
		}
		playerInventory.setChanged();
		return true;
	}

	private ItemStack createCurrentPackage(ItemStack existingPackage) {
		ItemStack packageBox = PackageItem.isPackage(existingPackage)
			? existingPackage
			: packageTemplate.copy();
		boolean hasAnyContents = false;
		for (int slot = 0; slot < PACKAGE_SLOT_COUNT; slot++) {
			if (!packageInventory.getStackInSlot(slot).isEmpty()) {
				hasAnyContents = true;
				break;
			}
		}

		if (!hasAnyContents && !CapturedEntityBoxItem.isBox(packageBox)) {
			return ItemStack.EMPTY;
		}

		if (PackageItem.isPackage(packageBox)) {
			packageBox.set(AllDataComponents.PACKAGE_CONTENTS,
				ItemHelper.containerContentsFromHandler(packageInventory));
			return packageBox;
		}

		return PackageItem.containing(packageInventory);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
	}

	@Override
	public boolean stillValid(Player player) {
		ItemStack heldStack = player.getItemInHand(hand);
		return heldStack.is(CBItems.ALLAY_COURIER.get());
	}

	@Override
	public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
		if (isOwnerInteraction(slotId, dragType, clickType)) {
			return;
		}
		super.clicked(slotId, dragType, clickType, player);
	}

	private boolean isOwnerInteraction(int slotId, int dragType, ClickType clickType) {
		if (ownerMenuSlot < 0) {
			return false;
		}
		if (slotId == ownerMenuSlot) {
			return true;
		}
		return clickType == ClickType.SWAP && dragType == ownerHotbarSlot;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return super.canTakeItemForPickAll(stack, slot) && slot.index != ownerHotbarSlot;
	}

	@Override
	public @NotNull ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();

		if (index < PACKAGE_SLOT_COUNT) {
			if (!moveItemStackTo(stack, PLAYER_SLOT_START, slots.size(), false)) {
				return ItemStack.EMPTY;
			}
		} else {
			if (!moveItemStackTo(stack, 0, PACKAGE_SLOT_COUNT, false)) {
				return ItemStack.EMPTY;
			}
		}

		if (stack.isEmpty()) {
			slot.set(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}

		return copy;
	}

	@OnlyIn(Dist.CLIENT)
	public static AllayCourierMenu createOnClient(int id, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
		return new AllayCourierMenu(CBMenuTypes.ALLAY_COURIER.get(), id, playerInventory, extraData);
	}
}
