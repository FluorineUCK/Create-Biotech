package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class ComputerStructureLocator {
	private ComputerStructureLocator() {}

	static Optional<ComputerBlockEntity> findCoordinatorEntity(ServerLevel level,
		BlockPos casingPos, ComputerStructureScanner.Limits limits) {
		Objects.requireNonNull(level, "level");
		return findCoordinatorEntity(ComputerTopologyController.realWorld(level), casingPos, limits);
	}

	static Optional<ComputerBlockEntity> findCoordinatorEntity(
		ComputerTopologyController.WorldAccess world, BlockPos casingPos,
		ComputerStructureScanner.Limits limits) {
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(casingPos, "casingPos");
		Objects.requireNonNull(limits, "limits");
		BlockPos clicked = casingPos.immutable();
		int radius = limits.maxSize() - 1;
		List<Candidate> matches = new ArrayList<>();
		for (int x = clicked.getX() - radius; x <= clicked.getX() + radius; x++)
			for (int y = clicked.getY() - radius; y <= clicked.getY() + radius; y++)
				for (int z = clicked.getZ() - radius; z <= clicked.getZ() + radius; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!world.isLoaded(pos)) continue;
					if (!world.sameSpace(clicked, pos)) continue;
					ComputerBlockEntity computer = world.loadedComputer(pos);
					if (computer == null) continue;
					ComputerStructureRecord stored = computer.currentStructureRecord().orElse(null);
					UUID computerId = computer.computerId().orElse(null);
					if (stored == null || computerId == null || !computer.persistenceAvailable()) continue;
					ComputerStructureScanner.ScanResult scan = world.scan(pos, limits);
					if ((scan.state() != ComputerStructureScanner.State.VALID
						&& scan.state() != ComputerStructureScanner.State.VALID_NOT_READY)
						|| scan.snapshot() == null
						|| !stored.computerStructureMemberId().equals(scan.observedStructureMemberId())
						|| !stored.snapshot().equals(scan.snapshot())
						|| !scan.snapshot().casingPositions().contains(clicked)) continue;
					SpaceAddress seedAddress = world.address(pos);
					Candidate candidate = new Candidate(computer, computerId, seedAddress,
						stored.computerStructureMemberId(), stored.coordinatorId(), scan.snapshot());
					if (matches.stream().noneMatch(existing -> existing.sameStructure(candidate)))
						matches.add(candidate);
				}
		if (matches.size() != 1) return Optional.empty();
		Candidate candidate = matches.getFirst();
		ComputerStructureNode coordinatorNode = candidate.snapshot()
			.node(candidate.coordinatorId()).orElse(null);
		if (coordinatorNode == null) return Optional.empty();
		BlockPos coordinatorPos = coordinatorNode.address().localPos();

		if (!world.isLoaded(coordinatorPos) || !world.sameSpace(clicked, coordinatorPos))
			return Optional.empty();
		ComputerStructureScanner.ScanResult rescanned = world.scan(coordinatorPos, limits);
		if ((rescanned.state() != ComputerStructureScanner.State.VALID
			&& rescanned.state() != ComputerStructureScanner.State.VALID_NOT_READY)
			|| rescanned.snapshot() == null
			|| !candidate.snapshot().equals(rescanned.snapshot())
			|| !candidate.structureId().equals(rescanned.observedStructureMemberId())
			|| !rescanned.snapshot().casingPositions().contains(clicked)) return Optional.empty();
		if (!world.isLoaded(coordinatorPos) || !world.sameSpace(clicked, coordinatorPos))
			return Optional.empty();
		ComputerBlockEntity coordinator = world.resolveComputer(coordinatorNode.address());
		if (coordinator == null) return Optional.empty();
		ComputerStructureRecord current = coordinator.currentStructureRecord().orElse(null);
		if (current == null || coordinator.computerId().isEmpty()
			|| !coordinator.computerId().orElseThrow().equals(candidate.coordinatorId())
			|| !world.address(coordinatorPos).equals(coordinatorNode.address())
			|| !coordinator.computerStructureMemberId().orElse(new UUID(0, 0))
				.equals(candidate.structureId())
			|| !current.coordinatorId().equals(candidate.coordinatorId())
			|| !current.snapshot().equals(candidate.snapshot())) return Optional.empty();
		if (coordinator == candidate.seed()
			&& (!candidate.seedId().equals(coordinator.computerId().orElseThrow())
				|| !candidate.seedAddress().equals(world.address(candidate.seedAddress().localPos()))))
			return Optional.empty();
		return Optional.of(coordinator);
	}

	private record Candidate(ComputerBlockEntity seed, UUID seedId, SpaceAddress seedAddress,
		UUID structureId, UUID coordinatorId, ComputerStructureSnapshot snapshot) {
		boolean sameStructure(Candidate other) {
			return structureId.equals(other.structureId)
				&& seedAddress.dimension().equals(other.seedAddress.dimension())
				&& Objects.equals(seedAddress.subLevelId(), other.seedAddress.subLevelId())
				&& sameBounds(snapshot, other.snapshot);
		}
	}

	private static boolean sameBounds(ComputerStructureSnapshot first,
		ComputerStructureSnapshot second) {
		var a = first.bounds();
		var b = second.bounds();
		return a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ()
			&& a.maxX() == b.maxX() && a.maxY() == b.maxY() && a.maxZ() == b.maxZ();
	}
}
