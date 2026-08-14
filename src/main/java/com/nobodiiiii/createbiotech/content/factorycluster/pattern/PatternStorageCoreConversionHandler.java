package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.CBBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class PatternStorageCoreConversionHandler {
	private PatternStorageCoreConversionHandler() {}

	record CandidateFacts(boolean villager, boolean adult, boolean librarian) {}

	interface ConversionOps {
		boolean placeLower();
		boolean placeUpper();
		boolean installSnapshot();
		void clearHeldBox();
		void restoreLectern();
	}

	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		Level level = event.getLevel();
		BlockPos pos = event.getPos();
		BlockState state = level.getBlockState(pos);
		if (!state.is(Blocks.LECTERN)
			|| !(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)
			|| !lectern.getBook().isEmpty()
			|| !PatternStorageCoreBlock.hasSpaceForUpperHalf(level, pos))
			return;

		ItemStack held = event.getItemStack();
		Entity candidate = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(held, level);
		if (!isAdultLibrarian(candidate))
			return;

		event.setCanceled(true);
		if (level.isClientSide) {
			event.setCancellationResult(InteractionResult.SUCCESS);
			return;
		}
		boolean converted = tryConvert(level, pos, held, CBBlocks.PATTERN_STORAGE_CORE.get());
		event.setCancellationResult(converted ? InteractionResult.SUCCESS : InteractionResult.FAIL);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
	public static void onBreak(BlockEvent.BreakEvent event) {
		BlockState state = event.getState();
		if (!(state.getBlock() instanceof PatternStorageCoreBlock))
			return;
		Level level = (Level) event.getLevel();
		BlockPos anchor = state.getValue(PatternStorageCoreBlock.HALF) == DoubleBlockHalf.LOWER
			? event.getPos() : event.getPos().below();
		event.setCanceled(true);
		Player player = event.getPlayer();
		ItemStack tool = player == null ? ItemStack.EMPTY : player.getMainHandItem();
		PatternStorageCoreLifecycle.controlledPlayerBreak(level, anchor, player, tool,
			entity -> level instanceof ServerLevel server && server.addFreshEntity(entity));
	}

	static boolean tryConvert(Level level, BlockPos pos, ItemStack held,
		PatternStorageCoreBlock coreBlock) {
		BlockState originalState = level.getBlockState(pos);
		if (!originalState.is(Blocks.LECTERN)
			|| !(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)
			|| !lectern.getBook().isEmpty()
			|| !PatternStorageCoreBlock.hasSpaceForUpperHalf(level, pos))
			return false;
		Entity candidate = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(held, level);
		if (!isAdultLibrarian(candidate))
			return false;

		BlockState originalUpperState = level.getBlockState(pos.above());
		CompoundTag originalLecternData = lectern.saveWithoutMetadata(level.registryAccess());
		ItemStack snapshot = held.copy();
		BlockState lower = coreBlock.defaultBlockState()
			.setValue(PatternStorageCoreBlock.FACING, originalState.getValue(LecternBlock.FACING))
			.setValue(PatternStorageCoreBlock.HALF, DoubleBlockHalf.LOWER);
		BlockState upper = lower.setValue(PatternStorageCoreBlock.HALF, DoubleBlockHalf.UPPER);

		return runConversionTransaction(candidateFacts(candidate), new ConversionOps() {
			@Override
			public boolean placeLower() {
				return level.setBlock(pos, lower, Block.UPDATE_ALL);
			}

			@Override
			public boolean placeUpper() {
				return level.setBlock(pos.above(), upper, Block.UPDATE_ALL);
			}

			@Override
			public boolean installSnapshot() {
				if (!(level.getBlockEntity(pos) instanceof PatternStorageCoreBlockEntity newCore))
					return false;
				newCore.installLibrarianSnapshot(snapshot);
				return true;
			}

			@Override
			public void clearHeldBox() {
				CapturedEntityBoxHelper.clearCapturedEntity(held);
			}

			@Override
			public void restoreLectern() {
				PatternStorageCoreConversionHandler.restoreLectern(level, pos, originalState,
					originalUpperState, originalLecternData);
			}
		});
	}

	private static boolean isAdultLibrarian(Entity candidate) {
		return isAdultLibrarian(candidateFacts(candidate));
	}

	static CandidateFacts candidateFacts(Entity candidate) {
		if (!(candidate instanceof Villager villager))
			return new CandidateFacts(false, false, false);
		return new CandidateFacts(true, !villager.isBaby(),
			villager.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN);
	}

	static boolean isAdultLibrarian(CandidateFacts candidate) {
		return candidate.villager() && candidate.adult() && candidate.librarian();
	}

	static boolean runConversionTransaction(CandidateFacts candidate, ConversionOps operations) {
		if (!isAdultLibrarian(candidate))
			return false;
		if (!operations.placeLower() || !operations.placeUpper()
			|| !operations.installSnapshot()) {
			operations.restoreLectern();
			return false;
		}
		operations.clearHeldBox();
		return true;
	}

	private static void restoreLectern(Level level, BlockPos pos, BlockState originalState,
		BlockState originalUpperState, CompoundTag originalLecternData) {
		int flags = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
		if (!level.getBlockState(pos).equals(originalState)
			|| !(level.getBlockEntity(pos) instanceof LecternBlockEntity)) {
			level.removeBlockEntity(pos);
			if (level.getBlockState(pos).equals(originalState))
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
			level.setBlock(pos, originalState, flags);
		}
		level.setBlock(pos.above(), originalUpperState, flags);
		if (level.getBlockEntity(pos) instanceof LecternBlockEntity restored)
			restored.loadWithComponents(originalLecternData, level.registryAccess());
	}
}
