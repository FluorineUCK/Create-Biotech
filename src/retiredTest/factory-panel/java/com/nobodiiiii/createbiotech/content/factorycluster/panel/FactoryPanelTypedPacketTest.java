package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelGoalCommandPacket.ComputationSelection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelGoalCommandPacket.ExecutionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelNewTaskPacket.Draft;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelNewTaskPacket.Failure;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelNewTaskPacket.SubmissionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelViewRequestPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorViewRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class FactoryPanelTypedPacketTest {
	private static final UUID PANEL = id(1), CLUSTER = id(2), EPOCH = id(3), SESSION = id(4);
	private static final UUID GOAL = id(5), COMPUTER = id(6), PROCESS = id(7), NETWORK = id(8);
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(new SpaceAddress(
		Level.OVERWORLD, null, new BlockPos(1, 2, 3)), PANEL, SESSION, CLUSTER, EPOCH);

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		Items.AIR.getDefaultInstance();
	}

	@Test
	void fiveServerboundDomainsHaveIndependentDirectionsTypesAndCodecs() {
		ComputerMonitorViewRequest monitor = new ComputerMonitorViewRequest(
			ComputerMonitorViewRequest.ActiveViewport.COMPUTERS, Optional.empty(), Optional.empty(),
			new ComputerMonitorViewRequest.Viewport(0, 4),
			new ComputerMonitorViewRequest.Viewport(0, 0),
			new ComputerMonitorViewRequest.Viewport(0, 0), false);
		FactoryPanelViewRequestPacket view = new FactoryPanelViewRequestPacket(OPENING,
			FactoryPanelView.COMPUTER_MONITOR, OptionalLong.of(2), 0, 4, Optional.empty(), 31,
			Optional.of(monitor));
		FactoryPanelGoalCommandPacket goal = new FactoryPanelGoalCommandPacket(OPENING, 3, 4,
			GOAL, 32, new FactoryPanelGoalCommandPacket.ViewComputation());
		Draft draft = new Draft(new StackKey(new ItemStack(Items.IRON_INGOT)), NETWORK,
			"warehouse", 2, InputMode.EXACT, 9);
		FactoryPanelNewTaskPacket newTask = new FactoryPanelNewTaskPacket(OPENING, 3, 5, 33,
			draft);
		FactoryPanelCommunicationCommandPacket communication =
			new FactoryPanelCommunicationCommandPacket(OPENING, 3, 6,
				new FactoryPanelCommunicationCommandPacket.ChangePrimary(0,
					CommunicationFrequency.of("typed")));
		FactoryPanelLogisticsCommandPacket logistics = new FactoryPanelLogisticsCommandPacket(
			OPENING, NETWORK, new FactoryPanelLogisticsCommandPacket.RenameAlias(0,
				Optional.of("Main")));

		List<Object> packets = List.of(view, goal, newTask, communication, logistics);
		for (Object packet : packets) assertInstanceOf(ServerboundPacketPayload.class, packet);
		assertEquals(view, roundTrip(view, FactoryPanelViewRequestPacket.class));
		assertEquals(goal, roundTrip(goal, FactoryPanelGoalCommandPacket.class));
		assertEquals(newTask, roundTrip(newTask, FactoryPanelNewTaskPacket.class));
		assertEquals(communication,
			roundTrip(communication, FactoryPanelCommunicationCommandPacket.class));
		assertEquals(logistics, roundTrip(logistics, FactoryPanelLogisticsCommandPacket.class));
		for (int left = 0; left < packets.size(); left++) for (int right = left + 1;
			right < packets.size(); right++) assertNotEquals(
				((ServerboundPacketPayload) packets.get(left)).getTypeProvider().getType(),
				((ServerboundPacketPayload) packets.get(right)).getTypeProvider().getType());
	}

	@Test
	void commandResultsAreIndependentClientboundCodecsAndPreserveNavigationOrDraft() {
		var selection = new ComputationSelection(COMPUTER, PROCESS);
		FactoryPanelGoalCommandResultPacket goal = new FactoryPanelGoalCommandResultPacket(
			OPENING, 41, new ExecutionResult(true, Optional.of(selection)));
		Draft draft = new Draft(new StackKey(new ItemStack(Items.GOLD_INGOT)), NETWORK,
			"failed-route", 7, InputMode.PARTIAL, -3);
		FactoryPanelNewTaskResultPacket failed = new FactoryPanelNewTaskResultPacket(OPENING, 42,
			SubmissionResult.rejected(Failure.INVALID_ROUTE, draft));
		FactoryPanelNewTaskResultPacket succeeded = new FactoryPanelNewTaskResultPacket(OPENING, 43,
			new SubmissionResult(Optional.empty(), Optional.of(GOAL), draft));

		assertInstanceOf(ClientboundPacketPayload.class, goal);
		assertInstanceOf(ClientboundPacketPayload.class, failed);
		assertEquals(goal, roundTrip(goal, FactoryPanelGoalCommandResultPacket.class));
		assertEquals(failed, roundTrip(failed, FactoryPanelNewTaskResultPacket.class));
		assertEquals(succeeded, roundTrip(succeeded, FactoryPanelNewTaskResultPacket.class));
		assertNotEquals(goal.getTypeProvider().getType(), failed.getTypeProvider().getType());
		var communication = new FactoryPanelCommunicationCommandResultPacket(OPENING, 44, true);
		var logistics = new FactoryPanelLogisticsCommandResultPacket(OPENING, 45, false);
		assertInstanceOf(ClientboundPacketPayload.class, communication);
		assertInstanceOf(ClientboundPacketPayload.class, logistics);
		assertEquals(communication, roundTrip(communication, FactoryPanelCommunicationCommandResultPacket.class));
		assertEquals(logistics, roundTrip(logistics, FactoryPanelLogisticsCommandResultPacket.class));
		assertNotEquals(communication.getTypeProvider().getType(), logistics.getTypeProvider().getType());
	}

	@Test
	void malformedBoundsAndMonitorShapeFailClosedBeforeHandling() {
		assertThrows(IllegalArgumentException.class, () -> new FactoryPanelViewRequestPacket(OPENING,
			FactoryPanelView.TASKS, OptionalLong.empty(), -1, 1, Optional.empty(), 1,
			Optional.empty()));
		assertThrows(IllegalArgumentException.class, () -> new FactoryPanelViewRequestPacket(OPENING,
			FactoryPanelView.COMPUTER_MONITOR, OptionalLong.empty(), 0, 1, Optional.empty(), 1,
			Optional.empty()));
		assertThrows(IllegalArgumentException.class, () -> new FactoryPanelGoalCommandPacket(OPENING,
			0, 0, GOAL, -1, new FactoryPanelGoalCommandPacket.Cancel()));
		assertThrows(IllegalArgumentException.class, () -> new FactoryPanelNewTaskPacket(OPENING,
			0, 0, -1, new Draft(new StackKey(new ItemStack(Items.IRON_INGOT)), NETWORK,
				"warehouse", 1, InputMode.BULK, 0)));
		assertThrows(IllegalArgumentException.class, () ->
			new FactoryPanelCommunicationCommandPacket.ReplaceExternalListeners("invalid", 0,
				List.of()));
		assertThrows(IllegalArgumentException.class, () ->
			new FactoryPanelLogisticsCommandPacket.RenameAlias(-1, Optional.empty()));
	}

	@Test
	void decoderRejectsUnknownVersionsAndEveryTruncatedPrefixAcrossFiveDomains() {
		List<Object> packets = List.of(
			new FactoryPanelViewRequestPacket(OPENING, FactoryPanelView.TASKS, OptionalLong.empty(), 0, 4, Optional.empty(), 1),
			new FactoryPanelGoalCommandPacket(OPENING, 1, 1, GOAL, 2, new FactoryPanelGoalCommandPacket.Cancel()),
			new FactoryPanelNewTaskPacket(OPENING, 1, 1, 3, new Draft(new StackKey(new ItemStack(Items.IRON_INGOT)),
				NETWORK, "warehouse", 1, InputMode.EXACT, 0)),
			new FactoryPanelCommunicationCommandPacket(OPENING, 1, 1, 4,
				new FactoryPanelCommunicationCommandPacket.ChangePrimary(0, CommunicationFrequency.of("wire"))),
			new FactoryPanelLogisticsCommandPacket(OPENING, NETWORK, 5,
				new FactoryPanelLogisticsCommandPacket.RenameAlias(0, Optional.of("Alias"))));
		for (Object packet : packets) {
			RegistryFriendlyByteBuf encoded = buffer();
			invoke(packet, "write", RegistryFriendlyByteBuf.class, encoded);
			int length = (Integer) invoke(encoded, "writerIndex");
			byte[] bytes = new byte[length];
			invoke(encoded, "getBytes", new Class<?>[]{int.class, byte[].class}, 0, bytes);
			for (int end = 0; end < length; end++) {
				RegistryFriendlyByteBuf truncated = buffer();
				invoke(truncated, "writeBytes", byte[].class, java.util.Arrays.copyOf(bytes, end));
				assertDecoderRejects(packet.getClass(), truncated);
			}
			RegistryFriendlyByteBuf unknown = buffer();
			integer(unknown, 127);
			assertDecoderRejects(packet.getClass(), unknown);
		}
	}

	@Test
	void malformedWireTagsEnumsLengthsCollectionsAndMonitorIffAreRejected() {
		for (int view : new int[] {127, FactoryPanelView.COMPUTER_MONITOR.wireId()}) {
			var wire = header(FactoryPanelViewRequestPacket.VERSION);
			integer(wire, view); bool(wire, false); integer(wire, 0); integer(wire, 1);
			bool(wire, false); number(wire, 1); bool(wire, false);
			assertDecoderRejects(FactoryPanelViewRequestPacket.class, wire);
		}
		var negative = header(FactoryPanelViewRequestPacket.VERSION);
		integer(negative, FactoryPanelView.TASKS.wireId()); bool(negative, false);
		integer(negative, -1); integer(negative, 1); bool(negative, false);
		number(negative, 1); bool(negative, false);
		assertDecoderRejects(FactoryPanelViewRequestPacket.class, negative);
		for (int tag : new int[] {127, 1}) {
			var wire = header(FactoryPanelGoalCommandPacket.VERSION);
			number(wire, 1); number(wire, 1); invoke(wire, "writeUUID", UUID.class, GOAL);
			number(wire, 1); integer(wire, tag); integer(wire, 127);
			assertDecoderRejects(FactoryPanelGoalCommandPacket.class, wire);
		}
		for (int count : new int[] {-1, 1000}) {
			var wire = header(FactoryPanelCommunicationCommandPacket.VERSION);
			number(wire, 1); number(wire, 1); number(wire, 1); integer(wire, 2);
			invoke(wire, "writeUtf", String.class, "listener-0000000000000000");
			number(wire, 0); integer(wire, count);
			assertDecoderRejects(FactoryPanelCommunicationCommandPacket.class, wire);
		}
		var communicationTag = header(FactoryPanelCommunicationCommandPacket.VERSION);
		number(communicationTag, 1); number(communicationTag, 1); number(communicationTag, 1);
		integer(communicationTag, 127);
		assertDecoderRejects(FactoryPanelCommunicationCommandPacket.class, communicationTag);
		var role = header(FactoryPanelCommunicationCommandPacket.VERSION);
		number(role, 1); number(role, 1); number(role, 1); integer(role, 1);
		number(role, 0); integer(role, 127);
		assertDecoderRejects(FactoryPanelCommunicationCommandPacket.class, role);
		var alias = header(FactoryPanelLogisticsCommandPacket.VERSION);
		invoke(alias, "writeUUID", UUID.class, NETWORK); number(alias, 1); number(alias, 0);
		bool(alias, true); invoke(alias, "writeUtf", String.class, "x".repeat(4096));
		assertDecoderRejects(FactoryPanelLogisticsCommandPacket.class, alias);
		var draft = header(FactoryPanelNewTaskPacket.VERSION);
		number(draft, 1); number(draft, 1); number(draft, 1);
		com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskListResponsePacket
			.writeStackKey(draft, new StackKey(new ItemStack(Items.IRON_INGOT)));
		invoke(draft, "writeUUID", UUID.class, NETWORK);
		invoke(draft, "writeUtf", String.class, "x".repeat(4096));
		assertDecoderRejects(FactoryPanelNewTaskPacket.class, draft);
	}

	private static RegistryFriendlyByteBuf header(int version) {
		var wire = buffer(); integer(wire, version); OPENING.write(wire); return wire;
	}
	private static void integer(Object wire, int value) { invoke(wire, "writeVarInt", int.class, value); }
	private static void number(Object wire, long value) { invoke(wire, "writeVarLong", long.class, value); }
	private static void bool(Object wire, boolean value) { invoke(wire, "writeBoolean", boolean.class, value); }
	private static Object invoke(Object object, String method, Class<?> type, Object value) {
		return invoke(object, method, new Class<?>[]{type}, value);
	}
	private static Object invoke(Object object, String method, Class<?>[] types, Object... values) {
		try { return object.getClass().getMethod(method, types).invoke(object, values); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static Object invoke(Object object, String method) {
		return invoke(object, method, new Class<?>[0]);
	}
	private static void assertDecoderRejects(Class<?> type, RegistryFriendlyByteBuf wire) {
		var exception = assertThrows(InvocationTargetException.class,
			() -> type.getConstructor(RegistryFriendlyByteBuf.class).newInstance((Object) wire), type.getSimpleName());
		assertInstanceOf(RuntimeException.class, exception.getCause());
	}

	private static <T> T roundTrip(Object packet, Class<T> type) {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			Object erasedBuffer = buffer;
			packet.getClass().getMethod("write", RegistryFriendlyByteBuf.class)
				.invoke(packet, erasedBuffer);
			return type.getConstructor(RegistryFriendlyByteBuf.class).newInstance(erasedBuffer);
		} catch (InvocationTargetException exception) {
			throw new AssertionError(exception.getCause());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
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

	private static UUID id(long value) { return new UUID(0, value); }

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(),
				Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
