package com.nobodiiiii.createbiotech.content.biopackager;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.foundation.block.CBWrenchHelper;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.block.WrenchableDirectionalBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

public class BioPackagerBlock extends WrenchableDirectionalBlock implements IBE<BioPackagerBlockEntity>, IWrenchable {

	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	public BioPackagerBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(POWERED, false));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction preferredFacing = null;
		for (Direction face : context.getNearestLookingDirections()) {
			BlockPos relative = context.getClickedPos().relative(face);
			BlockEntity be = context.getLevel()
				.getBlockEntity(relative);
			if (be instanceof BioPackagerBlockEntity)
				continue;
			if (be != null && context.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
				relative, face.getOpposite()) != null) {
				preferredFacing = face.getOpposite();
				break;
			}
		}

		Player player = context.getPlayer();
		if (preferredFacing == null) {
			Direction facing = context.getNearestLookingDirection();
			preferredFacing = player != null && player.isShiftKeyDown() ? facing : facing.getOpposite();
		}

		return super.getStateForPlacement(context)
			.setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()))
			.setValue(FACING, preferredFacing);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack itemInHand, BlockState state, Level worldIn, BlockPos pos,
		Player player, InteractionHand handIn, BlockHitResult hit) {
		InteractionResult result = interact(state, worldIn, pos, player, handIn, itemInHand);
		return result.consumesAction()
			? ItemInteractionResult.sidedSuccess(worldIn.isClientSide)
			: ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level worldIn, BlockPos pos, Player player,
		BlockHitResult hit) {
		return interact(state, worldIn, pos, player, InteractionHand.MAIN_HAND, ItemStack.EMPTY);
	}

	private InteractionResult interact(BlockState state, Level worldIn, BlockPos pos, Player player,
		InteractionHand handIn, ItemStack itemInHand) {
		if (player == null)
			return InteractionResult.PASS;

		if (CBWrenchHelper.isWrench(itemInHand))
			return InteractionResult.PASS;

		return onBlockEntityUse(worldIn, pos, be -> {
			if (be.heldBox.isEmpty()) {
				if (be.animationTicks > 0)
					return InteractionResult.SUCCESS;
				if (itemInHand.getItem() instanceof CapturedEntityBoxItem
					&& CapturedEntityBoxItem.hasCapturedEntity(itemInHand)) {
					if (worldIn.isClientSide())
						return InteractionResult.SUCCESS;
					ItemStack toInsert = itemInHand.copy();
					toInsert.setCount(1);
					if (!be.startUnpacking(toInsert))
						return InteractionResult.SUCCESS;
					itemInHand.shrink(1);
					worldIn.playSound(null, pos, SoundEvents.UI_TOAST_IN, SoundSource.BLOCKS, 0.5f, 1f);
					if (itemInHand.isEmpty())
						player.setItemInHand(handIn, ItemStack.EMPTY);
					return InteractionResult.SUCCESS;
				}
				return InteractionResult.PASS;
			}
			if (be.animationTicks > 0)
				return InteractionResult.SUCCESS;
			if (!worldIn.isClientSide()) {
				player.getInventory().placeItemBackInInventory(be.heldBox.copy());
				worldIn.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.2f, 1f);
				be.heldBox = ItemStack.EMPTY;
				be.notifyUpdate();
			}
			return InteractionResult.SUCCESS;
		}).consumesAction() ? InteractionResult.SUCCESS : InteractionResult.PASS;
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(POWERED));
	}

	@Override
	public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
		super.onNeighborChange(state, level, pos, neighbor);
		if (neighbor.relative(state.getOptionalValue(FACING).orElse(Direction.UP)).equals(pos))
			withBlockEntityDo(level, pos, BioPackagerBlockEntity::triggerStockCheck);
	}

	@Override
	public void neighborChanged(BlockState state, Level worldIn, BlockPos pos, Block blockIn, BlockPos fromPos,
		boolean isMoving) {
		if (worldIn.isClientSide)
			return;

		InvManipulationBehaviour behaviour = BlockEntityBehaviour.get(worldIn, pos, InvManipulationBehaviour.TYPE);
		if (behaviour != null)
			behaviour.onNeighborChanged(fromPos);

		boolean powered = worldIn.hasNeighborSignal(pos);
		boolean previouslyPowered = state.getValue(POWERED);
		if (previouslyPowered == powered)
			return;
		worldIn.setBlock(pos, state.setValue(POWERED, powered), 2);
		withBlockEntityDo(worldIn, pos, be -> be.onRedstoneUpdate(powered));
	}

	@Override
	public void onRemove(BlockState pState, Level pLevel, BlockPos pPos, BlockState pNewState, boolean pIsMoving) {
		IBE.onRemove(pState, pLevel, pPos, pNewState);
	}

	@Override
	public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side) {
		return false;
	}

	@Override
	public Class<BioPackagerBlockEntity> getBlockEntityClass() {
		return BioPackagerBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends BioPackagerBlockEntity> getBlockEntityType() {
		return CBBlockEntityTypes.BIO_PACKAGER.get();
	}

	@Override
	protected boolean isPathfindable(BlockState pState, PathComputationType pType) {
		return false;
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState pState) {
		return true;
	}

	@Override
	public int getAnalogOutputSignal(BlockState pState, Level pLevel, BlockPos pPos) {
		return getBlockEntityOptional(pLevel, pPos).map(pbe -> {
				boolean empty = pbe.heldBox.isEmpty();
				if (pbe.animationTicks != 0)
					empty = false;
				return empty ? 0 : 15;
			}).orElse(0);
	}
}
