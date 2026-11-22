package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerCardProjection.AggregateState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorProjection.Status;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot.ProcessEvidence;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot.PublishedSnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.ExecutionState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.TreeNode;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.TreeNodeKind;

/** Pure composition over one combined immutable monitor capture. */
public final class ComputerMonitorProjectionService {
	private static final Comparator<ClusterEngineState.MemberSnapshot> MEMBER_ORDER =
		Comparator.comparing(ClusterEngineState.MemberSnapshot::computerId);
	private static final Comparator<ProcessRecord> PROCESS_ORDER = Comparator
		.comparing(ProcessRecord::owningComputerId)
		.thenComparingInt(ProcessRecord::owningSlot)
		.thenComparing(ProcessRecord::processId);

	private final SnapshotLookup snapshots;
	private final int panelRowLimit;

	public ComputerMonitorProjectionService(SnapshotLookup snapshots, int panelRowLimit) {
		this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
		if (panelRowLimit <= 0 || panelRowLimit > ComputerMonitorProjection.MAX_WIRE_ROWS)
			throw new IllegalArgumentException("invalid Computer monitor row limit");
		this.panelRowLimit = panelRowLimit;
	}

	public ComputerMonitorProjection project(FactoryPanelAuthorityValidator.ViewValidation authority,
		Query query) {
		authority = Objects.requireNonNull(authority, "authority");
		query = Objects.requireNonNull(query, "query");
		var request = authority.request();
		if (request.view() != FactoryPanelView.COMPUTER_MONITOR)
			throw new IllegalArgumentException("authority does not name the Computer monitor");
		ComputerMonitorViewRequest monitor = request.monitorRequest().orElseThrow(() ->
			new IllegalArgumentException("Computer monitor request lacks viewport state"));
		int activeOffset = monitor.activeOffset();
		if (request.limit() > panelRowLimit
			|| activeOffset != request.offset()
			|| query.requestedRows(monitor.includeTreeContext()) > request.limit())
			throw new IllegalArgumentException("monitor viewports exceed the authorized wire budget");
		Optional<UUID> primarySelection = monitor.activeSelection();
		if (!request.selection().equals(primarySelection))
			throw new IllegalArgumentException("monitor selection is outside the authority proof");
		int queryActiveOffset = switch (monitor.activeViewport()) {
			case COMPUTERS -> query.computers().offset();
			case SLOTS -> query.slots().offset();
			case GOAL_TREE -> query.tree().offset();
		};
		if (!monitor.selectedComputerId().equals(query.selectedComputerId())
			|| !monitor.selectedProcessId().equals(query.selectedProcessId())
			|| queryActiveOffset != request.offset())
			throw new IllegalArgumentException("monitor query differs from authorized navigation");
		query.validateSelectionShape();

		UUID clusterId = request.opening().clusterId();
		PublishedSnapshot snapshot = snapshots.capture(clusterId).orElseThrow(() ->
			new IllegalStateException("formed Cluster monitor snapshot is unavailable"));
		validatePublication(snapshot);
		ClusterEngineState state = snapshot.engine();
		if (!state.clusterId().equals(clusterId)
			|| !state.epochId().equals(request.opening().epochId()))
			throw new IllegalStateException("Cluster monitor snapshot is outside the menu epoch");
		if (request.knownRevision().isPresent()
			&& request.knownRevision().orElseThrow() != snapshot.viewRevision())
			return ComputerMonitorProjection.stale(state.clusterId(), state.epochId(),
				snapshot.viewRevision(), request.limit());

		List<ClusterEngineState.MemberSnapshot> members = state.members().stream()
			.sorted(MEMBER_ORDER).toList();
		Viewport computerViewport = clamp(query.computers(), members.size());
		List<ComputerCardProjection> cards = cards(members, state.processes(),
			snapshot.residentLevels(), computerViewport);
		Optional<UUID> selectedComputer = query.selectedComputerId()
			.filter(selected -> state.member(selected) != null);
		int slotOffset = 0;
		int totalSlots = 0;
		List<ComputerSlotProjection> slots = List.of();
		if (selectedComputer.isPresent()) {
			ClusterEngineState.MemberSnapshot member = state.member(selectedComputer.orElseThrow());
			totalSlots = member.profile().processSlots();
			Viewport slotViewport = clamp(query.slots(), totalSlots);
			slotOffset = slotViewport.offset();
			slots = slots(member, state, snapshot, slotViewport);
		}

		Optional<GoalTreeProjection> tree = Optional.empty();
		Optional<UUID> selectedProcess = query.selectedProcessId().filter(processId -> {
			ProcessRecord process = state.processes().get(processId);
			return process != null && selectedComputer.isPresent()
				&& process.owningComputerId().equals(selectedComputer.orElseThrow());
		});
		if (selectedProcess.isPresent()) {
			ProcessRecord selected = state.processes().get(selectedProcess.orElseThrow());
			GoalRecord goal = state.goals().get(selected.goalId());
			if (goal != null) {
				List<TreeNode> allNodes = treeNodes(state, goal, snapshot);
				Viewport treeViewport = clamp(query.tree(), allNodes.size());
				int treeOffset = treeViewport.offset();
				int end = Math.min(allNodes.size(), Math.addExact(treeOffset, treeViewport.limit()));
				Optional<TreeNode> parentContext = monitor.includeTreeContext()
					? parentProcessContext(allNodes, treeOffset, end) : Optional.empty();
				var display = snapshot.goals().get(goal.goalId());
				tree = Optional.of(new GoalTreeProjection(goal.goalId(), display.output(),
					display.count(), treeOffset, allNodes.size(), parentContext,
					allNodes.subList(treeOffset, end)));
			}
		}

		return new ComputerMonitorProjection(state.clusterId(), state.epochId(),
			snapshot.viewRevision(), Status.CURRENT, computerViewport.offset(), members.size(),
			cards, selectedComputer, slotOffset, totalSlots, slots, selectedProcess,
			tree, request.limit());
	}

	/** Projects the complete Monitor navigation embedded in the authorized view request. */
	public ComputerMonitorProjection project(FactoryPanelAuthorityValidator.ViewValidation authority) {
		ComputerMonitorViewRequest request = Objects.requireNonNull(authority, "authority")
			.request().monitorRequest().orElseThrow(() ->
				new IllegalArgumentException("Computer monitor request lacks viewport state"));
		return project(authority, new Query(request.selectedComputerId(), request.selectedProcessId(),
			viewport(request.computers()), viewport(request.slots()), viewport(request.goalTree())));
	}



	private static Viewport viewport(ComputerMonitorViewRequest.Viewport viewport) {
		return new Viewport(viewport.offset(), viewport.limit());
	}

	private static List<ComputerCardProjection> cards(
		List<ClusterEngineState.MemberSnapshot> members, Map<UUID, ProcessRecord> processes,
		Map<UUID, Integer> residentLevels, Viewport viewport) {
		int offset = checkedOffset(viewport, members.size(), "Computer card");
		int end = Math.min(members.size(), Math.addExact(offset, viewport.limit()));
		ArrayList<ComputerCardProjection> result = new ArrayList<>(end - offset);
		for (int index = offset; index < end; index++) {
			ClusterEngineState.MemberSnapshot member = members.get(index);
			List<ProcessRecord> owned = processes.values().stream()
				.filter(process -> process.owningComputerId().equals(member.computerId())).toList();
			result.add(new ComputerCardProjection(member.computerId(), member.profile().profession(),
				residentLevels.get(member.computerId()), owned.size(),
				member.profile().processSlots(), aggregate(member, owned), owned.size()));
		}
		return List.copyOf(result);
	}

	private static List<ComputerSlotProjection> slots(ClusterEngineState.MemberSnapshot member,
		ClusterEngineState state, PublishedSnapshot snapshot, Viewport viewport) {
		int offset = checkedOffset(viewport, member.profile().processSlots(), "Computer slot");
		int end = Math.min(member.profile().processSlots(), Math.addExact(offset, viewport.limit()));
		Map<Integer, ProcessRecord> occupied = new LinkedHashMap<>();
		state.processes().values().stream()
			.filter(process -> process.owningComputerId().equals(member.computerId()))
			.forEach(process -> occupied.put(process.owningSlot(), process));
		ArrayList<ComputerSlotProjection> result = new ArrayList<>(end - offset);
		for (int slot = offset; slot < end; slot++) {
			ProcessRecord process = occupied.get(slot);
			if (process == null) {
				result.add(ComputerSlotProjection.empty(slot));
				continue;
			}
			GoalRecord goal = state.goals().get(process.goalId());
			String output = goal == null ? "" : snapshot.goals().get(goal.goalId()).output();
			ProcessEvidence display = snapshot.processes().get(process.processId());
			result.add(ComputerSlotProjection.occupied(slot, process.processId(), process.kind(),
				process.goalId(), output, process.state(), Optional.ofNullable(process.waitReason()),
				display.remoteState(), goal != null));
		}
		return List.copyOf(result);
	}

	private static List<TreeNode> treeNodes(ClusterEngineState state, GoalRecord goal,
		PublishedSnapshot snapshot) {
		var goalDisplay = snapshot.goals().get(goal.goalId());
		ArrayList<TreeNode> nodes = new ArrayList<>();
		nodes.add(node(0, TreeNodeKind.GOAL, goalDisplay.output(), goalDisplay.count(), "",
			Optional.empty(), execution(goal.state()), Optional.empty(), -1,
			Optional.ofNullable(goal.waitReason()), Optional.empty()));
		state.processes().values().stream().filter(process -> process.goalId().equals(goal.goalId()))
			.sorted(PROCESS_ORDER).forEach(process -> appendProcess(nodes, state, process,
				goalDisplay.output(), goalDisplay.count(),
				snapshot.processes().get(process.processId())));
		return List.copyOf(nodes);
	}

	private static void appendProcess(List<TreeNode> nodes, ClusterEngineState state,
		ProcessRecord process, String output, long count, ProcessEvidence display) {
		ClusterEngineState.MemberSnapshot member = state.member(process.owningComputerId());
		ExecutionState execution = member.loaded() ? execution(process.state()) : ExecutionState.FAULT;
		Optional<String> assigned = Optional.of(
			ComputerCardProjection.shortId(process.owningComputerId()));
		nodes.add(node(1, TreeNodeKind.PROCESS, output, count, "", Optional.empty(),
			execution, assigned, process.owningSlot(), Optional.ofNullable(process.waitReason()),
			Optional.of("kind=" + process.kind() + ";remote=" + display.remoteState())));
		if (!display.libraryDisplay().isEmpty())
			nodes.add(node(2, TreeNodeKind.LIBRARY, output, count, display.libraryDisplay(),
				Optional.empty(), execution, assigned, process.owningSlot(), Optional.empty(),
				Optional.empty()));
		display.createAddress().ifPresent(address ->
			nodes.add(node(2, TreeNodeKind.CREATE_ADDRESS, output, count, "",
				Optional.of(address), execution, assigned, process.owningSlot(), Optional.empty(),
				Optional.empty())));
		display.inventory().stream().sorted(Comparator.comparing(
			ComputerMonitorSnapshot.InventoryEvidence::output).thenComparingLong(
				ComputerMonitorSnapshot.InventoryEvidence::count)).forEach(leaf ->
				nodes.add(new TreeNode(2, TreeNodeKind.INVENTORY, leaf.output(), leaf.count(), "",
					Optional.empty(), execution, assigned, process.owningSlot(), Optional.empty(),
					Optional.empty(), leaf.item())));
		if (display.unresolvedSearch())
			nodes.add(node(2, TreeNodeKind.UNRESOLVED_SEARCH, output, count, "",
				Optional.empty(), execution, assigned, process.owningSlot(),
				Optional.ofNullable(process.waitReason()), Optional.empty()));
		if (display.listener() || process.kind() == ProcessKind.REMOTE_LISTENER
			|| process.kind() == ProcessKind.RADIO_LISTENER)
			nodes.add(node(2, TreeNodeKind.LISTENER, output, count, "", Optional.empty(),
				execution, assigned, process.owningSlot(), Optional.empty(), Optional.empty()));
		if (display.handoff())
			nodes.add(node(2, TreeNodeKind.HANDOFF, output, count, "", Optional.empty(),
				execution, assigned, process.owningSlot(), Optional.empty(), Optional.empty()));
		if (display.agentQueuePosition().isPresent()) {
			String queue = "queue=" + display.agentQueuePosition().orElseThrow();
			nodes.add(node(2, TreeNodeKind.AGENT_QUEUE, output, count, "", Optional.empty(),
				execution, assigned, process.owningSlot(), Optional.empty(), Optional.of(queue)));
		}
		display.logisticsAlias().ifPresent(alias ->
			nodes.add(node(2, TreeNodeKind.LOGISTICS_ALIAS, output, count, "",
				Optional.empty(), execution, assigned, process.owningSlot(), Optional.empty(),
				Optional.of(alias))));
	}

	private static TreeNode node(int depth, TreeNodeKind kind, String output, long count,
		String library, Optional<SpaceAddress> address, ExecutionState state,
		Optional<String> assigned, int slot, Optional<WaitReason> wait, Optional<String> detail) {
		return new TreeNode(depth, kind, output, count, library, address, state, assigned, slot,
			wait, detail);
	}

	private static int checkedOffset(Viewport viewport, int total, String name) {
		if (viewport.offset() > total)
			throw new IllegalArgumentException(name + " offset exceeds total");
		return viewport.offset();
	}

	private static Viewport clamp(Viewport viewport, int total) {
		int maximum = Math.max(0, total - viewport.limit());
		return new Viewport(Math.min(viewport.offset(), maximum), viewport.limit());
	}

	private static AggregateState aggregate(ClusterEngineState.MemberSnapshot member,
		List<ProcessRecord> processes) {
		if (!member.loaded()) return AggregateState.FAULT;
		if (processes.stream().anyMatch(process -> process.state() == ProcessState.RUNNABLE))
			return AggregateState.RUN;
		if (processes.stream().anyMatch(process -> process.state() == ProcessState.WAITING))
			return AggregateState.SLP;
		return AggregateState.IDLE;
	}

	private static ExecutionState execution(ProcessState state) {
		return switch (state) {
			case RUNNABLE -> ExecutionState.RUN;
			case WAITING -> ExecutionState.SLP;
			case TERMINAL -> ExecutionState.IDLE;
		};
	}

	private static ExecutionState execution(GoalState state) {
		return switch (state) {
			case RUNNABLE -> ExecutionState.RUN;
			case WAITING -> ExecutionState.SLP;
			case TERMINAL -> ExecutionState.IDLE;
		};
	}

	private static Optional<TreeNode> parentProcessContext(List<TreeNode> nodes, int offset,
		int end) {
		if (offset > 0 && offset < end && nodes.get(offset).depth() == 2) {
			for (int index = offset - 1; index >= 0; index--)
				if (nodes.get(index).kind() == TreeNodeKind.PROCESS)
					return Optional.of(nodes.get(index));
		}
		return Optional.empty();
	}

	private static void validatePublication(PublishedSnapshot snapshot) {
		if (!snapshot.isCurrentPublication())
			throw new IllegalStateException("monitor lookup returned a superseded publication");
	}

	@FunctionalInterface
	public interface SnapshotLookup {
		/** Returns the sequence publication created from one same-lock-domain combined capture. */
		Optional<PublishedSnapshot> capture(UUID clusterId);
	}

	public record Viewport(int offset, int limit) {
		public Viewport {
			if (offset < 0 || limit < 0 || (long) offset + limit > Integer.MAX_VALUE)
				throw new IllegalArgumentException("invalid monitor viewport");
		}

		private boolean isZero() {
			return offset == 0 && limit == 0;
		}
	}

	public record Query(Optional<UUID> selectedComputerId, Optional<UUID> selectedProcessId,
		Viewport computers, Viewport slots, Viewport tree) {
		public Query {
			selectedComputerId = Objects.requireNonNull(selectedComputerId, "selectedComputerId");
			selectedProcessId = Objects.requireNonNull(selectedProcessId, "selectedProcessId");
			computers = Objects.requireNonNull(computers, "computers");
			slots = Objects.requireNonNull(slots, "slots");
			tree = Objects.requireNonNull(tree, "tree");
		}

		private int requestedRows(boolean includeTreeContext) {
			long contextReserve = includeTreeContext ? 1 : 0;
			long total = (long) computers.limit() + slots.limit() + tree.limit() + contextReserve;
			if (total > Integer.MAX_VALUE)
				throw new IllegalArgumentException("monitor viewport budget overflow");
			return (int) total;
		}

		private void validateSelectionShape() {
			if (selectedProcessId.isPresent() && selectedComputerId.isEmpty())
				throw new IllegalArgumentException("a process selection requires its Computer");
			if (selectedComputerId.isEmpty() && !slots.isZero())
				throw new IllegalArgumentException("slot viewport requires a selected Computer");
			if (selectedProcessId.isEmpty() && !tree.isZero())
				throw new IllegalArgumentException("tree viewport requires a selected process");
		}
	}
}
