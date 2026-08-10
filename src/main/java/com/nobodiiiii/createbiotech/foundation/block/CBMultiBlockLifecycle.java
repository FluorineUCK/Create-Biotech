package com.nobodiiiii.createbiotech.foundation.block;

import javax.annotation.Nullable;

import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared lifecycle plumbing for blocks whose single logical machine occupies more
 * than one position.
 *
 * <p>The contract mirrors vanilla doors and beds plus Create's large water wheel:</p>
 * <ul>
 * <li>Only the anchor part has a loot table entry, every other part drops nothing,
 * and no code path pops the block item itself. Creative mode, explosions and
 * third-party {@code destroyBlock(pos, false)} calls then behave the way the engine
 * intends instead of each needing its own special case.</li>
 * <li>Structure validation never runs from {@code updateShape}. Neighbour changes
 * only schedule a block tick, which collapses a burst of updates into a single
 * check, keeps the check off the client, and makes reentrancy guards unnecessary
 * because scheduled ticks never fire on a position that has already been cleared.</li>
 * <li>Positions in unloaded chunks count as intact, so a structure straddling a
 * chunk border is neither torn down nor allowed to force its neighbour in from disk
 * just because a block update happened at the edge of the loaded area.</li>
 * </ul>
 */
public final class CBMultiBlockLifecycle {

	/** Level event id for the vanilla "block broken" particle and sound burst. */
	private static final int BLOCK_BREAK_EFFECT = 2001;
	private static boolean movementChecksRegistered;

	private CBMultiBlockLifecycle() {}

	/**
	 * A block occupying one position of a larger logical block. Every part reports
	 * the same type and anchor, allowing structure collectors to keep the parts
	 * together without knowing the layout of each machine.
	 */
	public interface Part {
		default Class<? extends Block> getMultiBlockType() {
			return getClass().asSubclass(Block.class);
		}

		BlockPos getMultiBlockAnchor(BlockPos pos, BlockState state);
	}

	/**
	 * Registers the shared movement rules used by Create contraptions and by
	 * Simulated physical structures. Parts remain piston-immovable, but Create is
	 * allowed to collect them, and the attachment check pulls in the rest of their
	 * logical block.
	 *
	 * <p>Parts intentionally remain non-brittle. Unlike vanilla doors and beds, these
	 * machines rely on normal adjacent-block stickiness to join a moving structure;
	 * their implementations must defer completeness checks until placement settles.</p>
	 */
	public static synchronized void registerMovementChecks() {
		if (movementChecksRegistered)
			return;
		movementChecksRegistered = true;

		BlockMovementChecks.registerMovementNecessaryCheck((state, level, pos) ->
			state.getBlock() instanceof Part ? CheckResult.SUCCESS : CheckResult.PASS);
		BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) ->
			state.getBlock() instanceof Part ? CheckResult.SUCCESS : CheckResult.PASS);
		BlockMovementChecks.registerAttachedCheck(CBMultiBlockLifecycle::isPartAttachedTowards);
	}

	private static CheckResult isPartAttachedTowards(BlockState state, Level level, BlockPos pos,
		Direction direction) {
		if (!(state.getBlock() instanceof Part part))
			return CheckResult.PASS;

		BlockPos neighbourPos = pos.relative(direction);
		BlockState neighbourState = level.getBlockState(neighbourPos);
		if (!(neighbourState.getBlock() instanceof Part neighbourPart))
			return CheckResult.PASS;
		if (part.getMultiBlockType() != neighbourPart.getMultiBlockType())
			return CheckResult.PASS;

		BlockPos anchor = part.getMultiBlockAnchor(pos, state);
		BlockPos neighbourAnchor = neighbourPart.getMultiBlockAnchor(neighbourPos, neighbourState);
		return anchor.equals(neighbourAnchor) ? CheckResult.SUCCESS : CheckResult.PASS;
	}

	/**
	 * Queues a structure check on {@code pos} for the next tick. Repeated calls
	 * before that tick fires collapse into a single check.
	 */
	public static void scheduleValidation(LevelAccessor level, BlockPos pos, Block block) {
		if (level.isClientSide())
			return;
		if (level.getBlockTicks()
			.hasScheduledTick(pos, block))
			return;
		level.scheduleTick(pos, block, 1);
	}

	/**
	 * Whether {@code pos} can be read without pulling its chunk in from disk. Callers
	 * on the validation path treat an unloaded position as still intact rather than
	 * reading it; removal paths run rarely enough to just read through.
	 *
	 * <p>Readers that are not a {@link Level} - worldgen regions, structure templates -
	 * already refuse access outside their own bounds, so they count as loaded.</p>
	 */
	public static boolean isLoaded(LevelReader level, BlockPos pos) {
		return !(level instanceof Level actualLevel) || actualLevel.isLoaded(pos);
	}

	/**
	 * Removes a part without dropping anything, propagating the suppression into
	 * whatever the removal cascades into. Same flags as vanilla's
	 * {@code DoublePlantBlock#preventCreativeDropFromBottomPart}.
	 */
	public static void removeSilently(LevelAccessor level, BlockPos pos) {
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
	}

	/**
	 * Removes the anchor on behalf of a creative player who broke one of the other
	 * parts: no drops, but the usual break particles and sound so it does not read as
	 * a bug. Block entity contents still drop, matching vanilla containers.
	 */
	public static void removeAnchorInCreative(Level level, BlockPos anchorPos, @Nullable Player player) {
		BlockState anchorState = level.getBlockState(anchorPos);
		removeSilently(level, anchorPos);
		level.levelEvent(player, BLOCK_BREAK_EFFECT, anchorPos, Block.getId(anchorState));
	}
}
