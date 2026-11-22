package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;

/** One explicit page of an owning Goal's deterministic, command-free expansion tree. */
public record GoalTreeProjection(UUID goalId, String output, long count, int offset,
	int totalNodes, Optional<TreeNode> parentProcessContext, List<TreeNode> nodes) {
	public GoalTreeProjection(UUID goalId, String output, long count, int offset,
		int totalNodes, List<TreeNode> nodes) {
		this(goalId, output, count, offset, totalNodes, Optional.empty(), nodes);
	}

	public GoalTreeProjection {
		goalId = Objects.requireNonNull(goalId, "goalId");
		output = bounded(output, "output");
		nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
		parentProcessContext = Objects.requireNonNull(parentProcessContext,
			"parentProcessContext");
		if (count < 0 || offset < 0 || totalNodes <= 0 || offset > totalNodes
			|| nodes.size() > totalNodes - offset)
			throw new IllegalArgumentException("invalid Goal tree page");
		if (offset == 0 && parentProcessContext.isPresent()
			|| parentProcessContext.isPresent()
				&& (parentProcessContext.orElseThrow().kind() != TreeNodeKind.PROCESS
					|| nodes.isEmpty() || nodes.getFirst().depth() != 2))
			throw new IllegalArgumentException("invalid Goal tree parent process context");
		TreeNode processContext = parentProcessContext.orElse(null);
		for (int index = 0; index < nodes.size(); index++) {
			boolean rootPosition = offset == 0 && index == 0;
			if ((nodes.get(index).kind() == TreeNodeKind.GOAL) != rootPosition)
				throw new IllegalArgumentException("Goal root is outside its canonical tree position");
			if (nodes.get(index).kind() == TreeNodeKind.PROCESS) {
				processContext = nodes.get(index);
			} else if (nodes.get(index).depth() == 2) {
				if (processContext != null && (!nodes.get(index).assignedComputerShortId()
					.equals(processContext.assignedComputerShortId())
					|| nodes.get(index).assignedSlot() != processContext.assignedSlot()))
					throw new IllegalArgumentException("Goal leaf is outside its process assignment");
			}
		}
	}

	public int wireRowCount() {
		return nodes.size() + (parentProcessContext.isPresent() ? 1 : 0);
	}

	public boolean hasMore() {
		return offset + nodes.size() < totalNodes;
	}

	public record TreeNode(int depth, TreeNodeKind kind, String output, long count,
		String libraryDisplay, Optional<SpaceAddress> createAddress, ExecutionState executionState,
		Optional<String> assignedComputerShortId, int assignedSlot,
		Optional<WaitReason> waitReason, Optional<String> detail,
		Optional<com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey> inventoryItem) {
		public TreeNode(int depth, TreeNodeKind kind, String output, long count, String libraryDisplay,
			Optional<SpaceAddress> createAddress, ExecutionState executionState,
			Optional<String> assignedComputerShortId, int assignedSlot,
			Optional<WaitReason> waitReason, Optional<String> detail) {
			this(depth, kind, output, count, libraryDisplay, createAddress, executionState,
				assignedComputerShortId, assignedSlot, waitReason, detail, Optional.empty());
		}
		public TreeNode {
			kind = Objects.requireNonNull(kind, "kind");
			inventoryItem = Objects.requireNonNull(inventoryItem, "inventoryItem");
			if (inventoryItem.isPresent() && kind != TreeNodeKind.INVENTORY)
				throw new IllegalArgumentException("only inventory leaves carry observed item components");
			output = bounded(output, "output");
			libraryDisplay = bounded(libraryDisplay, "libraryDisplay");
			createAddress = Objects.requireNonNull(createAddress, "createAddress");
			executionState = Objects.requireNonNull(executionState, "executionState");
			assignedComputerShortId = Objects.requireNonNull(assignedComputerShortId,
				"assignedComputerShortId");
			waitReason = Objects.requireNonNull(waitReason, "waitReason");
			detail = Objects.requireNonNull(detail, "detail").map(value -> bounded(value, "detail"));
			assignedComputerShortId = assignedComputerShortId.map(value -> bounded(value,
				"assignedComputerShortId"));
			if (depth < 0 || count < 0 || assignedSlot < -1
				|| assignedComputerShortId.isEmpty() != (assignedSlot == -1))
				throw new IllegalArgumentException("invalid Goal tree node");
			if (assignedComputerShortId.isPresent()
				&& !ComputerCardProjection.isCanonicalShortId(
					assignedComputerShortId.orElseThrow()))
				throw new IllegalArgumentException("assigned Computer ID is not canonical short form");
			validateBranch(depth, kind, libraryDisplay, createAddress,
				assignedComputerShortId, waitReason, detail);
		}

		private static void validateBranch(int depth, TreeNodeKind kind, String library,
			Optional<SpaceAddress> address, Optional<String> assigned, Optional<WaitReason> wait,
			Optional<String> detail) {
			boolean root = kind == TreeNodeKind.GOAL;
			if (depth != (root ? 0 : kind == TreeNodeKind.PROCESS ? 1 : 2)
				|| assigned.isPresent() == root)
				throw new IllegalArgumentException("invalid typed Goal tree depth/assignment");
			boolean hasLibrary = !library.isEmpty();
			boolean hasAddress = address.isPresent();
			boolean hasDetail = detail.filter(value -> !value.isEmpty()).isPresent();
			boolean valid = switch (kind) {
				case GOAL -> !hasLibrary && !hasAddress && !hasDetail;
				case PROCESS -> !hasLibrary && !hasAddress && hasDetail;
				case LIBRARY -> hasLibrary && !hasAddress && !hasDetail && wait.isEmpty();
				case CREATE_ADDRESS -> !hasLibrary && hasAddress && !hasDetail && wait.isEmpty();
				case INVENTORY, LISTENER, HANDOFF -> !hasLibrary && !hasAddress
					&& !hasDetail && wait.isEmpty();
				case UNRESOLVED_SEARCH -> !hasLibrary && !hasAddress && !hasDetail;
				case AGENT_QUEUE, LOGISTICS_ALIAS -> !hasLibrary && !hasAddress
					&& hasDetail && wait.isEmpty();
			};
			if (!valid) throw new IllegalArgumentException("invalid fields for Goal tree branch " + kind);
		}
	}

	public enum TreeNodeKind {
		GOAL,
		PROCESS,
		LIBRARY,
		CREATE_ADDRESS,
		INVENTORY,
		UNRESOLVED_SEARCH,
		LISTENER,
		HANDOFF,
		AGENT_QUEUE,
		LOGISTICS_ALIAS
	}

	public enum ExecutionState {
		IDLE,
		RUN,
		SLP,
		FAULT
	}

	static String bounded(String value, String name) {
		value = Objects.requireNonNull(value, name);
		if (value.length() > WorldFact.MAX_TEXT_LENGTH)
			throw new IllegalArgumentException(name + " exceeds text bound");
		return ComputerMonitorSnapshot.safeText(value);
	}
}
