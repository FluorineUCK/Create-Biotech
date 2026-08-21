package com.nobodiiiii.createbiotech.content.processing.basin;

import java.util.List;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.beltsurface.BeltFunnelStateExtensions;
import com.nobodiiiii.createbiotech.content.beltsurface.BeltSurface;
import com.nobodiiiii.createbiotech.content.beltsurface.BeltSurfaceResolver;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock.Shape;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;

public final class BasinEntityProcessing {
	private static final int DATA_VERSION = 2;
	private static final double BASIN_INNER_MIN = 2 / 16d;
	private static final double BASIN_INNER_MAX = 14 / 16d;
	private static final String DATA_ROOT = CreateBiotech.MOD_ID;
	private static final String DATA_VERSION_TAG = "BasinEntityProcessingDataVersion";
	private static final String CAPTURED_TAG = "BasinEntityProcessingCaptured";
	private static final String BASIN_POS_TAG = "BasinEntityProcessingBasinPos";
	private static final String PREVIOUS_NO_AI_TAG = "BasinEntityProcessingPreviousNoAi";
	private static final String PREVIOUS_NO_GRAVITY_TAG = "BasinEntityProcessingPreviousNoGravity";
	private static final String SYNCED_ITEM_COUNT_TAG = "BasinEntityProcessingSyncedItemCount";

	private static final String CREATE_FUNNEL_PACKAGE = "com.simibubi.create.content.logistics.funnel.";
	private static final String CREATE_BASIN_RECIPE = "com.simibubi.create.content.processing.basin.BasinRecipe";
	private static final String CREATE_BASIN_BLOCK_ENTITY =
		"com.simibubi.create.content.processing.basin.BasinBlockEntity";
	private static final String CREATE_BASIN_ACTIVE_OUTPUT_METHOD = "updateSpoutput";
	private static final String FUNNEL_MIXIN = "com.nobodiiiii.createbiotech.mixin.FunnelBlockEntityMixin";
	private static final ThreadLocal<Integer> CAPTURED_SLIME_ITEM_MOVEMENT_DEPTH = new ThreadLocal<>();

	private BasinEntityProcessing() {}

	public static boolean isCapturedSmallSlimeItem(ItemStack stack) {
		return stack.getItem() == CBItems.CAPTURED_SMALL_SLIME.get();
	}

	/** The basin inventories are the sole authoritative contained-slime state. */
	public static boolean hasCapturedSmallSlimes(BasinBlockEntity basin) {
		return getCapturedSmallSlimeItemCount(basin) > 0;
	}

	public static int getCapturedSmallSlimeItemCount(BasinBlockEntity basin) {
		return countCapturedSmallSlimeItems(basin.getInputInventory())
			+ countCapturedSmallSlimeItems(basin.getOutputInventory());
	}

	public static boolean canMoveCapturedSmallSlimeItems() {
		Integer movementDepth = CAPTURED_SLIME_ITEM_MOVEMENT_DEPTH.get();
		if (movementDepth != null && movementDepth > 0)
			return true;

		for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
			String className = frame.getClassName();
			if (className.startsWith(CREATE_FUNNEL_PACKAGE) || className.equals(CREATE_BASIN_RECIPE)
				|| className.equals(FUNNEL_MIXIN)
				|| (className.equals(CREATE_BASIN_BLOCK_ENTITY)
					&& frame.getMethodName().equals(CREATE_BASIN_ACTIVE_OUTPUT_METHOD)))
				return true;
		}
		return false;
	}

	/**
	 * One-time conversion of old entity mirrors. Existing control items win;
	 * surplus legacy entities are converted into items when possible and otherwise
	 * released with their pre-capture movement flags restored.
	 */
	public static boolean migrateLegacyContainedSlimes(BasinBlockEntity basin) {
		Level level = basin.getLevel();
		if (level == null || level.isClientSide)
			return false;
		CompoundTag data = getCreateBiotechData(basin);
		if (data.getInt(DATA_VERSION_TAG) >= DATA_VERSION)
			return false;

		int authoritativeItems = getCapturedSmallSlimeItemCount(basin);
		List<Slime> legacySlimes = level.getEntitiesOfClass(Slime.class, getEntityProcessingBounds(basin.getBlockPos()),
			slime -> slime.getSize() == 1 && isCapturedInBasin(slime, basin.getBlockPos()));
		int mirrored = 0;
		for (Slime slime : legacySlimes) {
			if (mirrored < authoritativeItems) {
				clearLegacyCaptureData(slime, false);
				slime.discard();
				mirrored++;
				continue;
			}
			if (insertCapturedSmallSlimeItems(basin, 1, true) == 1
				&& insertCapturedSmallSlimeItems(basin, 1, false) == 1) {
				clearLegacyCaptureData(slime, false);
				slime.discard();
				continue;
			}
			clearLegacyCaptureData(slime, true);
		}

		data.remove(SYNCED_ITEM_COUNT_TAG);
		data.putInt(DATA_VERSION_TAG, DATA_VERSION);
		notifyBasinContentsChanged(basin);
		return !legacySlimes.isEmpty();
	}

	public static boolean acceptsCapturedSmallSlimeOutput(BasinBlockEntity basin, List<ItemStack> outputItems,
		boolean simulate) {
		if (outputItems.isEmpty())
			return true;
		beginCapturedSlimeItemMovement();
		basin.getOutputInventory().allowInsertion();
		try {
			for (ItemStack stack : outputItems) {
				if (!stack.isEmpty() && !ItemHandlerHelper.insertItemStacked(
					basin.getOutputInventory(), stack.copy(), simulate).isEmpty())
					return false;
			}
		} finally {
			basin.getOutputInventory().forbidInsertion();
			endCapturedSlimeItemMovement();
		}
		return true;
	}

	public static void handleFunnelEntityInside(Level level, BlockPos funnelPos, Entity entity) {
		if (level.isClientSide || !(entity instanceof Slime slime) || !slime.isAlive() || slime.getSize() != 1
			|| isCapturedSmallSlime(slime) || !getSmallSlimeCaptureBounds(funnelPos).intersects(slime.getBoundingBox()))
			return;
		if (level.getBlockEntity(funnelPos) instanceof SlimeCaptureFunnelAccess captureFunnel)
			captureFunnel.createBiotech$tryCaptureSmallSlime(slime);
	}

	public static boolean tryCaptureSmallSlimeFromFunnel(FunnelBlockEntity funnel, Slime slime) {
		Level level = funnel.getLevel();
		if (level == null || level.isClientSide || slime.level() != level || !slime.isAlive()
			|| slime.getSize() != 1 || isCapturedSmallSlime(slime)
			|| !getSmallSlimeCaptureBounds(funnel.getBlockPos()).intersects(slime.getBoundingBox()))
			return false;

		BlockState blockState = funnel.getBlockState();
		if (blockState.getOptionalValue(AbstractFunnelBlock.POWERED).orElse(false))
			return false;
		Direction facing = getSmallSlimeInputFacing(level, funnel.getBlockPos(), blockState);
		if (facing == null)
			return false;
		BlockPos basinPos = funnel.getBlockPos().relative(facing.getOpposite());
		if (!(level.getBlockEntity(basinPos) instanceof BasinBlockEntity basin))
			return false;

		if (insertCapturedSmallSlimeItems(basin, 1, true) != 1)
			return false;
		if (insertCapturedSmallSlimeItems(basin, 1, false) != 1)
			return false;
		slime.discard();
		notifyBasinContentsChanged(basin);
		return true;
	}

	/** True only for legacy mirrors waiting for their one-time basin migration. */
	public static boolean isCapturedSmallSlime(Entity entity) {
		if (!(entity instanceof Slime slime) || slime.getSize() != 1)
			return false;
		CompoundTag data = getExistingCreateBiotechData(entity);
		return data != null && data.getBoolean(CAPTURED_TAG);
	}

	public static Slime createSmallSlime(Level level, Vec3 position, Vec3 motion) {
		if (level == null)
			return null;
		Slime slime = EntityType.SLIME.create(level);
		if (slime == null)
			return null;
		slime.setSize(1, true);
		slime.setPersistenceRequired();
		slime.moveTo(position.x, position.y, position.z, level.random.nextFloat() * 360, 0);
		slime.setDeltaMovement(motion);
		slime.fallDistance = 0;
		return slime;
	}

	public static float getContainedSlimeAnimationPhase(Level level, BlockPos basinPos, int visualIndex,
		float partialTicks) {
		return (level.getGameTime() + partialTicks) * .22f + visualIndex * 1.73f
			+ (basinPos.asLong() & 31) * .11f;
	}

	private static int insertCapturedSmallSlimeItems(BasinBlockEntity basin, int count, boolean simulate) {
		beginCapturedSlimeItemMovement();
		try {
			int inserted = 0;
			int remaining = count;
			int maxStackSize = new ItemStack(CBItems.CAPTURED_SMALL_SLIME.get()).getMaxStackSize();
			while (remaining > 0) {
				ItemStack stack = new ItemStack(CBItems.CAPTURED_SMALL_SLIME.get(), Math.min(remaining, maxStackSize));
				ItemStack remainder = ItemHandlerHelper.insertItemStacked(basin.getInputInventory(), stack, simulate);
				int insertedThisPass = stack.getCount() - remainder.getCount();
				if (insertedThisPass <= 0)
					break;
				inserted += insertedThisPass;
				remaining -= insertedThisPass;
			}
			return inserted;
		} finally {
			endCapturedSlimeItemMovement();
		}
	}

	private static void beginCapturedSlimeItemMovement() {
		Integer depth = CAPTURED_SLIME_ITEM_MOVEMENT_DEPTH.get();
		CAPTURED_SLIME_ITEM_MOVEMENT_DEPTH.set(depth == null ? 1 : depth + 1);
	}

	private static void endCapturedSlimeItemMovement() {
		Integer depth = CAPTURED_SLIME_ITEM_MOVEMENT_DEPTH.get();
		if (depth == null || depth <= 1)
			CAPTURED_SLIME_ITEM_MOVEMENT_DEPTH.remove();
		else
			CAPTURED_SLIME_ITEM_MOVEMENT_DEPTH.set(depth - 1);
	}

	private static int countCapturedSmallSlimeItems(IItemHandlerModifiable inventory) {
		int count = 0;
		for (int slot = 0; slot < inventory.getSlots(); slot++) {
			ItemStack stack = inventory.getStackInSlot(slot);
			if (isCapturedSmallSlimeItem(stack))
				count += stack.getCount();
		}
		return count;
	}

	private static Direction getSmallSlimeInputFacing(Level level, BlockPos funnelPos, BlockState blockState) {
		if (blockState.getBlock() instanceof FunnelBlock) {
			if (blockState.getValue(FunnelBlock.EXTRACTING))
				return null;
			Direction facing = AbstractFunnelBlock.getFunnelFacing(blockState);
			return facing != null && facing.getAxis().isHorizontal() ? facing : null;
		}
		if (!(blockState.getBlock() instanceof BeltFunnelBlock))
			return null;
		Direction facing = AbstractFunnelBlock.getFunnelFacing(blockState);
		if (facing == null)
			return null;
		Direction outwardNormal = BeltFunnelStateExtensions.tiltedOutwardNormal(blockState);
		if (outwardNormal != null)
			facing = BeltSurface.worldizeCanonical(facing, outwardNormal);
		if (!facing.getAxis().isHorizontal())
			return null;
		Shape shape = blockState.getValue(BeltFunnelBlock.SHAPE);
		if (shape == Shape.PULLING)
			return facing;
		if (shape == Shape.PUSHING)
			return null;
		BeltSurface surface = BeltSurfaceResolver.resolve(level, funnelPos, blockState);
		return isTakingFromBelt(level, funnelPos, facing, surface) ? facing : null;
	}

	private static boolean isTakingFromBelt(Level level, BlockPos funnelPos, Direction worldFacing,
		BeltSurface surface) {
		if (surface != null)
			return surface.movementFacing() != worldFacing;
		BeltBlockEntity belt = BeltHelper.getSegmentBE(level, funnelPos.below());
		return belt != null && belt.getMovementFacing() != worldFacing;
	}

	private static AABB getEntityProcessingBounds(BlockPos basinPos) {
		return new AABB(basinPos.getX() + BASIN_INNER_MIN, basinPos.getY(), basinPos.getZ() + BASIN_INNER_MIN,
			basinPos.getX() + BASIN_INNER_MAX, basinPos.getY() + getEntityScanHeight(),
			basinPos.getZ() + BASIN_INNER_MAX);
	}

	private static AABB getSmallSlimeCaptureBounds(BlockPos funnelPos) {
		return new AABB(funnelPos.getX(), funnelPos.getY(), funnelPos.getZ(), funnelPos.getX() + 1,
			funnelPos.getY() + .5d, funnelPos.getZ() + 1);
	}

	private static boolean isCapturedInBasin(Entity entity, BlockPos basinPos) {
		CompoundTag data = getExistingCreateBiotechData(entity);
		return data != null && data.getBoolean(CAPTURED_TAG)
			&& data.contains(BASIN_POS_TAG, Tag.TAG_LONG) && data.getLong(BASIN_POS_TAG) == basinPos.asLong();
	}

	private static void clearLegacyCaptureData(Slime slime, boolean restoreState) {
		CompoundTag data = getExistingCreateBiotechData(slime);
		if (data == null)
			return;
		if (restoreState) {
			slime.setNoAi(data.getBoolean(PREVIOUS_NO_AI_TAG));
			slime.setNoGravity(data.getBoolean(PREVIOUS_NO_GRAVITY_TAG));
			CapturedEntityBoxHelper.unmarkAiDisabledByMod(slime);
		}
		data.remove(CAPTURED_TAG);
		data.remove(BASIN_POS_TAG);
		data.remove(PREVIOUS_NO_AI_TAG);
		data.remove(PREVIOUS_NO_GRAVITY_TAG);
	}

	private static double getEntityScanHeight() {
		return CBConfigs.SERVER.basinEntityProcessing.entityScanHeight.get();
	}

	private static void notifyBasinContentsChanged(BasinBlockEntity basin) {
		basin.notifyChangeOfContents();
		basin.notifyUpdate();
	}

	private static CompoundTag getCreateBiotechData(BasinBlockEntity basin) {
		CompoundTag persistentData = basin.getPersistentData();
		if (!persistentData.contains(DATA_ROOT, Tag.TAG_COMPOUND))
			persistentData.put(DATA_ROOT, new CompoundTag());
		return persistentData.getCompound(DATA_ROOT);
	}

	private static CompoundTag getExistingCreateBiotechData(Entity entity) {
		CompoundTag persistentData = entity.getPersistentData();
		return persistentData.contains(DATA_ROOT, Tag.TAG_COMPOUND)
			? persistentData.getCompound(DATA_ROOT) : null;
	}
}
