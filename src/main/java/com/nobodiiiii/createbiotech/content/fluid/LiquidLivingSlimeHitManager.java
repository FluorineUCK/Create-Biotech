package com.nobodiiiii.createbiotech.content.fluid;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBFluids;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class LiquidLivingSlimeHitManager {

	private static final Reference2ObjectOpenHashMap<ServerLevel, LevelHits> HITS_BY_LEVEL =
		new Reference2ObjectOpenHashMap<>();

	private LiquidLivingSlimeHitManager() {}

	public static void hit(ServerLevel level, BlockPos pos, FluidState fluidState) {
		if (!isLiquidLivingSlime(fluidState)) {
			forget(level, pos);
			return;
		}

		playHitSounds(level, pos);

		if (!fluidState.isSource()) {
			clearFluid(level, pos);
			return;
		}

		LevelHits levelHits = HITS_BY_LEVEL.computeIfAbsent(level, ignored -> new LevelHits());
		long packedPos = pos.asLong();
		int hits = levelHits.hitCounts.get(packedPos) + 1;
		int hitsToBreak = CBConfigs.SERVER.liquidLivingSlime.sourceHitsToBreak.get();
		if (hits < hitsToBreak) {
			levelHits.hitCounts.put(packedPos, (byte) hits);
			int breakerId = levelHits.breakerIds.get(packedPos);
			if (breakerId == 0) {
				breakerId = levelHits.allocateBreakerId();
				levelHits.breakerIds.put(packedPos, breakerId);
			}
			level.destroyBlockProgress(breakerId, pos, hitToProgressStage(hits, hitsToBreak));
			return;
		}

		clearFluid(level, pos);
		if (CBConfigs.SERVER.liquidLivingSlime.dropSlimeBallWhenSourceBreaks.get())
			Block.popResource(level, pos, new ItemStack(Items.SLIME_BALL));
	}

	public static void forget(ServerLevel level, BlockPos pos) {
		LevelHits levelHits = HITS_BY_LEVEL.get(level);
		if (levelHits == null)
			return;

		long packedPos = pos.asLong();
		levelHits.hitCounts.remove(packedPos);
		int breakerId = levelHits.breakerIds.remove(packedPos);
		if (breakerId != 0)
			level.destroyBlockProgress(breakerId, pos, -1);
		if (levelHits.hitCounts.isEmpty())
			HITS_BY_LEVEL.remove(level);
	}

	private static void clearFluid(ServerLevel level, BlockPos pos) {
		forget(level, pos);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	private static void playHitSounds(ServerLevel level, BlockPos pos) {
		level.playSound(null, pos, SoundEvents.SLIME_HURT_SMALL, SoundSource.BLOCKS, 0.8F, 0.9F);
		level.playSound(null, pos, SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.BLOCKS, 0.6F, 0.95F);
	}

	private static boolean isLiquidLivingSlime(FluidState fluidState) {
		return !fluidState.isEmpty()
			&& fluidState.getFluidType() == CBFluids.LIQUID_LIVING_SLIME_TYPE.get();
	}

	private static int hitToProgressStage(int hits, int hitsToBreak) {
		if (hitsToBreak == 4) {
			return switch (hits) {
			case 1 -> 2;
			case 2 -> 5;
			default -> 8;
			};
		}
		return Mth.clamp((int) Math.round(hits * 9.0D / hitsToBreak), 0, 8);
	}

	@SubscribeEvent
	public static void onLevelUnload(LevelEvent.Unload event) {
		if (event.getLevel() instanceof ServerLevel serverLevel)
			HITS_BY_LEVEL.remove(serverLevel);
	}

	private static final class LevelHits {
		private final Long2ByteOpenHashMap hitCounts = new Long2ByteOpenHashMap();
		private final Long2IntOpenHashMap breakerIds = new Long2IntOpenHashMap();
		private int nextBreakerId = Integer.MIN_VALUE;

		private int allocateBreakerId() {
			int breakerId = nextBreakerId++;
			if (nextBreakerId == 0)
				nextBreakerId = Integer.MIN_VALUE;
			return breakerId;
		}
	}
}
