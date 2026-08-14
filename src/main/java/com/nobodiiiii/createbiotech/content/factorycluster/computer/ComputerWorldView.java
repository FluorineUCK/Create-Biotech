package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class ComputerWorldView implements ComputerStructureScanner.View {
	private final Level level;
	private final BlockPos seed;

	ComputerWorldView(Level level, BlockPos seed) {
		this.level = Objects.requireNonNull(level, "level");
		this.seed = Objects.requireNonNull(seed, "seed").immutable();
	}

	@Override
	public boolean isLoaded(BlockPos pos) {
		return CBMultiBlockLifecycle.isLoaded(level, pos);
	}

	@Override
	public boolean sameSpace(BlockPos origin, BlockPos pos) {
		return SubLevelCompat.sameSpace(level, origin, pos);
	}

	@Override
	public ComputerStructureScanner.CellKind cellAt(BlockPos pos) {
		return classify(level.getBlockState(pos), state -> state.is(ComputerBlockTags.INTERNAL_COMPONENTS));
	}

	@Override
	public @Nullable ComputerStructureScanner.ComputerObservation computerAt(BlockPos pos) {
		if (!CBMultiBlockLifecycle.isLoaded(level, pos)
			|| !SubLevelCompat.sameSpace(level, seed, pos)) return null;
		BlockEntity blockEntity = SubLevelCompat.getLoadedBlockEntity(level, pos);
		if (!(blockEntity instanceof ComputerBlockEntity computer)) return null;
		if (!computer.persistenceAvailable() || computer.computerId().isEmpty())
			return new ComputerStructureScanner.ComputerObservation(null, null,
				ComputerStructureScanner.ProfileLoadState.CORRUPT, null, null);

		ComputerProfile profile = computer.installedProfile().orElse(null);
		ComputerStructureScanner.ProfileLoadState profileState;
		if (profile != null) profileState = ComputerStructureScanner.ProfileLoadState.VALID;
		else if (computer.hasResidentSource())
			profileState = ComputerStructureScanner.ProfileLoadState.CORRUPT;
		else profileState = ComputerStructureScanner.ProfileLoadState.EMPTY;
		SpaceAddress address = SpaceAddress.capture(level, pos);
		return new ComputerStructureScanner.ComputerObservation(
			computer.computerId().orElseThrow(), computer.computerStructureMemberId().orElse(null),
			profileState, profile, address);
	}

	static ComputerStructureScanner.CellKind classify(BlockState state,
		Predicate<BlockState> internalTag) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(internalTag, "internalTag");
		if (state.getBlock() instanceof ComputerCasingBlock)
			return ComputerStructureScanner.CellKind.CASING;
		if (state.getBlock() instanceof ComputerBlock)
			return ComputerStructureScanner.CellKind.COMPUTER;
		if (state.isAir()) return ComputerStructureScanner.CellKind.AIR;
		if (internalTag.test(state)) return ComputerStructureScanner.CellKind.ALLOWED_INTERNAL;
		return ComputerStructureScanner.CellKind.ILLEGAL;
	}
}
