package com.nobodiiiii.createbiotech.content.fixedcarrotfishingrod;

import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.foundation.advancement.PlacedByPlayerAdvancementTracker;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.ItemStackHandler;

public class FixedCarrotFishingRodBlockEntity extends BlockEntity implements Clearable {

	@Nullable
	private UUID advancementOwner;

	private final ItemStackHandler inventory = new ItemStackHandler(1) {
		@Override
		protected void onContentsChanged(int slot) {
			setChanged();
			if (level != null) {
				level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
			}
		}

		@Override
		public int getSlotLimit(int slot) {
			return 1;
		}
	};

	public FixedCarrotFishingRodBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.FIXED_CARROT_FISHING_ROD.get(), pos, state);
	}

	public ItemStack getBaitItem() {
		return inventory.getStackInSlot(0);
	}

	public void setBaitItem(ItemStack stack) {
		inventory.setStackInSlot(0, stack);
	}

	public ItemStackHandler getInventory() {
		return inventory;
	}

	public void setAdvancementOwner(@Nullable LivingEntity placer) {
		advancementOwner = PlacedByPlayerAdvancementTracker.ownerFrom(placer);
		setChanged();
	}

	@Nullable
	public UUID getAdvancementOwner() {
		return advancementOwner;
	}

	public ItemStackHandler getItemCapability(@Nullable Direction side) {
		return inventory;
	}

	public AABB getRenderBoundingBox() {
		return new AABB(worldPosition).expandTowards(0, -1, 0);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.put("Inventory", inventory.serializeNBT(registries));
		PlacedByPlayerAdvancementTracker.writeOwner(tag, advancementOwner);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
		advancementOwner = PlacedByPlayerAdvancementTracker.readOwner(tag);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		CompoundTag tag = super.getUpdateTag(registries);
		tag.put("Inventory", inventory.serializeNBT(registries));
		PlacedByPlayerAdvancementTracker.writeOwner(tag, advancementOwner);
		return tag;
	}

	@Override
	public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
		super.handleUpdateTag(tag, registries);
		inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
		advancementOwner = PlacedByPlayerAdvancementTracker.readOwner(tag);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
    public void clearContent() {
        setBaitItem(ItemStack.EMPTY);
    }
}
