package com.nobodiiiii.createbiotech.content.biocomputing.item;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreHost;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryServiceBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryServiceItemData;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import java.util.Objects;


import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Physical Library item, direct placement with an exclusive identity claim. */
public final class LibraryCoreBlockItem extends BlockItem {
	public LibraryCoreBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

    @Override
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    public void initializeClient(java.util.function.Consumer<net.neoforged.neoforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(com.simibubi.create.foundation.item.render.SimpleCustomRenderer.create(this, new LibraryCoreItemRenderer()));
    }

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!safePlacementStack(context.getItemInHand()) || LibraryCoreItemData.readForPlacement(context.getItemInHand()).isEmpty())
			return InteractionResult.FAIL;
		InteractionResult result = super.useOn(context);
		if (result == InteractionResult.FAIL && context.getPlayer() instanceof ServerPlayer player) {
			// The client may already have consumed its predicted item. An unchanged server stack
			// produces no ordinary slot delta, so explicitly correct that prediction on rejection.
			player.inventoryMenu.sendAllDataToRemote();
		}
		return result;
	}

	@Override
	protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
		Level level = context.getLevel();
		ItemStack sourceStack = context.getItemInHand();
		if (!safePlacementStack(sourceStack))
			return false;
		if (level.isClientSide)
			return super.placeBlock(context, state);
		if (level.getServer() == null)
			return false;
		LibraryServiceItemData carried;
		try {
			var raw = sourceStack.get(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get());
			carried = raw == null ? null : LibraryServiceItemData.decode(raw);
			if (carried != null && !LibraryCoreItemData.read(sourceStack)
				.flatMap(LibraryCoreItemData::preservedLibraryId).filter(carried.libraryId()::equals).isPresent())
				return false;
		} catch (IllegalArgumentException invalid) { return false; }
		BlockPos lowerPos = context.getClickedPos().immutable();
		return transferPlacement(sourceStack, new PlacementOps() {
			@Override public boolean placeLower() {
				return LibraryCoreBlockItem.super.placeBlock(context, state);
			}

			@Override public boolean applyPlacementData(LibraryCoreItemData placementData) {
				var host = level.getBlockEntity(lowerPos);
				var service = host instanceof SmartBlockEntity smart ? smart.getBehaviour(LibraryServiceBehaviour.TYPE) : null;
				var control = host instanceof SmartBlockEntity smart ? smart.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour.TYPE) : null;
				var drive = host instanceof SmartBlockEntity smart ? smart.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour.TYPE) : null;
				var serial = host instanceof SmartBlockEntity smart ? smart.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibrarySerialBehaviour.TYPE) : null;
				if (carried != null && service == null) return false;
				if (carried != null && carried.control().isPresent() && control == null) return false;
				if (carried != null && carried.drive().isPresent() && drive == null) return false;
				if (carried != null && carried.serial().isPresent() && serial == null) return false;
				if (!(host instanceof LibraryCoreHost core) || !core.applyPlacementData(placementData)) return false;
				if (carried != null) service.restoreCarriedService(carried);
				if (carried != null && carried.control().isPresent()) {
					control.restore(carried.control().orElseThrow()); host.setChanged();
				}
				if (carried != null && carried.drive().isPresent()) {
					drive.restore(carried.drive().orElseThrow()); host.setChanged();
				}
				if (carried != null && carried.serial().isPresent()) {
					serial.restore(carried.serial().orElseThrow()); host.setChanged();
				}
				return true;
			}

			@Override public void rollbackLower() {
				if (level.getBlockEntity(lowerPos) instanceof LibraryCoreHost core)
					core.rollbackLibraryPlacement();
				else
					level.setBlock(lowerPos, Blocks.AIR.defaultBlockState(),
						Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			}
		});
	}

	static boolean transferPlacement(LibraryCoreItemData data, PlacementOps operations) {
		data = Objects.requireNonNull(data, "item data");
		operations = Objects.requireNonNull(operations, "placement operations");
		if (!operations.placeLower())
			return false;
		if (operations.applyPlacementData(data))
			return true;
		operations.rollbackLower();
		return false;
	}

	static boolean transferPlacement(ItemStack sourceStack, PlacementOps operations) {
		Objects.requireNonNull(sourceStack, "source stack");
		operations = Objects.requireNonNull(operations, "placement operations");
		if (!safePlacementStack(sourceStack))
			return false;
		LibraryCoreItemData data = LibraryCoreItemData.readForPlacement(sourceStack).orElse(null);
		return data != null && transferPlacement(data, operations);
	}

	/** Rejects every vanilla post-place component capable of replaying Block/BE state. */
	static boolean safePlacementStack(ItemStack stack) {
		Objects.requireNonNull(stack, "stack");
		return !stack.has(DataComponents.BLOCK_ENTITY_DATA)
			&& !stack.has(DataComponents.BLOCK_STATE);
	}

	interface PlacementOps {
		boolean placeLower();
		boolean applyPlacementData(LibraryCoreItemData data);
		void rollbackLower();
	}

}
