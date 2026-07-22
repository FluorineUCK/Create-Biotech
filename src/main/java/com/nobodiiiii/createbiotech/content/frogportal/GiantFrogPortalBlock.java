package com.nobodiiiii.createbiotech.content.frogportal;

import java.util.List;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Giant Frog Portal (巨型青蛙传送门). A standalone (non-kinetic) block that teleports entities
 * lingering inside it into a private room in the Frog Stomach dimension. The bound room's space
 * index is kept on the block entity and travels with the item
 * ({@link CBDataComponents#FROG_STOMACH_SPACE}) so breaking and re-placing rebinds to the same room;
 * the item is non-stackable, one per unique room.
 */
public class GiantFrogPortalBlock extends Block implements EntityBlock, Portal {

	public GiantFrogPortalBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FrogPortalBehaviour.AXIS, Direction.Axis.X));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new GiantFrogPortalBlockEntity(pos, state);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return FrogPortalBehaviour.getShape(state, level, pos, context);
	}

	@Nullable
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return FrogPortalBehaviour.getStateForPlacement(defaultBlockState(), context);
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
		FrogPortalBehaviour.entityInside(this, state, level, pos, entity);
	}

	@Override
	public int getPortalTransitionTime(ServerLevel level, Entity entity) {
		return FrogPortalBehaviour.getPortalTransitionTime(level, entity);
	}

	@Nullable
	@Override
	public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof GiantFrogPortalBlockEntity portal))
			return null;
		ServerLevel frogLevel = level.getServer().getLevel(FrogStomachDimensions.FROG_STOMACH);
		if (frogLevel == null)
			return null;

		long spaceIndex = portal.ensureRoom(level.getServer(), frogLevel);
		FrogStomachSavedData.get(level.getServer()).setReturn(entity.getUUID(), spaceIndex, level.dimension(), pos);
		return FrogPortalBehaviour.transitionTo(frogLevel, entity,
			Vec3.atBottomCenterOf(FrogStomachSpace.spawnPos(spaceIndex)));
	}

	@Override
	public Transition getLocalTransition() {
		return FrogPortalBehaviour.getLocalTransition();
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		FrogPortalBehaviour.animateTick(state, level, pos, random);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
		ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide)
			return;
		Long index = stack.get(CBDataComponents.FROG_STOMACH_SPACE.get());
		if (index != null && level.getBlockEntity(pos) instanceof GiantFrogPortalBlockEntity portal)
			portal.setSpaceIndex(index);
	}

	@Override
	public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		ItemStack stack = new ItemStack(CBItems.GIANT_FROG_PORTAL.get());
		if (builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof GiantFrogPortalBlockEntity portal
			&& portal.hasSpace())
			stack.set(CBDataComponents.FROG_STOMACH_SPACE.get(), portal.getSpaceIndex());
		return List.of(stack);
	}

	@Override
	public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos,
		Player player) {
		ItemStack stack = new ItemStack(CBItems.GIANT_FROG_PORTAL.get());
		if (level.getBlockEntity(pos) instanceof GiantFrogPortalBlockEntity portal && portal.hasSpace())
			stack.set(CBDataComponents.FROG_STOMACH_SPACE.get(), portal.getSpaceIndex());
		return stack;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return FrogPortalBehaviour.rotate(state, rotation);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FrogPortalBehaviour.AXIS);
	}
}
