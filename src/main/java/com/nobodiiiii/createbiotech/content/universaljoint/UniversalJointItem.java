package com.nobodiiiii.createbiotech.content.universaljoint;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.foundation.advancement.CBAdvancements;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.foundation.feature.CBFeature;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class UniversalJointItem extends BlockItem {

	public static final String FIRST_TARGET_KEY = "FirstUniversalJointTarget";
	public static final String FIRST_FACE_KEY = "FirstUniversalJointFace";
	public static final String FIRST_DIMENSION_KEY = "FirstUniversalJointDimension";
	public static final String FIRST_SUB_LEVEL_KEY = "FirstUniversalJointSubLevel";
	public static final String FIRST_SPACE_KNOWN_KEY = "FirstUniversalJointSpaceKnown";

	public UniversalJointItem(Properties properties) {
		super(CBBlocks.UNIVERSAL_JOINT.get(), properties);
	}

	@Nonnull
	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!CBFeature.UNIVERSAL_JOINT.isEnabled())
			return InteractionResult.FAIL;
		Player player = context.getPlayer();
		if (player != null && player.isShiftKeyDown()) {
			CBItemData.set(context.getItemInHand(), null);
			return InteractionResult.SUCCESS;
		}

		Level level = context.getLevel();
		if (player == null
			|| !player.canInteractWithBlock(context.getClickedPos(), 0))
			return InteractionResult.FAIL;
		Endpoint clickedEndpoint = Endpoint.fromClick(level, context.getClickedPos(), context.getClickedFace());

		if (level.isClientSide)
			return clickedEndpoint != null ? InteractionResult.SUCCESS : InteractionResult.FAIL;
		if (clickedEndpoint == null)
			return InteractionResult.FAIL;

		CompoundTag tag = CBItemData.getOrEmpty(context.getItemInHand());
		Endpoint firstEndpoint = readFirstEndpoint(level, tag);
		if (firstEndpoint == null && tag.contains(FIRST_TARGET_KEY)) {
			CBItemData.set(context.getItemInHand(), null);
			tag = CBItemData.getOrEmpty(context.getItemInHand());
		}

		if (firstEndpoint != null) {
			if (!canPair(level, firstEndpoint, clickedEndpoint))
				return InteractionResult.FAIL;
			if (!placeJointPair(level, firstEndpoint, clickedEndpoint))
				return InteractionResult.FAIL;

			if (!player.isCreative())
				context.getItemInHand().shrink(1);
			if (!context.getItemInHand().isEmpty())
				CBItemData.set(context.getItemInHand(), null);
			player.getCooldowns().addCooldown(this, getItemCooldownTicks());
			if (player instanceof ServerPlayer serverPlayer)
				CBAdvancements.award(serverPlayer, CBAdvancements.UNIVERSAL_JOINT);
			return InteractionResult.SUCCESS;
		}

		writeFirstEndpoint(tag, clickedEndpoint, level.dimension().location());
		CBItemData.set(context.getItemInHand(), tag);
		player.getCooldowns().addCooldown(this, getItemCooldownTicks());
		return InteractionResult.SUCCESS;
	}

	@Nullable
	public static Endpoint readFirstEndpoint(Level level, CompoundTag tag) {
		if (!tag.contains(FIRST_TARGET_KEY) || !tag.contains(FIRST_FACE_KEY)
			|| !tag.contains(FIRST_DIMENSION_KEY, Tag.TAG_STRING)
			|| !tag.contains(FIRST_SPACE_KNOWN_KEY, Tag.TAG_BYTE)
			|| !tag.getBoolean(FIRST_SPACE_KNOWN_KEY)
			|| (tag.contains(FIRST_SUB_LEVEL_KEY) && !tag.hasUUID(FIRST_SUB_LEVEL_KEY)))
			return null;

		Direction face = Direction.byName(tag.getString(FIRST_FACE_KEY));
		BlockPos storedTarget = NbtUtils.readBlockPos(tag, FIRST_TARGET_KEY).orElse(null);
		if (face == null || storedTarget == null)
			return null;

		ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString(FIRST_DIMENSION_KEY));
		if (dimensionId == null || !dimensionId.equals(level.dimension().location()))
			return null;

		UUID subLevelId = tag.hasUUID(FIRST_SUB_LEVEL_KEY) ? tag.getUUID(FIRST_SUB_LEVEL_KEY) : null;
		BlockPos resolvedTarget = SubLevelCompat.resolveRawPosition(level, storedTarget, subLevelId);
		return resolvedTarget == null ? null : Endpoint.fromStored(level, resolvedTarget, face, subLevelId);
	}

	private static void writeFirstEndpoint(CompoundTag tag, Endpoint endpoint, ResourceLocation dimensionId) {
		tag.put(FIRST_TARGET_KEY, NbtUtils.writeBlockPos(endpoint.targetPos));
		tag.putString(FIRST_FACE_KEY, endpoint.clickedFace.getName());
		tag.putString(FIRST_DIMENSION_KEY, dimensionId.toString());
		tag.putBoolean(FIRST_SPACE_KNOWN_KEY, true);
		if (endpoint.subLevelId != null)
			tag.putUUID(FIRST_SUB_LEVEL_KEY, endpoint.subLevelId);
		else
			tag.remove(FIRST_SUB_LEVEL_KEY);
	}

	private static boolean canPair(Level level, Endpoint first, Endpoint second) {
		return !sameAddress(first.jointPos, first.subLevelId, second.jointPos, second.subLevelId)
			&& !sameAddress(first.targetPos, first.subLevelId, second.targetPos, second.subLevelId)
			&& isWithinHitRange(level, first.jointPos, second.jointPos);
	}

	private static boolean sameAddress(BlockPos firstPos, @Nullable UUID firstSubLevelId, BlockPos secondPos,
		@Nullable UUID secondSubLevelId) {
		return firstPos.equals(secondPos) && Objects.equals(firstSubLevelId, secondSubLevelId);
	}

	public static boolean canConnect(Level level, BlockPos firstTarget, Direction firstFace, BlockPos secondTarget,
		Direction secondFace) {
		Endpoint firstEndpoint = Endpoint.fromClick(level, firstTarget, firstFace);
		Endpoint secondEndpoint = Endpoint.fromClick(level, secondTarget, secondFace);
		return firstEndpoint != null && secondEndpoint != null && canPair(level, firstEndpoint, secondEndpoint);
	}

	public static BlockPos getJointPos(BlockPos targetPos, Direction clickedFace) {
		return targetPos.relative(clickedFace);
	}

	public static boolean isWithinHitRange(Level level, BlockPos firstJoint, BlockPos secondJoint) {
		double range = getConnectionRange();
		return SubLevelCompat.distanceSquared(level, Vec3.atCenterOf(firstJoint), Vec3.atCenterOf(secondJoint))
			<= range * range;
	}

	public static boolean isWithinPreviewRange(Level level, BlockPos firstJoint, BlockPos secondJoint) {
		double range = CBConfigs.CLIENT.universalJoint.previewRange.get();
		return SubLevelCompat.distanceSquared(level, Vec3.atCenterOf(firstJoint), Vec3.atCenterOf(secondJoint))
			< range * range;
	}

	public static double getConnectionRange() {
		return CBConfigs.SERVER.universalJoint.effectiveStrainStartDistance();
	}

	private static int getItemCooldownTicks() {
		return CBConfigs.SERVER.universalJoint.itemCooldownTicks.get();
	}

	private static boolean placeJointPair(Level level, Endpoint first, Endpoint second) {
		BlockState firstState = stateForEndpoint(level, first);
		BlockState secondState = stateForEndpoint(level, second);

		boolean placedFirst = level.setBlock(first.jointPos, firstState, Block.UPDATE_ALL);
		boolean placedSecond = level.setBlock(second.jointPos, secondState, Block.UPDATE_ALL);
		if (!placedFirst || !placedSecond) {
			if (placedFirst)
				level.destroyBlock(first.jointPos, false);
			if (placedSecond)
				level.destroyBlock(second.jointPos, false);
			return false;
		}

		BlockEntity firstBlockEntity = level.getBlockEntity(first.jointPos);
		BlockEntity secondBlockEntity = level.getBlockEntity(second.jointPos);
		if (!(firstBlockEntity instanceof UniversalJointBlockEntity firstJoint)
			|| !(secondBlockEntity instanceof UniversalJointBlockEntity secondJoint)
			|| !firstJoint.createMutualLink(secondJoint)) {
			level.destroyBlock(first.jointPos, false);
			level.destroyBlock(second.jointPos, false);
			return false;
		}

		playPlacementSound(level, first);
		playPlacementSound(level, second);
		return true;
	}

	private static void playPlacementSound(Level level, Endpoint endpoint) {
		Vec3 worldPosition = SubLevelCompat.toWorld(level, endpoint.jointPos, Vec3.atCenterOf(endpoint.jointPos));
		level.playSound(null, worldPosition.x, worldPosition.y, worldPosition.z, SoundEvents.SLIME_JUMP,
			SoundSource.BLOCKS, 0.5f, 1.0f);
	}

	private static BlockState stateForEndpoint(Level level, Endpoint endpoint) {
		BlockState state = CBBlocks.UNIVERSAL_JOINT.get()
			.defaultBlockState()
			.setValue(UniversalJointBlock.FACING, endpoint.jointFacing);
		return ProperWaterloggedBlock.withWater(level, state, endpoint.jointPos);
	}

	public record Endpoint(BlockPos targetPos, Direction clickedFace, BlockPos jointPos, Direction jointFacing,
		@Nullable UUID subLevelId) {

		@Nullable
		private static Endpoint fromClick(Level level, BlockPos targetPos, Direction clickedFace) {
			if (!isValidTarget(level, targetPos))
				return null;

			BlockPos jointPos = targetPos.relative(clickedFace);
			if (!canPlaceEndpoint(level, jointPos) || !SubLevelCompat.sameSpace(level, targetPos, jointPos))
				return null;

			return new Endpoint(targetPos.immutable(), clickedFace, jointPos.immutable(), clickedFace.getOpposite(),
				SubLevelCompat.getSpaceId(level, targetPos));
		}

		@Nullable
		private static Endpoint fromStored(Level level, BlockPos targetPos, Direction clickedFace,
			@Nullable UUID expectedSubLevelId) {
			if (!SubLevelCompat.matchesSpace(level, targetPos, expectedSubLevelId) || !isValidTarget(level, targetPos))
				return null;

			BlockPos jointPos = targetPos.relative(clickedFace);
			if (!SubLevelCompat.matchesSpace(level, jointPos, expectedSubLevelId) || !canPlaceEndpoint(level, jointPos))
				return null;

			return new Endpoint(targetPos.immutable(), clickedFace, jointPos.immutable(), clickedFace.getOpposite(),
				expectedSubLevelId);
		}

		private static boolean isValidTarget(Level level, BlockPos targetPos) {
			if (!level.isLoaded(targetPos) || !SubLevelCompat.isValidSpacePosition(level, targetPos))
				return false;
			BlockState targetState = level.getBlockState(targetPos);
			return !targetState.isAir() && !targetState.canBeReplaced();
		}

		private static boolean canPlaceEndpoint(Level level, BlockPos jointPos) {
			if (jointPos.getY() < level.getMinBuildHeight() || jointPos.getY() >= level.getMaxBuildHeight())
				return false;
			if (!level.isLoaded(jointPos) || !SubLevelCompat.isValidSpacePosition(level, jointPos))
				return false;
			return level.getBlockState(jointPos).canBeReplaced();
		}
	}
}
