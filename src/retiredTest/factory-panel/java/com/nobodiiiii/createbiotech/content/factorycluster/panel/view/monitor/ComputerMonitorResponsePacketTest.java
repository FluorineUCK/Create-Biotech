package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerCardProjection.AggregateState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorProjection.Status;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerSlotProjection.RemoteState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.ExecutionState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.TreeNode;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.TreeNodeKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;

class ComputerMonitorResponsePacketTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2), PANEL = id(3), SESSION = id(4);
	private static final UUID COMPUTER = id(5), PROCESS = id(6), GOAL = id(7);
	private static final SpaceAddress PANEL_ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(2, 64, 2));
	private static final SpaceAddress CREATE_ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(9, 70, 4));

	@BeforeAll
	static void bootstrapMinecraftRegistries() throws Exception {
		Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (type.getMethod("get").invoke(null) == null)
			type.getMethod("of", java.util.List.class, java.util.List.class,
				java.util.List.class, java.util.List.class, java.util.Map.class)
				.invoke(null, java.util.List.of(), java.util.List.of(), java.util.List.of(),
					java.util.List.of(), java.util.Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
		bootstrapped.setAccessible(true);
		bootstrapped.setBoolean(null, true);
	}

	@Test
	void packetRoundTripsTheExactSessionEpochRevisionAndBoundedMonitor() {
		FactoryPanelOpening opening = opening();
		ComputerCardProjection card = new ComputerCardProjection(COMPUTER,
			ProductionProfession.LIBRARIAN, 2, 1, 6, AggregateState.RUN, 1);
		ComputerSlotProjection slot = ComputerSlotProjection.occupied(0, PROCESS, ProcessKind.GOAL,
			GOAL, "minecraft:iron_ingot", ProcessState.RUNNABLE, Optional.empty(),
			RemoteState.RUNNING, true);
		TreeNode root = new TreeNode(0, TreeNodeKind.GOAL, "minecraft:iron_ingot", 4, "",
			Optional.empty(), ExecutionState.RUN, Optional.empty(), -1, Optional.empty(),
			Optional.empty());
		TreeNode process = new TreeNode(1, TreeNodeKind.PROCESS, "minecraft:iron_ingot", 4,
			"", Optional.empty(), ExecutionState.RUN,
			Optional.of(ComputerCardProjection.shortId(COMPUTER)), 0, Optional.empty(),
			Optional.of("kind=GOAL;remote=RUNNING"));
		TreeNode address = new TreeNode(2, TreeNodeKind.CREATE_ADDRESS, "minecraft:iron_ingot", 4,
			"", Optional.of(CREATE_ADDRESS), ExecutionState.RUN,
			Optional.of(ComputerCardProjection.shortId(COMPUTER)), 0, Optional.empty(),
			Optional.empty());
		GoalTreeProjection tree = new GoalTreeProjection(GOAL, "minecraft:iron_ingot", 4,
			0, 3, List.of(root, process, address));
		ComputerMonitorProjection projection = new ComputerMonitorProjection(CLUSTER, EPOCH, 9,
			Status.CURRENT, 0, 1, List.of(card), Optional.of(COMPUTER), 0, 6, List.of(slot),
			Optional.of(PROCESS), Optional.of(tree), 8);
		ComputerMonitorResponsePacket packet = new ComputerMonitorResponsePacket(opening, projection);
		RegistryFriendlyByteBuf encoded = buffer();

		packet.write(encoded);
		ComputerMonitorResponsePacket decoded = decode(encoded);

		assertEquals(packet, decoded);
		assertTrue(decoded.matches(opening));
		assertFalse(decoded.matches(new FactoryPanelOpening(PANEL_ADDRESS, PANEL, id(99), CLUSTER, EPOCH)));
	}

	@Test
	void stalePacketCarriesOnlyTheCurrentRevisionAndNoRows() {
		ComputerMonitorProjection stale = ComputerMonitorProjection.stale(CLUSTER, EPOCH, 12, 16);
		ComputerMonitorResponsePacket packet = roundTrip(
			new ComputerMonitorResponsePacket(opening(), stale));

		assertEquals(Status.STALE, packet.projection().status());
		assertEquals(12, packet.projection().revision());
		assertEquals(0, packet.projection().returnedRowCount());
		assertTrue(packet.projection().goalTree().isEmpty());
	}

	@Test
	void packetRejectsIdentityMismatchAndCumulativeWireRowsBeyondTheDeclaredLimit() {
		ComputerMonitorProjection stale = ComputerMonitorProjection.stale(CLUSTER, EPOCH, 12, 1);
		assertThrows(IllegalArgumentException.class, () -> new ComputerMonitorResponsePacket(
			new FactoryPanelOpening(PANEL_ADDRESS, PANEL, SESSION, id(90), EPOCH), stale));

		RegistryFriendlyByteBuf malicious = buffer();
		write(malicious, "writeVarInt", int.class, ComputerMonitorResponsePacket.VERSION);
		opening().write(malicious);
		write(malicious, "writeVarLong", long.class, 0L);
		write(malicious, "writeEnum", Enum.class, Status.CURRENT);
		write(malicious, "writeVarLong", long.class, 1L);
		write(malicious, "writeVarInt", int.class, 1);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeVarInt", int.class, 2);
		write(malicious, "writeVarInt", int.class, 2);

		assertThrows(IllegalArgumentException.class, () -> decode(malicious));
	}

	@Test
	void decoderRejectsOutOfRangeSlotIndexEvenWhenWireCountsAreBounded() {
		RegistryFriendlyByteBuf malicious = buffer();
		write(malicious, "writeVarInt", int.class, ComputerMonitorResponsePacket.VERSION);
		opening().write(malicious);
		write(malicious, "writeVarLong", long.class, 0L);
		write(malicious, "writeEnum", Enum.class, Status.CURRENT);
		write(malicious, "writeVarLong", long.class, 1L);
		write(malicious, "writeVarInt", int.class, 2);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeBoolean", boolean.class, true);
		write(malicious, "writeUUID", UUID.class, COMPUTER);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeVarInt", int.class, 1);
		write(malicious, "writeVarInt", int.class, 1);
		write(malicious, "writeVarInt", int.class, 5);
		write(malicious, "writeBoolean", boolean.class, false);
		write(malicious, "writeBoolean", boolean.class, false);
		write(malicious, "writeBoolean", boolean.class, false);

		assertThrows(IllegalArgumentException.class, () -> decode(malicious));
	}

	@Test
	void offsetTreeRoundTripsParentContextAndDecoderRejectsMismatchedLeafAssignment() {
		String computerShortId = ComputerCardProjection.shortId(COMPUTER);
		TreeNode context = new TreeNode(1, TreeNodeKind.PROCESS, "minecraft:iron", 1, "",
			Optional.empty(), ExecutionState.RUN, Optional.of(computerShortId), 0,
			Optional.empty(), Optional.of("kind=GOAL"));
		var observed = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
		observed.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 1);
		var observedKey = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey(observed);
		TreeNode leaf = new TreeNode(2, TreeNodeKind.INVENTORY, "minecraft:iron", 1, "",
			Optional.empty(), ExecutionState.RUN, Optional.of(computerShortId), 0,
			Optional.empty(), Optional.empty(), Optional.of(observedKey));
		observed.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64);
		GoalTreeProjection tree = new GoalTreeProjection(GOAL, "minecraft:iron", 1, 2, 3,
			Optional.of(context), List.of(leaf));
		ComputerMonitorProjection projection = new ComputerMonitorProjection(CLUSTER, EPOCH, 0,
			Status.CURRENT, 0, 0, List.of(), Optional.of(COMPUTER), 0, 0, List.of(),
			Optional.of(PROCESS), Optional.of(tree), 2);
		assertEquals(projection, roundTrip(
			new ComputerMonitorResponsePacket(opening(), projection)).projection());

		RegistryFriendlyByteBuf malicious = buffer();
		write(malicious, "writeVarInt", int.class, ComputerMonitorResponsePacket.VERSION);
		opening().write(malicious);
		write(malicious, "writeVarLong", long.class, 0L);
		write(malicious, "writeEnum", Enum.class, Status.CURRENT);
		write(malicious, "writeVarLong", long.class, 0L);
		write(malicious, "writeVarInt", int.class, 2);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeBoolean", boolean.class, true);
		write(malicious, "writeUUID", UUID.class, COMPUTER);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeVarInt", int.class, 0);
		write(malicious, "writeBoolean", boolean.class, true);
		write(malicious, "writeUUID", UUID.class, PROCESS);
		write(malicious, "writeBoolean", boolean.class, true);
		write(malicious, "writeUUID", UUID.class, GOAL);
		writeText(malicious, "minecraft:iron");
		write(malicious, "writeVarLong", long.class, 1L);
		write(malicious, "writeVarInt", int.class, 2);
		write(malicious, "writeVarInt", int.class, 3);
		write(malicious, "writeBoolean", boolean.class, true);
		writeTreeNode(malicious, context);
		write(malicious, "writeVarInt", int.class, 1);
		TreeNode mismatched = new TreeNode(2, TreeNodeKind.INVENTORY, "minecraft:iron", 1,
			"", Optional.empty(), ExecutionState.RUN,
			Optional.of(ComputerCardProjection.shortId(id(99))), 1, Optional.empty(),
			Optional.empty());
		writeTreeNode(malicious, mismatched);

		assertThrows(IllegalArgumentException.class, () -> decode(malicious));
	}

	@Test
	void projectionConstructorsRejectUnboundedTextAndMutableOrInconsistentRows() {
		assertThrows(IllegalArgumentException.class, () -> new TreeNode(0, TreeNodeKind.GOAL,
			"x".repeat(4097), 1, "", Optional.empty(), ExecutionState.IDLE, Optional.empty(), -1,
			Optional.empty(), Optional.empty()));
		ComputerCardProjection card = new ComputerCardProjection(COMPUTER,
			ProductionProfession.LIBRARIAN, 1, 0, 4, AggregateState.IDLE, 0);
		assertThrows(IllegalArgumentException.class, () -> new ComputerMonitorProjection(CLUSTER,
			EPOCH, 1, Status.CURRENT, 0, 1, List.of(card), Optional.empty(), 0, 0, List.of(),
			Optional.empty(), Optional.empty(), 0));
		assertThrows(IllegalArgumentException.class, () -> new ComputerMonitorProjection(CLUSTER,
			EPOCH, 1, Status.STALE, 0, 1, List.of(card), Optional.empty(), 0, 0, List.of(),
			Optional.empty(), Optional.empty(), 1));
	}

	private static ComputerMonitorResponsePacket roundTrip(ComputerMonitorResponsePacket packet) {
		RegistryFriendlyByteBuf encoded = buffer();
		packet.write(encoded);
		return decode(encoded);
	}

	private static FactoryPanelOpening opening() {
		return new FactoryPanelOpening(PANEL_ADDRESS, PANEL, SESSION, CLUSTER, EPOCH);
	}

	private static RegistryFriendlyByteBuf buffer() {
		try {
			Class<?> byteBufType = Class.forName("io.netty.buffer.ByteBuf");
			Object byteBuf = Class.forName("io.netty.buffer.Unpooled")
				.getMethod("buffer").invoke(null);
			return (RegistryFriendlyByteBuf) RegistryFriendlyByteBuf.class
				.getConstructor(byteBufType, RegistryAccess.class)
				.newInstance(byteBuf, RegistryAccess.EMPTY);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static ComputerMonitorResponsePacket decode(Object buffer) {
		try {
			return (ComputerMonitorResponsePacket) ComputerMonitorResponsePacket.class
				.getConstructor(RegistryFriendlyByteBuf.class).newInstance(buffer);
		} catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
			throw new AssertionError(exception.getCause());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void write(Object buffer, String method, Class<?> type, Object value) {
		try {
			buffer.getClass().getMethod(method, type).invoke(buffer, value);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void writeTreeNode(RegistryFriendlyByteBuf buffer, TreeNode node) {
		write(buffer, "writeVarInt", int.class, node.depth());
		write(buffer, "writeEnum", Enum.class, node.kind());
		writeText(buffer, node.output());
		write(buffer, "writeVarLong", long.class, node.count());
		writeText(buffer, node.libraryDisplay());
		write(buffer, "writeBoolean", boolean.class, false);
		write(buffer, "writeEnum", Enum.class, node.executionState());
		write(buffer, "writeBoolean", boolean.class, node.assignedComputerShortId().isPresent());
		if (node.assignedComputerShortId().isPresent()) {
			writeText(buffer, node.assignedComputerShortId().orElseThrow());
			write(buffer, "writeVarInt", int.class, node.assignedSlot());
		}
		write(buffer, "writeBoolean", boolean.class, false);
		write(buffer, "writeBoolean", boolean.class, node.detail().isPresent());
		node.detail().ifPresent(detail -> writeText(buffer, detail));
		write(buffer, "writeBoolean", boolean.class, node.inventoryItem().isPresent());
		node.inventoryItem().ifPresent(item -> com.nobodiiiii.createbiotech.content.factorycluster
			.panel.view.tasks.TaskListResponsePacket.writeStackKey(buffer, item));
	}

	private static void writeText(Object buffer, String value) {
		try {
			buffer.getClass().getMethod("writeUtf", String.class, int.class)
				.invoke(buffer, value, WorldFact.MAX_TEXT_LENGTH);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
