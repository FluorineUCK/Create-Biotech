package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.HistoricalProductionHost;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellBlock;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
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
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ComputerBlock) {
			if (!hasCompletePair(pos, state)) return ComputerStructureScanner.CellKind.ILLEGAL;
			return state.getValue(ComputerBlock.HALF)
				== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
					? ComputerStructureScanner.CellKind.COMPUTER
					: ComputerStructureScanner.CellKind.ALLOWED_INTERNAL;
		}
		return classify(state, candidate -> candidate.is(ComputerBlockTags.INTERNAL_COMPONENTS));
	}

	@Override
	public @Nullable ComputerStructureScanner.ComputerObservation computerAt(BlockPos pos) {
		if (!CBMultiBlockLifecycle.isLoaded(level, pos)
			|| !SubLevelCompat.sameSpace(level, seed, pos)) return null;
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ComputerBlock)
			|| state.getValue(ComputerBlock.HALF)
				!= net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
			|| !hasCompletePair(pos, state)) return null;
		BlockEntity blockEntity = SubLevelCompat.getLoadedBlockEntity(level, pos);
		if (!(blockEntity instanceof HistoricalProductionHost physical)) return null;
		var computer = physical.production();
		if (!computer.persistenceAvailable() || computer.computerId().isEmpty())
			return new ComputerStructureScanner.ComputerObservation(null, null,
				ComputerStructureScanner.ProfileLoadState.CORRUPT, null, null);

		ProductionResourceProfile profile = computer.installedProfile().orElse(null);
		ComputerStructureScanner.ProfileLoadState profileState;
		if (profile != null) profileState = ComputerStructureScanner.ProfileLoadState.VALID;
		else if (computer.owner().hasResidentSource())
			profileState = ComputerStructureScanner.ProfileLoadState.CORRUPT;
		else profileState = ComputerStructureScanner.ProfileLoadState.EMPTY;
		SpaceAddress address = SpaceAddress.capture(level, pos);
		return new ComputerStructureScanner.ComputerObservation(
			computer.computerId().orElseThrow(), computer.computerStructureMemberId().orElse(null),
			profileState, profile, address);
	}

	private boolean hasCompletePair(BlockPos pos, BlockState state) {
		BlockPos lowerPos = ComputerBlock.lowerPos(state, pos);
		BlockPos upperPos = lowerPos.above();
		if (!CBMultiBlockLifecycle.isLoaded(level, lowerPos)
			|| !CBMultiBlockLifecycle.isLoaded(level, upperPos)
			|| !SubLevelCompat.sameSpace(level, seed, lowerPos)
			|| !SubLevelCompat.sameSpace(level, seed, upperPos)) return false;
		return ComputerBlock.isCompletePair(level.getBlockState(lowerPos),
			level.getBlockState(upperPos));
	}


	@Override public @Nullable ComputerStructureScanner.PanelObservation panelAt(BlockPos pos) {
		return null; // Material ports cannot attest to retired production membership.
	}

	static ComputerStructureScanner.CellKind classify(BlockState state,
		Predicate<BlockState> internalTag) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(internalTag, "internalTag");
		if (state.getBlock() instanceof ComputerShellBlock)
			return ComputerStructureScanner.CellKind.CASING;
		if (state.getBlock() instanceof ComputerBlock)
			return ComputerStructureScanner.CellKind.COMPUTER;
		if (state.isAir()) return ComputerStructureScanner.CellKind.AIR;
		if (internalTag.test(state)) return ComputerStructureScanner.CellKind.ALLOWED_INTERNAL;
		return ComputerStructureScanner.CellKind.ILLEGAL;
	}
}
