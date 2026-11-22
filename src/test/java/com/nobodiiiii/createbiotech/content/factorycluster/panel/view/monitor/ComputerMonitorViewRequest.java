package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** Complete retained Monitor navigation with one explicitly active viewport. */
public record ComputerMonitorViewRequest(ActiveViewport activeViewport,
	Optional<UUID> selectedComputerId, Optional<UUID> selectedProcessId,
	Viewport computers, Viewport slots, Viewport goalTree, boolean includeTreeContext) {
	public ComputerMonitorViewRequest(ActiveViewport activeViewport,
		Optional<UUID> selectedComputerId, Optional<UUID> selectedProcessId,
		Viewport computers, Viewport slots, Viewport goalTree) {
		this(activeViewport, selectedComputerId, selectedProcessId, computers, slots, goalTree,
			goalTree.offset() > 0 && goalTree.limit() > 0);
	}

	public ComputerMonitorViewRequest {
		activeViewport = Objects.requireNonNull(activeViewport, "activeViewport");
		selectedComputerId = Objects.requireNonNull(selectedComputerId, "selectedComputerId");
		selectedProcessId = Objects.requireNonNull(selectedProcessId, "selectedProcessId");
		computers = Objects.requireNonNull(computers, "computers");
		slots = Objects.requireNonNull(slots, "slots");
		goalTree = Objects.requireNonNull(goalTree, "goalTree");
		if (selectedProcessId.isPresent() && selectedComputerId.isEmpty())
			throw new IllegalArgumentException("process selection requires a Computer");
		if (Math.addExact(computers.limit(), Math.addExact(slots.limit(), goalTree.limit()))
			+ (includeTreeContext ? 1 : 0)
			> ComputerMonitorProjection.MAX_WIRE_ROWS)
			throw new IllegalArgumentException("monitor request exceeds wire row bound");
		if (includeTreeContext && (goalTree.offset() == 0 || goalTree.limit() == 0))
			throw new IllegalArgumentException("tree context requires a continued tree page");
	}

	public int activeOffset() {
		return switch (activeViewport) {
			case COMPUTERS -> computers.offset();
			case SLOTS -> slots.offset();
			case GOAL_TREE -> goalTree.offset();
		};
	}

	public Optional<UUID> activeSelection() {
		return switch (activeViewport) {
			case COMPUTERS -> selectedComputerId;
			case SLOTS, GOAL_TREE -> selectedProcessId.isPresent()
				? selectedProcessId : selectedComputerId;
		};
	}

	public int requestedRows() {
		return Math.addExact(computers.limit(), Math.addExact(slots.limit(), goalTree.limit()))
			+ (includeTreeContext ? 1 : 0);
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeEnum(activeViewport);
		writeUuid(buffer, selectedComputerId); writeUuid(buffer, selectedProcessId);
		computers.write(buffer); slots.write(buffer); goalTree.write(buffer);
		buffer.writeBoolean(includeTreeContext);
	}

	public static ComputerMonitorViewRequest read(RegistryFriendlyByteBuf buffer) {
		return new ComputerMonitorViewRequest(buffer.readEnum(ActiveViewport.class),
			readUuid(buffer), readUuid(buffer), Viewport.read(buffer), Viewport.read(buffer),
			Viewport.read(buffer), buffer.readBoolean());
	}

	private static void writeUuid(RegistryFriendlyByteBuf buffer, Optional<UUID> value) {
		buffer.writeBoolean(value.isPresent()); value.ifPresent(buffer::writeUUID);
	}
	private static Optional<UUID> readUuid(RegistryFriendlyByteBuf buffer) {
		return buffer.readBoolean() ? Optional.of(buffer.readUUID()) : Optional.empty();
	}

	public enum ActiveViewport { COMPUTERS, SLOTS, GOAL_TREE }

	public record Viewport(int offset, int limit) {
		public Viewport {
			if (offset < 0 || limit < 0 || (long) offset + limit > Integer.MAX_VALUE)
				throw new IllegalArgumentException("invalid monitor viewport");
		}
		private void write(RegistryFriendlyByteBuf buffer) {
			buffer.writeVarInt(offset); buffer.writeVarInt(limit);
		}
		private static Viewport read(RegistryFriendlyByteBuf buffer) {
			return new Viewport(buffer.readVarInt(), buffer.readVarInt());
		}
	}
}
