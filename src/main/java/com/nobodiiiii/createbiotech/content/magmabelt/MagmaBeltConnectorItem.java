package com.nobodiiiii.createbiotech.content.magmabelt;

import java.util.List;

import javax.annotation.Nonnull;

import com.simibubi.create.AllBlocks;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.foundation.block.CBBeltConnectorGeometry;
import com.nobodiiiii.createbiotech.foundation.block.CBBeltConnectorSelection;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.foundation.feature.CBFeature;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.nobodiiiii.createbiotech.content.magmabelt.MagmaBeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public class MagmaBeltConnectorItem extends BlockItem {

	public MagmaBeltConnectorItem(Properties properties) {
		super(CBBlocks.MAGMA_BELT.get(), properties);
	}

	@Override
	public String getDescriptionId() {
		return getOrCreateDescriptionId();
	}

	@Nonnull
	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!CBFeature.MAGMA_BELT.isEnabled())
			return InteractionResult.FAIL;
		Player playerEntity = context.getPlayer();
		if (playerEntity != null && playerEntity.isShiftKeyDown()) {
			CBItemData.set(context.getItemInHand(), null);
			return InteractionResult.SUCCESS;
		}

		Level world = context.getLevel();
		BlockPos pos = context.getClickedPos();
		boolean validAxis = validateAxis(world, pos);

		if (world.isClientSide)
			return validAxis ? InteractionResult.SUCCESS : InteractionResult.FAIL;

		CompoundTag tag = CBItemData.getOrEmpty(context.getItemInHand());
		BlockPos firstPulley = null;

		// Remove first if no longer existant or valid
		if (tag.contains(CBBeltConnectorSelection.POSITION)) {
			firstPulley = CBBeltConnectorSelection.readValid(tag, world, pos, maxLength() * 2,
				candidate -> validateAxis(world, candidate));
			if (firstPulley == null) {
				CBItemData.set(context.getItemInHand(), null);
				tag = new CompoundTag();
			}
		}

		if (!validAxis || playerEntity == null)
			return InteractionResult.FAIL;

		if (tag.contains(CBBeltConnectorSelection.POSITION)) {

			if (!canConnect(world, firstPulley, pos))
				return InteractionResult.FAIL;

			if (firstPulley != null && !firstPulley.equals(pos)) {
				createBelts(world, firstPulley, pos);
				AllAdvancements.BELT.awardTo(playerEntity);
				if (!playerEntity.isCreative())
					context.getItemInHand()
						.shrink(1);
			}

			if (!context.getItemInHand()
				.isEmpty()) {
				CBItemData.set(context.getItemInHand(), null);
				playerEntity.getCooldowns()
					.addCooldown(this, 5);
			}
			return InteractionResult.SUCCESS;
		}

		CBBeltConnectorSelection.write(tag, world, pos);
		CBItemData.set(context.getItemInHand(), tag);
		playerEntity.getCooldowns()
			.addCooldown(this, 5);
		return InteractionResult.SUCCESS;
	}

	public static void createBelts(Level world, BlockPos start, BlockPos end) {
		if (!SubLevelCompat.sameSpace(world, start, end))
			return;
		world.playSound(null, BlockPos.containing(VecHelper.getCenterOf(start.offset(end))
			.scale(.5f)), SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.5F, 1F);

		BeltSlope slope = CBBeltConnectorGeometry.slopeBetween(start, end);
		Direction facing = CBBeltConnectorGeometry.facingFromTo(start, end);

		BlockPos diff = end.subtract(start);
		if (diff.getX() == diff.getZ())
			facing = Direction.get(facing.getAxisDirection(), world.getBlockState(start)
				.getValue(BlockStateProperties.AXIS) == Axis.X ? Axis.Z : Axis.X);

		List<BlockPos> beltsToCreate = CBBeltConnectorGeometry.chainBetween(start, end, slope, facing);
		BlockState beltBlock = CBBlocks.MAGMA_BELT.get().defaultBlockState();
		boolean failed = false;

		for (BlockPos pos : beltsToCreate) {
			BlockState existingBlock = world.getBlockState(pos);
			if (existingBlock.getDestroySpeed(world, pos) == -1) {
				failed = true;
				break;
			}

			BeltPart part = pos.equals(start) ? BeltPart.START : pos.equals(end) ? BeltPart.END : BeltPart.MIDDLE;
			BlockState shaftState = world.getBlockState(pos);
			boolean pulley = ShaftBlock.isShaft(shaftState);
			if (part == BeltPart.MIDDLE && pulley)
				part = BeltPart.PULLEY;
			if (pulley && shaftState.getValue(AbstractSimpleShaftBlock.AXIS) == Axis.Y)
				slope = BeltSlope.SIDEWAYS;

			if (!existingBlock.canBeReplaced())
				world.destroyBlock(pos, false);

			KineticBlockEntity.switchToBlockState(world, pos,
				ProperWaterloggedBlock.withWater(world, beltBlock.setValue(MagmaBeltBlock.SLOPE, slope)
					.setValue(MagmaBeltBlock.PART, part)
					.setValue(MagmaBeltBlock.HORIZONTAL_FACING, facing), pos));
		}

		if (!failed)
			return;

		for (BlockPos pos : beltsToCreate)
			if (MagmaBeltBlock.isMagmaBelt(world.getBlockState(pos)))
				world.destroyBlock(pos, false);
	}

	public static boolean canConnect(Level world, BlockPos first, BlockPos second) {
		if (!SubLevelCompat.sameSpace(world, first, second))
			return false;
		if (!world.isLoaded(first) || !world.isLoaded(second))
			return false;
		if (!second.closerThan(first, maxLength()))
			return false;

		BlockPos diff = second.subtract(first);
		Axis shaftAxis = world.getBlockState(first)
			.getValue(BlockStateProperties.AXIS);

		int x = diff.getX();
		int y = diff.getY();
		int z = diff.getZ();
		int sames = ((Math.abs(x) == Math.abs(y)) ? 1 : 0) + ((Math.abs(y) == Math.abs(z)) ? 1 : 0)
			+ ((Math.abs(z) == Math.abs(x)) ? 1 : 0);

		if (shaftAxis.choose(x, y, z) != 0)
			return false;
		if (sames != 1)
			return false;
		if (shaftAxis != world.getBlockState(second)
			.getValue(BlockStateProperties.AXIS))
			return false;
		if (shaftAxis == Axis.Y && x != 0 && z != 0)
			return false;

		BlockEntity blockEntity = world.getBlockEntity(first);
		BlockEntity blockEntity2 = world.getBlockEntity(second);

		if (!(blockEntity instanceof KineticBlockEntity))
			return false;
		if (!(blockEntity2 instanceof KineticBlockEntity))
			return false;

		float speed1 = ((KineticBlockEntity) blockEntity).getTheoreticalSpeed();
		float speed2 = ((KineticBlockEntity) blockEntity2).getTheoreticalSpeed();
		if (Math.signum(speed1) != Math.signum(speed2) && speed1 != 0 && speed2 != 0)
			return false;

		BlockPos step = BlockPos.containing(Math.signum(diff.getX()), Math.signum(diff.getY()), Math.signum(diff.getZ()));
		int limit = 1000;
		for (BlockPos currentPos = first.offset(step); !currentPos.equals(second) && limit-- > 0; currentPos =
			currentPos.offset(step)) {
			BlockState blockState = world.getBlockState(currentPos);
			if (ShaftBlock.isShaft(blockState) && blockState.getValue(AbstractSimpleShaftBlock.AXIS) == shaftAxis)
				continue;
			if (!blockState.canBeReplaced())
				return false;
		}

		return true;

	}

	public static Integer maxLength() {
		return AllConfigs.server().kinetics.maxBeltLength.get();
	}

	public static boolean validateAxis(Level world, BlockPos pos) {
		if (!world.isLoaded(pos))
			return false;
		if (!ShaftBlock.isShaft(world.getBlockState(pos)))
			return false;
		return true;
	}

}
