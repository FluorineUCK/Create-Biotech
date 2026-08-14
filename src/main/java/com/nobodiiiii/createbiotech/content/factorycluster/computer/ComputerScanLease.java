package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Ephemeral, member-owned scheduling cohort. It contains no persisted or network state and never
 * retains a scan result; it only lets the exact loaded members agree which UUID performs a scan.
 */
final class ComputerScanLease {
	private final Set<ComputerBlockEntity> members = new LinkedHashSet<>();
	private boolean dirty = true;
	private long lastScanTick = Long.MIN_VALUE;

	private ComputerScanLease(List<ComputerBlockEntity> exactMembers) {
		reconcile(exactMembers);
	}

	static void tick(ComputerBlockEntity caller, long now,
		ComputerTopologyController.WorldAccess world, ComputerStructureScanner.Limits limits) {
		Objects.requireNonNull(caller, "caller");
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(limits, "limits");
		ComputerScanLease lease = caller.scanLease();
		if (lease == null) {
			if (!caller.beginDiscoveryAttempt(now)) return;
			Rebuild rebuild = rebuildPersisted(caller, world);
			lease = rebuild.lease();
			if (lease != null && rebuild.partial()) {
				lease.dirty = false;
				lease.lastScanTick = now;
				ComputerTopologyController.fanAvailability(lease.memberList(),
					ComputerAvailabilityReason.PARTIAL_UNLOADED);
				caller.consumeTopologyDirty();
				return;
			}
			if (lease == null) {
				discover(caller, now, world, limits);
				caller.consumeTopologyDirty();
				return;
			}
		}

		if (caller.topologyDirty()) lease.markDirty();
		caller.consumeTopologyDirty();
		ComputerBlockEntity owner = lease.owner();
		if (owner != caller) return;
		if (!lease.dirty && lease.lastScanTick != Long.MIN_VALUE
			&& now >= lease.lastScanTick
			&& now - lease.lastScanTick < ComputerBlockEntity.TOPOLOGY_SCAN_INTERVAL_TICKS) return;
		lease.refreshOwner(owner, now, world, limits);
	}

	private static void discover(ComputerBlockEntity caller, long now,
		ComputerTopologyController.WorldAccess world, ComputerStructureScanner.Limits limits) {
		ComputerTopologyController.RefreshOutcome discovered =
			ComputerTopologyController.refresh(caller, world, limits);
		if (discovered.members().isEmpty()) return;
		ComputerScanLease lease = new ComputerScanLease(discovered.members());
		ComputerTopologyController.RefreshOutcome finalOutcome = discovered;
		ComputerBlockEntity owner = lease.owner();
		if (!discovered.committed() && discovered.reason() == null && owner != caller) {
			finalOutcome = ComputerTopologyController.refresh(owner, world, limits);
			if (!finalOutcome.members().isEmpty()) lease.reconcile(finalOutcome.members());
		}
		lease.lastScanTick = now;
		lease.dirty = false;
		lease.fanFailure(finalOutcome);
	}

	private void refreshOwner(ComputerBlockEntity owner, long now,
		ComputerTopologyController.WorldAccess world, ComputerStructureScanner.Limits limits) {
		ComputerTopologyController.RefreshOutcome outcome =
			ComputerTopologyController.refresh(owner, world, limits);
		lastScanTick = now;
		dirty = false;
		if (!outcome.members().isEmpty()) reconcile(outcome.members());
		fanFailure(outcome);
	}

	private void fanFailure(ComputerTopologyController.RefreshOutcome outcome) {
		if (!outcome.committed() && outcome.reason() != null)
			ComputerTopologyController.fanAvailability(memberList(), outcome.reason());
	}

	private static Rebuild rebuildPersisted(ComputerBlockEntity caller,
		ComputerTopologyController.WorldAccess world) {
		ComputerStructureRecord record = caller.currentStructureRecord().orElse(null);
		if (record == null) return Rebuild.discovery();
		boolean partial = false;
		for (ChunkPos chunk : record.snapshot().containingChunks())
			if (!world.isChunkLoaded(chunk)) {
				partial = true;
				break;
			}

		List<ComputerBlockEntity> exact = new ArrayList<>();
		for (ComputerStructureNode node : record.snapshot().nodes()) {
			BlockPos pos = node.address().localPos();
			if (!world.isLoaded(pos)) {
				if (!partial) return Rebuild.discovery();
				continue;
			}
			if (!world.sameSpace(caller.getBlockPos(), pos)) return Rebuild.discovery();
			ComputerBlockEntity computer = world.loadedComputer(pos);
			if (computer == null || !world.address(pos).equals(node.address())
				|| computer.computerId().filter(node.computerId()::equals).isEmpty()
				|| computer.computerStructureMemberId()
					.filter(record.computerStructureMemberId()::equals).isEmpty()
				|| computer.currentStructureRecord().filter(record::equals).isEmpty())
				return Rebuild.discovery();
			exact.add(computer);
		}
		if (!exact.contains(caller)) return Rebuild.discovery();
		return new Rebuild(new ComputerScanLease(exact), partial);
	}

	void markDirty() { dirty = true; }

	void detach(ComputerBlockEntity member) {
		if (members.remove(member)) dirty = true;
		member.clearScanLease(this);
	}

	private void reconcile(List<ComputerBlockEntity> exactMembers) {
		Set<ComputerBlockEntity> next = new LinkedHashSet<>(exactMembers);
		for (ComputerBlockEntity old : List.copyOf(members))
			if (!next.contains(old)) {
				members.remove(old);
				old.clearScanLease(this);
				old.markTopologyDirty();
			}
		for (ComputerBlockEntity member : next) {
			ComputerScanLease previous = member.scanLease();
			if (previous != null && previous != this) previous.detach(member);
			members.add(member);
			member.attachScanLease(this);
		}
	}

	private ComputerBlockEntity owner() {
		return members.stream().min(Comparator.comparing(computer -> computer.computerId()
			.orElseThrow())).orElseThrow();
	}

	private List<ComputerBlockEntity> memberList() { return List.copyOf(members); }

	private record Rebuild(@Nullable ComputerScanLease lease, boolean partial) {
		private static Rebuild discovery() { return new Rebuild(null, false); }
	}
}
