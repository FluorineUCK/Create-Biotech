package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.CarrierHardwareConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierClock;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.WitchReceiverFilter;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.neoforged.neoforge.common.ModConfigSpec;

class ComputerBehaviourTest {
    @Test void witchCarrierIsServerOnlyAndCannotBeDrivenWithoutAPhysicalWitch() {
        var block = new Harness();
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var setting = new WitchCarrierStage.Setting(WitchCarrierStage.Mode.MULTIPLY,
            Optional.of(Direction.NORTH), Optional.of(Direction.WEST), 2);
        var limits = new CarrierHardwareConfig.Limits(16, 16, 3, 2, 3);
        var stage = new WitchCarrierStage();
        stage.configure(setting, limits);
        var saved = new CompoundTag();
        saved.put(WitchCarrierStage.ROOT, stage.write());
        var filter = new WitchReceiverFilter();
        filter.configure(new WitchReceiverFilter.Setting(Optional.of(Direction.WEST), WitchReceiverFilter.Width.NARROW));
        saved.put(WitchReceiverFilter.ROOT, filter.write());
        computer.read(saved, RegistryAccess.EMPTY, false);
        long revision = computer.programRevision();
        assertThrows(IllegalArgumentException.class, () -> computer.configureWitchCarrier(revision, setting));
        assertTrue(computer.witchCarrierOutput().isEmpty());
        block.selection = new HardwareSelection(HardwareSelection.Status.READY, Optional.of(new ResidentHardwareIdentity(
            ResourceLocation.withDefaultNamespace("witch"), ResourceLocation.withDefaultNamespace("none"), 1)), "witch", LIMITS);
        assertEquals(setting.encode(), computer.radioStatus().field("witch_carrier"));
        assertTrue(computer.witchCarrierInput().isEmpty(), "A detached computer is not an active physical port");
        var ref = CarrierClock.Output.valid(com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency.whole(20));
        assertEquals(CarrierClock.State.UNAVAILABLE, computer.advanceWitchCarrier(100, ref, limits).state());
        assertThrows(IllegalArgumentException.class, () -> computer.configureWitchCarrier(revision - 1, WitchCarrierStage.Setting.OFF));
        assertThrows(IllegalArgumentException.class, () -> computer.configureWitchCarrier(revision, setting));
        assertEquals(revision, computer.programRevision());
        var client = saved.copy();
        computer.write(client, RegistryAccess.EMPTY, true);
        assertFalse(client.contains(WitchCarrierStage.ROOT));
        computer.configureWitchCarrier(revision, WitchCarrierStage.Setting.OFF);
        assertEquals(filter.setting().encode(), computer.radioStatus().field("witch_filter"));
        var cleared = saved.copy(); computer.write(cleared, RegistryAccess.EMPTY, false);
        assertFalse(cleared.contains(WitchCarrierStage.ROOT));
        computer.read(saved, RegistryAccess.EMPTY, false);
        computer.residentRadioRemoved();
        computer.write(saved, RegistryAccess.EMPTY, false);
        assertFalse(saved.contains(WitchCarrierStage.ROOT));
        assertFalse(saved.contains(WitchReceiverFilter.ROOT));
    }
    @Test void editorDraftPersistsSeparatelyFromInstalledExecutionAndNeverLeaksIntoClientUpdates() {
        var computer = new Harness().getBehaviour(ComputerBehaviour.TYPE);
        var flow = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(
            Map.of("ticks", DataType.Scalar.INTEGER), List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Sleep("wait", "ticks"))).withAssembly();
        computer.installFlow(flow, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(5))));
        computer.step();
        var running = computer.flowSnapshot(); long revision = computer.programRevision();
        var workspace = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace();
        workspace.setFlow(Optional.of(com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody.emptySoftwareAssembly()));
        var document = workspace.snapshot();
        var bytes = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentWire.encode(document, computer.hardware().limits().archive());
        assertTrue(computer.saveDraft(0, bytes));
        assertEquals(running, computer.flowSnapshot()); assertEquals(revision, computer.programRevision());
        var durable = new CompoundTag(); computer.write(durable, RegistryAccess.EMPTY, false);
        var restored = new Harness().getBehaviour(ComputerBehaviour.TYPE); restored.read(durable, RegistryAccess.EMPTY, false); restored.tick();
        assertEquals(running, restored.flowSnapshot());
        assertEquals(document, com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentWire.decode(restored.savedDraft().document(), restored.hardware().limits().archive()));
        restored.reset(); assertArrayEquals(bytes, restored.savedDraft().document(), "Reset is not deletion of the editor draft");
        var update = durable.copy(); restored.write(update, RegistryAccess.EMPTY, true);
        assertFalse(update.contains(ComputerBehaviour.DRAFT_ROOT), "Drafts require an authorized menu, not a block update");
    }
    @Test void installedIdentitySurvivesFailedMainAndAnIndependentManagedTaskCanStillRun() {
        var block = new Harness(); block.selection = profile("minecraft:cleric", 5);
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var main = new ModuleDefinition.Key("m",1); var child = new ModuleDefinition.Key("c",1);
        var flow = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Invoke("main",main,
                new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings(Map.of(),Map.of())),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure("main_def", main, Map.of(),Map.of(),Map.of(),
                com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockRules.NONE,
                Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("REG t INTEGER\nCONST t -1\nSLEEP t"))),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.EndProcedure("main_end"),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure("child_def", child, Map.of(),Map.of(),Map.of(),
                com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockRules.NONE,
                Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("REG t INTEGER\nCONST t 1"))),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.EndProcedure("child_end"))).withAssembly();
        computer.installFlow(flow,EMPTY);
        var id = computer.installedProgram().orElseThrow().installation();
        computer.setPaused(false);
        for (int i=0;i<10;i++) block.tick();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.FAILED,
            computer.flowSnapshot().orElseThrow().state().status());
        assertEquals(id,computer.installedProgram().orElseThrow().installation());
        var task = computer.startFlowTask(child,EMPTY);
        for (int i=0;i<10;i++) block.tick();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.COMPLETE,
            computer.flowTask(task).orElseThrow().status());
        assertEquals(id,computer.installedProgram().orElseThrow().installation());
    }

    @Test void missingInstallationIsMigratedOnlyAfterValidatedLoadAndMalformedIdentityStaysClosed() {
        var source = new Harness(); var computer = source.getBehaviour(ComputerBehaviour.TYPE);
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        var installed = computer.installedProgram().orElseThrow();
        var saved = new CompoundTag(); source.saveAdditional(saved,RegistryAccess.EMPTY);
        var malformed = saved.copy(); malformed.putString(ComputerBehaviour.INSTALLATION_ROOT,"future");
        var broken = new Harness(); broken.loadWithComponents(malformed,RegistryAccess.EMPTY); broken.tick();
        assertTrue(broken.getBehaviour(ComputerBehaviour.TYPE).installedProgram().isEmpty());
        var retained = new CompoundTag(); broken.saveAdditional(retained,RegistryAccess.EMPTY);
        assertEquals(malformed.get(ComputerBehaviour.INSTALLATION_ROOT),retained.get(ComputerBehaviour.INSTALLATION_ROOT));
        saved.remove(ComputerBehaviour.INSTALLATION_ROOT);
        var legacy = new Harness(); legacy.loadWithComponents(saved,RegistryAccess.EMPTY);
        var old = legacy.getBehaviour(ComputerBehaviour.TYPE);
        assertTrue(old.installedProgram().isEmpty());
        legacy.tick();
        var migrated = old.installedProgram().orElseThrow();
        assertEquals(installed.computer(),migrated.computer());
        assertNotEquals(installed.installation(),migrated.installation());
        legacy.available = false;
        assertThrows(ComputerBlockHost.HostException.class,() -> old.install(ComputerRuntimeTest.counter(2),ComputerRuntime.MemoryPolicy.RESET));
        assertTrue(old.installedProgram().isEmpty());
        legacy.available = true;
        assertEquals(migrated.installation(),old.installedProgram().orElseThrow().installation());
    }

    @Test void installationIdentitySurvivesResetAndReloadButNotReplacement() {
        var block = new Harness();
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        assertTrue(computer.installedProgram().isEmpty());
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        var first = computer.installedProgram().orElseThrow();
        computer.setPaused(true);
        assertEquals(first.installation(), computer.installedProgram().orElseThrow().installation());
        computer.reset();
        var saved = new CompoundTag();
        block.saveAdditional(saved, RegistryAccess.EMPTY);
        var restored = new Harness();
        restored.loadWithComponents(saved, RegistryAccess.EMPTY);
        restored.tick();
        var next = restored.getBehaviour(ComputerBehaviour.TYPE);
        assertEquals(first.installation(), next.installedProgram().orElseThrow().installation());
        next.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        assertNotEquals(first.installation(), next.installedProgram().orElseThrow().installation());
    }
 
    @Test void explicitPortClosureAndReopeningAreLoggedOnceWithoutChangingProgramOrRam() {
        var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        var before = computer.snapshot();
        var config = new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("io", true);
        computer.configureEndpoint(config); computer.disableEndpoint(); computer.disableEndpoint(); computer.configureEndpoint(config);
        var events = ((DataValue.ListValue)computer.recentEvents().field("entries")).values();
        for (String code : List.of("closed", "recovered")) assertEquals(1, events.stream()
            .filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue(code))).count());
        assertEquals(before, computer.snapshot());
        assertEquals(computer.recentEvents(), computer.recentEvents());
    }    @Test void logReadRechecksMenuPermissionWithoutGrantingAnEndpointOrReturningRamOnDenial() {
        var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        var nonce = UUID.randomUUID(); var session = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession(nonce);
        var before = computer.snapshot();
        var answer = session.apply(nonce, 1, 0, com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.READ_LOG,
            () -> { throw new AssertionError("inputs/world"); }, computer, () -> {
                throw new com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery.DiscoveryException("invalid", "access_denied");
            }).orElseThrow();
        assertEquals("invalid", answer.code()); assertEquals("access_denied", answer.detail());
        assertTrue(answer.snapshot().isEmpty()); assertEquals(EMPTY, answer.dedicatedStatus().orElseThrow());
        assertEquals(before, computer.snapshot()); assertTrue(computer.advertisement().isEmpty());
    }    @Test void availabilityTransitionsAreLoggedOnceAndDoNotAdvanceTheBlockedOperation() {
        var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.installFlow(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(),
            List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable("wait", "x0"))), EMPTY);
        computer.setPaused(false); block.tick(); var before = computer.flowSnapshot();
        block.available = false; block.tick(); block.tick();
        block.available = true; block.tick(); block.tick();
        var entries = ((DataValue.ListValue)computer.recentEvents().field("entries")).values();
        for (String code : List.of("unavailable", "recovered")) assertEquals(1, entries.stream()
            .filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue(code))).count());
        assertEquals(before, computer.flowSnapshot());
    }    @Test void logStatusIsNonceFencedObservationAndDoesNotSerializePrivateRam() {
        var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        var nonce = UUID.randomUUID(); var session = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession(nonce);
        var action = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.READ_LOG;
        java.util.function.Supplier<DataValue.RecordValue> forbidden = () -> { throw new AssertionError("log read evaluated input/world capture"); };
        assertTrue(session.apply(UUID.randomUUID(), 1, 0, action, forbidden, computer).isEmpty());
        var before = computer.snapshot();
        var answer = session.apply(nonce, 1, 0, action, forbidden, computer).orElseThrow();
        assertEquals("ok", answer.code());
        assertTrue(answer.snapshot().isEmpty());
        var packet = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramStatusResponse.fromAnswer(1, nonce, 1, answer, LIMITS.archive());
        var status = (DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.decodeValue(packet.status(), LIMITS.archive());
        assertEquals(Set.of("recent_events"), status.fields().keySet());
        assertEquals(before, computer.snapshot());
        assertTrue(session.apply(nonce, 1, 0, action, forbidden, computer).isEmpty());
    }    @Test void actualXBusRendezvousAppearsAtBothComputersEvenThroughTransientAccess() {
        var a = new Harness(); var b = new Harness();
        var writer = a.getBehaviour(ComputerBehaviour.TYPE); var reader = b.getBehaviour(ComputerBehaviour.TYPE);
        var type = new DataType.RecordType(Map.of("data", DataType.Scalar.TEXT));
        var payload = new DataValue.RecordValue(Map.of("data", new DataValue.TextValue("private-transfer")));
        writer.installFlow(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of("out", type),
            List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Write("send", "x0", "out"))),
            new DataValue.RecordValue(Map.of("out", payload)));
        reader.installFlow(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(),
            List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Read("receive", "x0", "in", type))), EMPTY);
        writer.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("io", true));
        reader.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("io", true));
        writer.setPaused(false); reader.setPaused(false); a.tick(); b.tick();
        var left = writer.xbusAccess("io", writer.xbusPort("io", "x0").orElseThrow()).orElseThrow();
        var right = reader.xbusAccess("io", reader.xbusPort("io", "x0").orElseThrow()).orElseThrow();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.EXCHANGED, left.exchange(right));
        for (var computer : List.of(writer, reader)) {
            var events = computer.recentEvents();
            assertEquals(1, ((DataValue.ListValue)events.field("entries")).values().stream()
                .filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("xbus_exchanged"))).count());
            assertFalse(events.toString().contains("private-transfer"));
        }
        assertEquals(payload, reader.flowSnapshot().orElseThrow().state().locals().field("in"));
    }
    @Test void recentEventsShowOneRealWriteWaitWithoutPublishingItsPayloadOrMutatingTheSave() {
        var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var flow = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(
            Map.of("secret", new DataType.RecordType(Map.of("data", DataType.Scalar.TEXT))), List.of(
                new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Write("send", "x0", "secret")));
        computer.installFlow(flow, new DataValue.RecordValue(Map.of("secret", new DataValue.RecordValue(Map.of("data", new DataValue.TextValue("private-payload"))))));
        computer.setPaused(false); block.tick(); block.tick(); block.tick();
        var before = new CompoundTag(); computer.write(before, RegistryAccess.EMPTY, false);
        var events = computer.recentEvents();
        var entries = ((DataValue.ListValue)events.field("entries")).values();
        assertEquals(1, entries.stream().filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("waiting_write"))).count());
        assertFalse(events.toString().contains("private-payload"));
        assertFalse(events.toString().contains("secret"));
        assertEquals(events, computer.recentEvents());
        var after = new CompoundTag(); computer.write(after, RegistryAccess.EMPTY, false); assertEquals(before, after);
    }
	@Test void unavailableClusterDoesNotStopLocalSleepOrInventAZeroSignalInTickOrStep() {
		for (boolean singleStep : List.of(false, true)) {
			var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
			var flow = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of("level", DataType.Scalar.INTEGER, "ticks", DataType.Scalar.INTEGER), List.of(
				new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.SignalWrite("drive", "p0", "level"),
				new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Sleep("delay", "ticks"),
				new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.SignalRead("read", "p0", "sample")));
			computer.installFlow(flow, new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(40), "ticks", new DataValue.IntegerValue(2))));
			computer.attachCluster(new com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterAttachment(UUID.randomUUID(), new BlockPos(1, 0, 0), Set.of()));
			computer.step(); computer.step();
			assertEquals(2, computer.flowSnapshot().orElseThrow().state().sleepTicks());
			if (singleStep) { computer.step(); computer.step(); computer.step(); }
			else { computer.setPaused(false); block.tick(); assertEquals(1, computer.flowSnapshot().orElseThrow().state().sleepTicks()); block.tick(); }
			var state = computer.flowSnapshot().orElseThrow().state();
			assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.FAILED, state.status());
			assertFalse(state.locals().fields().containsKey("sample"));
			assertEquals(Map.of("p0", 15), state.signals().outputs());
			assertTrue(state.diagnostic().orElseThrow().message().contains("p0"));
		}
	}
    @Test void rejectedFlowDocumentInstallKeepsBothDurableRuntimeAndEditor() {
        var host = new Harness(); var computer = host.getBehaviour(ComputerBehaviour.TYPE);
        var flow = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(),
            List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable("wait", "x0")));
        var body = new com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody(flow.inputs(), flow.steps(), EMPTY);
        var document = new ProgramDocument(flow.expressions(), Map.of(), Optional.empty(), Optional.of(body));
        computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
        var before = new CompoundTag(); computer.write(before, RegistryAccess.EMPTY, false);
        assertThrows(IllegalArgumentException.class, () -> computer.installDocument(document, ComputerRuntime.MemoryPolicy.PRESERVE));
        var after = new CompoundTag(); computer.write(after, RegistryAccess.EMPTY, false); assertEquals(before, after);
        computer.setPaused(false); host.tick(); computer.setPaused(true);
        var waiting = computer.flowSnapshot(); before = new CompoundTag(); computer.write(before, RegistryAccess.EMPTY, false);
        assertThrows(ComputationException.class, () -> computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET));
        after = new CompoundTag(); computer.write(after, RegistryAccess.EMPTY, false);
        assertEquals(before, after); assertEquals(waiting, computer.flowSnapshot()); assertEquals(document, computer.document().orElseThrow());
        computer.reset();
        assertEquals(ComputerRuntime.InstallResult.APPLIED, computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET));
        assertTrue(computer.flowSnapshot().orElseThrow().state().paused());
    }
	@Test void authorizedFlowRefreshShowsRealLocalsAndWaitingSourceWithoutAdvancing() {
		var host = new Harness(); var computer = host.getBehaviour(ComputerBehaviour.TYPE);
		var flow = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of("n", DataType.Scalar.INTEGER),
			List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable("wait", "x0")));
		computer.installFlow(flow, new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(12))));
		computer.setPaused(false); host.tick(); var before = computer.flowSnapshot();
		var nonce = UUID.randomUUID(); var session = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession(nonce);
		var result = session.apply(nonce, 1, computer.programRevision(), com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.REFRESH,
			() -> { throw new AssertionError("refresh does not read inputs"); }, computer).orElseThrow();
		var view = result.dedicatedStatus().orElseThrow();
		assertEquals(new DataValue.TextValue("WAITING_READABLE"), view.field("phase"));
		assertEquals(new DataValue.TextValue("wait"), view.field("source"));
		assertEquals(before.orElseThrow().state().locals(), view.field("locals"));
		assertEquals(before, computer.flowSnapshot());
	}
	@Test void flowDocumentInstallsPausedAndReopensWithItsControlInsteadOfAnEmptyEventProgram() {
		var host = new Harness(); var computer = host.getBehaviour(ComputerBehaviour.TYPE);
		var flow = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(),
			List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable("wait", "x0")));
		var body = new com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody(flow.inputs(), flow.steps(), EMPTY);
		var document = new ProgramDocument(flow.expressions(), Map.of(), Optional.empty(), Optional.of(body));
		assertEquals(ComputerRuntime.InstallResult.APPLIED, computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET));
		var installed = computer.flowSnapshot().orElseThrow();
		assertTrue(installed.state().paused()); assertTrue(computer.snapshot().isEmpty());
		host.tick(); assertEquals(installed, computer.flowSnapshot().orElseThrow());
		var saved = new CompoundTag(); computer.write(saved, RegistryAccess.EMPTY, false);
		var restored = new Harness().getBehaviour(ComputerBehaviour.TYPE); restored.read(saved, RegistryAccess.EMPTY, false);
		assertEquals(document, restored.document().orElseThrow());
		assertEquals(installed, restored.flowSnapshot().orElseThrow());
		assertTrue(restored.diagnostic().isEmpty());
	}
	@Test void coldCommunicationObservationRepliesWithPostRestoreRevision() {
		var source = new Harness().getBehaviour(ComputerBehaviour.TYPE);
		source.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		var saved = new CompoundTag(); source.write(saved, RegistryAccess.EMPTY, false);
		var restored = new Harness().getBehaviour(ComputerBehaviour.TYPE); restored.read(saved, RegistryAccess.EMPTY, false);
		assertTrue(restored.snapshot().isEmpty());
		var nonce = UUID.randomUUID(); var session = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession(nonce);
		var action = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.READ_COMMUNICATION;
		var answer = session.apply(nonce, 1, restored.programRevision(), action, () -> { throw new AssertionError("inputs"); }, restored).orElseThrow();
		assertEquals("ok", answer.code()); assertTrue(restored.snapshot().isPresent());
		assertEquals(restored.programRevision(), answer.revision());
		assertEquals("ok", session.apply(nonce, 2, answer.revision(), action, () -> { throw new AssertionError("inputs"); }, restored).orElseThrow().code());
	}
	@Test void communicationObservationIsAuthorizedInertAndUsesOneHardwareSample() throws Exception {
		var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
		computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		computer.configurePeriodic(3, EMPTY); block.tick(); var before = computer.snapshot();
		var nonce = UUID.randomUUID(); var session = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession(nonce);
		var action = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.READ_COMMUNICATION;
		long revision = computer.programRevision();
		assertTrue(session.apply(UUID.randomUUID(), 1, revision, action, () -> { throw new AssertionError("inputs"); }, computer,
			() -> { throw new AssertionError("authorized invalid nonce"); }).isEmpty());
		assertEquals("stale", session.apply(nonce, 1, revision + 1, action, () -> { throw new AssertionError("inputs"); }, computer,
			() -> { throw new AssertionError("authorized stale revision"); }).orElseThrow().code());
		assertEquals("invalid", session.apply(nonce, 2, revision, action, () -> { throw new AssertionError("inputs"); }, computer,
			() -> { throw new com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery.DiscoveryException("invalid", "access_denied"); }).orElseThrow().code());
		var answer = session.apply(nonce, 3, revision, action, () -> { throw new AssertionError("inputs"); }, computer).orElseThrow();
		assertEquals("ok", answer.code()); assertTrue(answer.snapshot().isEmpty());
		assertTrue(answer.dedicatedStatus().orElseThrow().fields().containsKey("communication"));
		assertTrue(session.apply(nonce, 3, revision, action, () -> { throw new AssertionError("inputs"); }, computer).isEmpty());
		assertEquals(before, computer.snapshot());
		block.samples = 0;
		computer.communicationStatus(); assertEquals(1, block.samples); assertEquals(before, computer.snapshot());
	}
	@Test void transportObservationDoesNotLeakAcrossResetOrRead() {
		var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
		computer.install(ComputerOutboxTest.sender(1), ComputerRuntime.MemoryPolicy.RESET);
		computer.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("source", true));
		computer.begin(EMPTY); for (int i = 0; computer.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
			assertTrue(i < 100); block.tick();
		}
		assertTrue(computer.transportResult().isPresent());
		computer.reset(); assertTrue(computer.transportResult().isEmpty());
		computer.begin(EMPTY); for (int i = 0; computer.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
			assertTrue(i < 100); block.tick();
		}
		assertTrue(computer.transportResult().isPresent());
		var saved = new CompoundTag(); computer.write(saved, RegistryAccess.EMPTY, false);
		computer.read(saved, RegistryAccess.EMPTY, false); assertTrue(computer.transportResult().isEmpty());
	}
	@Test void openEndpointAdmitsNamedMessagesWithoutExecutingUntilItsOwnTick() {
		var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
		computer.install(ComputerMailboxTest.receiver(), ComputerRuntime.MemoryPolicy.RESET);
		var message = ComputerMailboxTest.message(UUID.randomUUID(), 0, 4);
		assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.CLOSED, computer.receive(message));
		computer.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("receiver", true));
		assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.ACCEPTED, computer.receive(message));
		assertEquals(new DataValue.IntegerValue(0), computer.snapshot().orElseThrow().machine().memory().field("total"));
		block.tick();
		for (int i = 0; computer.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
			assertTrue(i < 100); block.tick();
		}
		assertEquals(new DataValue.IntegerValue(4), computer.snapshot().orElseThrow().machine().memory().field("total"));
		computer.disableEndpoint();
		assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.CLOSED, computer.receive(message));
	}
	@Test void endpointSettingsPersistWithoutProgramAndNeverRestoreOrRewriteRuntime() {
		var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
		var badRuntime = new CompoundTag(); badRuntime.putString("future", "untouched");
		var saved = new CompoundTag(); saved.put(ComputerBehaviour.ROOT, badRuntime);
		computer.read(saved, RegistryAccess.EMPTY, false);
		var config = new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("gateway", true);
		long revision = computer.programRevision(); computer.configureEndpoint(config);
		assertEquals(Optional.of(config), computer.advertisement()); assertTrue(computer.snapshot().isEmpty());
		assertTrue(computer.programRevision() > revision);
		var result = new CompoundTag(); computer.write(result, RegistryAccess.EMPTY, false);
		assertEquals(badRuntime, result.get(ComputerBehaviour.ROOT));
		var restored = new Harness().getBehaviour(ComputerBehaviour.TYPE); restored.read(result, RegistryAccess.EMPTY, false);
		assertEquals(Optional.of(config), restored.advertisement()); assertTrue(restored.snapshot().isEmpty());
		restored.write(result, RegistryAccess.EMPTY, true); assertFalse(result.contains(ComputerBehaviour.ENDPOINT_ROOT));
		computer.disableEndpoint(); assertTrue(computer.advertisement().isEmpty());
		assertEquals(new DataValue.BooleanValue(false), ((DataValue.RecordValue)computer.endpointStatus().field("endpoint")).field("enabled"));
		computer.clearEndpoint(); assertEquals(DataValue.NullValue.INSTANCE, computer.endpointStatus().field("endpoint"));
	}
	@Test void closedMetadataDoesNotRestoreRuntimeOrGrantAdmissionAndSurvivesReload() {
		var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
		var badRuntime = new CompoundTag(); badRuntime.putString("future", "untouched");
		var saved = new CompoundTag(); saved.put(ComputerBehaviour.ROOT, badRuntime);
		computer.read(saved, RegistryAccess.EMPTY, false);
		var closed = new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("gateway", false);
		computer.configureEndpoint(closed);
		int changes = block.changes;
		assertEquals(List.of(closed), computer.advertisements());
		assertTrue(computer.advertisement().isEmpty());
		assertTrue(computer.snapshot().isEmpty()); assertEquals(changes, block.changes);
		var result = new CompoundTag(); computer.write(result, RegistryAccess.EMPTY, false);
		assertEquals(badRuntime, result.get(ComputerBehaviour.ROOT));
		var restored = new Harness().getBehaviour(ComputerBehaviour.TYPE); restored.read(result, RegistryAccess.EMPTY, false);
		assertEquals(List.of(closed), restored.advertisements()); assertTrue(restored.snapshot().isEmpty());
		block.available = false; assertTrue(computer.advertisements().isEmpty()); block.available = true;
		computer.clearEndpoint(); assertTrue(computer.advertisements().isEmpty());
	}
	@Test void endpointCommandsFenceAuthorityAndPayloadWithoutAffectingPeriodicCheckpoint() {
		var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
		computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET); computer.configurePeriodic(3, EMPTY);
		block.tick(); var before = computer.snapshot();
		var nonce = UUID.randomUUID(); var session = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession(nonce);
		var action = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.CONFIGURE_ENDPOINT;
		var config = new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("A", true).value();
		long revision = computer.programRevision();
		assertEquals("stale", session.apply(nonce,1,revision+1,action,()->{throw new AssertionError("decoded");},computer).orElseThrow().code());
		var denied = session.apply(nonce,2,revision,action,()->{throw new AssertionError("decoded");},computer,
			()->{throw new com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery.DiscoveryException("invalid","access_denied");}).orElseThrow();
		assertEquals("invalid", denied.code()); assertEquals(revision,computer.programRevision());
		block.available = false;
		assertEquals("unavailable",session.apply(nonce,3,revision,action,()->config,computer).orElseThrow().code());
		block.available = true;
		var answer = session.apply(nonce,4,revision,action,()->config,computer).orElseThrow();
		assertEquals("ok",answer.code()); assertTrue(answer.dedicatedStatus().isPresent()); assertTrue(answer.snapshot().isEmpty());
		assertEquals(before,computer.snapshot()); assertTrue(session.apply(nonce,4,answer.revision(),action,()->{throw new AssertionError();},computer).isEmpty());
	}
	@Test void peripheralAvailabilityRequiresReadyPhysicalHardwareButNoInstalledProgram() {
		var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
		assertTrue(computer.peripheralAvailable()); assertTrue(computer.snapshot().isEmpty());
		assertEquals(0, block.changes);
		block.available = false; assertFalse(computer.peripheralAvailable());
		block.available = true;
		block.selection = new HardwareSelection(HardwareSelection.Status.NO_RESIDENT, Optional.empty(), "", LIMITS);
		assertFalse(computer.peripheralAvailable());
	}
	@Test void documentLayoutsFollowActualQueuedApplyAndCancelEvenForEqualPrograms() {
		var block = new Harness(); var behaviour = block.getBehaviour(ComputerBehaviour.TYPE);
		var archive = ComputerRuntimeTest.counter(1);
		// Counter node IDs are stable; use an existing canonical node for validated layout.
		String node = archive.program().graph().nodes().keySet().iterator().next();
		var old = new ProgramDocument(archive, Map.of(ProgramDocument.graphId(null), Map.of(node, new ProgramDocument.Position(1, 2))));
		var replacement = new ProgramDocument(archive, Map.of(ProgramDocument.graphId(null), Map.of(node, new ProgramDocument.Position(9, 10))));
		behaviour.installDocument(old, ComputerRuntime.MemoryPolicy.RESET);
		behaviour.begin(EMPTY);
		assertEquals(ComputerRuntime.InstallResult.QUEUED, behaviour.installDocument(replacement, ComputerRuntime.MemoryPolicy.PRESERVE));
		assertEquals(replacement, behaviour.document().orElseThrow());
		behaviour.reset(); assertEquals(old, behaviour.document().orElseThrow());
		behaviour.begin(EMPTY); behaviour.installDocument(replacement, ComputerRuntime.MemoryPolicy.PRESERVE);
		for (int i = 0; i < 30 && behaviour.snapshot().orElseThrow().pending().isPresent(); i++) block.tick();
		assertEquals(replacement, behaviour.document().orElseThrow());
	}

	@Test void editorMetadataSurvivesSaveLoadAndCorruptionDoesNotDestroyRuntime() {
		var block = new Harness(); var behaviour = block.getBehaviour(ComputerBehaviour.TYPE);
		var archive = ComputerRuntimeTest.counter(1); String node = archive.program().graph().nodes().keySet().iterator().next();
		var document = new ProgramDocument(archive, Map.of(ProgramDocument.graphId(null), Map.of(node, new ProgramDocument.Position(3, 4))));
		behaviour.installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
		var saved = new CompoundTag(); block.saveAdditional(saved, RegistryAccess.EMPTY);
		assertTrue(saved.contains(ComputerBehaviour.EDITOR_ROOT, Tag.TAG_COMPOUND));
		var restored = new Harness(); restored.loadWithComponents(saved, RegistryAccess.EMPTY); restored.tick();
		assertEquals(document, restored.getBehaviour(ComputerBehaviour.TYPE).document().orElseThrow());
		saved.putString(ComputerBehaviour.EDITOR_ROOT, "future editor");
		var corrupt = new Harness(); corrupt.loadWithComponents(saved, RegistryAccess.EMPTY); corrupt.tick();
		assertTrue(corrupt.getBehaviour(ComputerBehaviour.TYPE).snapshot().isPresent());
		var rewritten = new CompoundTag(); corrupt.saveAdditional(rewritten, RegistryAccess.EMPTY);
		assertEquals(saved.get(ComputerBehaviour.EDITOR_ROOT), rewritten.get(ComputerBehaviour.EDITOR_ROOT));
	}
	@Test void editorRevisionDetectsCanceledAndAutomaticallyAppliedPendingProgramsButNotRamTicks() {
		var block = new Harness(); var behaviour = block.getBehaviour(ComputerBehaviour.TYPE);
		behaviour.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		long idleRevision = behaviour.programRevision();
		behaviour.begin(EMPTY); block.tick(); assertEquals(idleRevision, behaviour.programRevision());
		behaviour.install(ComputerRuntimeTest.counter(2), ComputerRuntime.MemoryPolicy.PRESERVE);
		long queuedRevision = behaviour.programRevision();
		behaviour.reset(); assertTrue(behaviour.programRevision() > queuedRevision);
		behaviour.begin(EMPTY); behaviour.install(ComputerRuntimeTest.counter(2), ComputerRuntime.MemoryPolicy.PRESERVE);
		long beforeApply = behaviour.programRevision();
		for (int i = 0; i < 30 && behaviour.snapshot().orElseThrow().pending().isPresent(); i++) block.tick();
		assertTrue(behaviour.snapshot().orElseThrow().pending().isEmpty());
		assertTrue(behaviour.programRevision() > beforeApply);
	}
	@Test void successfulInstallationAdvancesEditorRevisionEvenWhenProgramIsIdentical() {
		var block = new Harness(); var behaviour = block.getBehaviour(ComputerBehaviour.TYPE);
		long before = behaviour.programRevision();
		behaviour.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		assertEquals(before + 1, behaviour.programRevision());
		behaviour.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.PRESERVE);
		assertEquals(before + 2, behaviour.programRevision());
		block.available = false;
		assertThrows(ComputerBlockHost.HostException.class, () -> behaviour.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.PRESERVE));
		assertEquals(before + 2, behaviour.programRevision());
	}
	static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 1_000_000, 1), new ArchiveLimits(100_000, 1_000_000, 10_000_000)));
	static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

	@BeforeAll static void bootstrap() throws ReflectiveOperationException {
		Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (type.getMethod("get").invoke(null) == null)
			type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
		field.setAccessible(true); field.setBoolean(null, true);
	}

	@Test void createLifecycleDispatchesTicksSavesAndUnloadToTheIndependentHost() {
		var block = new Harness();
		var behaviour = block.getBehaviour(ComputerBehaviour.TYPE);
		behaviour.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		behaviour.begin(EMPTY); block.tick();
		var inFlight = behaviour.snapshot().orElseThrow();
		assertEquals(1, inFlight.machine().workUnits());
		var saved = new CompoundTag(); block.saveAdditional(saved, RegistryAccess.EMPTY);
		assertTrue(saved.contains(ComputerBehaviour.ROOT, Tag.TAG_COMPOUND));
		int changes = block.changes;
		block.available = false; block.invalidate(); block.tick();
		assertEquals(inFlight, behaviour.snapshot().orElseThrow()); assertEquals(changes, block.changes);
		assertThrows(IllegalStateException.class, behaviour::step);
		var restored = new Harness(); restored.loadWithComponents(saved, RegistryAccess.EMPTY);
		for (int i = 0; i < 30; i++) {
			restored.tick();
			if (restored.getBehaviour(ComputerBehaviour.TYPE).snapshot().orElseThrow().machine().status() == ComputationMachine.Status.SUCCEEDED) break;
		}
		var complete = restored.getBehaviour(ComputerBehaviour.TYPE).snapshot().orElseThrow().machine();
		assertEquals(ComputationMachine.Status.SUCCEEDED, complete.status());
		assertEquals(new DataValue.IntegerValue(1), complete.memory().field("count"));
		int idleChanges = restored.changes; restored.tick(); assertEquals(idleChanges, restored.changes);
	}

	@Test void registeredComputerOwnsBehaviourAndPreservesLegacyAndNewSaveRootsSeparately() throws ReflectiveOperationException {
		var computer = nativeComputer();
		assertNotNull(computer.getBehaviour(ComputerBehaviour.TYPE));
		var saved = new CompoundTag(); computer.saveAdditional(saved, RegistryAccess.EMPTY);
		assertFalse(saved.contains(ComputerBehaviour.ROOT), "old computers remain uninstalled");
		var legacy = saved.get("ComputerData").copy();
		var runtime = new ComputerRuntime(UUID.randomUUID(), ComputerRuntimeTest.counter(1), LIMITS.hardware());
		runtime.begin(EMPTY); runtime.step();
		var archive = ComputerNbtCodec.encode(runtime.snapshot(), LIMITS.archive());
		saved.put(ComputerBehaviour.ROOT, archive);
		var restored = nativeComputer(); restored.loadWithComponents(saved, RegistryAccess.EMPTY);
		var rewritten = new CompoundTag(); restored.saveAdditional(rewritten, RegistryAccess.EMPTY);
		assertEquals(legacy, rewritten.get("ComputerData"));
		assertEquals(archive, rewritten.get(ComputerBehaviour.ROOT));
		assertTrue(restored.getBehaviour(ComputerBehaviour.TYPE).snapshot().isEmpty(), "read must not activate a worldless instance");
		assertThrows(IllegalStateException.class, () -> restored.getBehaviour(ComputerBehaviour.TYPE).begin(EMPTY));
	}

	@Test void clientPacketsCannotOverwriteOrExposeTheProgramAndMemory() {
		var block = new Harness(); var behaviour = block.getBehaviour(ComputerBehaviour.TYPE);
		behaviour.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		var before = behaviour.snapshot().orElseThrow();
		var fakeClient = new CompoundTag(); fakeClient.putString(ComputerBehaviour.ROOT, "forged");
		behaviour.read(fakeClient, RegistryAccess.EMPTY, true);
		assertEquals(before, behaviour.snapshot().orElseThrow());
		behaviour.write(fakeClient, RegistryAccess.EMPTY, true);
		assertFalse(fakeClient.contains(ComputerBehaviour.ROOT));
		assertFalse(behaviour.isSafeNBT(), "schematics must not clone machine identities and RAM implicitly");
	}

	@Test void opaqueBadArchiveIsRetainedEvenWhenTheLegacyRootIsAlsoInvalid() throws ReflectiveOperationException {
		var nativeComputer = nativeComputer(); var saved = new CompoundTag(); nativeComputer.saveAdditional(saved, RegistryAccess.EMPTY);
		saved.putString(ComputerBehaviour.ROOT, "future-format"); saved.putString("ComputerData", "legacy-corrupt");
		var restored = nativeComputer(); restored.loadWithComponents(saved, RegistryAccess.EMPTY);
		var rewritten = new CompoundTag(); restored.saveAdditional(rewritten, RegistryAccess.EMPTY);
		assertEquals(saved.get(ComputerBehaviour.ROOT), rewritten.get(ComputerBehaviour.ROOT));
		assertEquals(saved.get("ComputerData"), rewritten.get("ComputerData"));
	}

	@Test void realComputerSelectsFrozenResidentHardwareButWorldlessInstanceStillCannotExecute() throws ReflectiveOperationException {
		var computer = nativeComputer(); var resident = new ItemStack(Items.PAPER);
		var data = new CompoundTag(); var entity = new CompoundTag(); var villager = new CompoundTag();
		entity.putString("id", "minecraft:villager"); entity.putInt("Age", 0);
		entity.putUUID("UUID", UUID.randomUUID());
		villager.putString("profession", "minecraft:cartographer"); villager.putInt("level", 4);
		entity.put("VillagerData", villager); data.put("CapturedEntity", entity); CBItemData.set(resident, data);
		var saved = new CompoundTag(); computer.saveAdditional(saved, RegistryAccess.EMPTY);
		saved.getCompound("ComputerData").put("Resident", resident.save(RegistryAccess.EMPTY));
		computer.loadWithComponents(saved, RegistryAccess.EMPTY);
		var behaviour = computer.getBehaviour(ComputerBehaviour.TYPE);
		assertEquals("memory", behaviour.hardware().profile());
		assertEquals(196608, behaviour.hardware().limits().hardware().memoryUnits());
		assertThrows(ComputerBlockHost.HostException.class, () -> behaviour.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET));
		assertTrue(behaviour.snapshot().isEmpty());
	}

	@Test void selectedHardwareControlsActualInstallationAndTickProgress() {
		var longGraph = new LinkedHashMap<String, ComputationGraph.Node>(); var outputs = new LinkedHashMap<String, String>();
		for (int i = 0; i < 20; i++) { longGraph.put("n" + i, new ComputationGraph.Constant(new DataValue.IntegerValue(i))); outputs.put("value" + i, "n" + i); }
		var longProgram = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), longGraph, outputs), Map.of(), List.of()), Map.of());
		var program = new Harness(); program.selection = profile("minecraft:librarian", 1);
		var speed = new Harness(); speed.selection = profile("minecraft:fletcher", 1);
		program.getBehaviour(ComputerBehaviour.TYPE).install(longProgram, ComputerRuntime.MemoryPolicy.RESET);
		var rejected = assertThrows(ComputationException.class,
			() -> speed.getBehaviour(ComputerBehaviour.TYPE).install(longProgram, ComputerRuntime.MemoryPolicy.RESET));
		assertEquals(ComputationException.Code.PROGRAM_LIMIT, rejected.code());
		assertTrue(speed.getBehaviour(ComputerBehaviour.TYPE).snapshot().isEmpty());
		var a = program.getBehaviour(ComputerBehaviour.TYPE); var b = speed.getBehaviour(ComputerBehaviour.TYPE);
		a.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		b.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		a.begin(EMPTY); b.begin(EMPTY); program.tick(); speed.tick();
		// This six-work counter can now finish on both presets in one tick (default CPU x8).
		// Compare effective budgets separately; completed programs must not invent extra work.
		long programBudget = program.selection.limits().hardware().workPerSlice();
		long speedBudget = speed.selection.limits().hardware().workPerSlice();
		assertTrue(speedBudget > programBudget);
		assertEquals(Math.min(6, programBudget), a.snapshot().orElseThrow().machine().workUnits());
		assertEquals(Math.min(6, speedBudget), b.snapshot().orElseThrow().machine().workUnits());
		for (int i = 0; i < 30; i++) { program.tick(); speed.tick(); }
		assertEquals(new DataValue.IntegerValue(1), a.snapshot().orElseThrow().machine().memory().field("count"));
		assertEquals(a.snapshot().orElseThrow().machine().memory(), b.snapshot().orElseThrow().machine().memory());
	}

	@Test void hardwareShrinkAndDisableRetainTheExactArchiveAndIncreasingLimitsResumes() {
		var block = new Harness(); block.selection = profile("minecraft:librarian", 1);
		var behaviour = block.getBehaviour(ComputerBehaviour.TYPE);
		behaviour.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET); behaviour.begin(EMPTY); block.tick();
		var before = behaviour.snapshot().orElseThrow(); var encoded = new CompoundTag(); block.saveAdditional(encoded, RegistryAccess.EMPTY);
		var original = block.selection;
		block.selection = new HardwareSelection(HardwareSelection.Status.READY, original.resident(), original.profile(),
			ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(0, 16384, 2), original.limits().archive())));
		block.tick();
		assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, behaviour.diagnostic().orElseThrow().code());
		assertEquals(before, behaviour.snapshot().orElseThrow());
		var rewritten = new CompoundTag(); block.saveAdditional(rewritten, RegistryAccess.EMPTY); assertEquals(encoded, rewritten);
		block.selection = new HardwareSelection(HardwareSelection.Status.DISABLED, original.resident(), "disabled", original.limits());
		block.tick();
		assertEquals(HardwareSelection.Status.DISABLED, behaviour.hardware().status());
		assertThrows(ComputerBlockHost.HostException.class, behaviour::reset);
		assertThrows(ComputerBlockHost.HostException.class, behaviour::step);
		assertThrows(ComputerBlockHost.HostException.class, () -> behaviour.begin(EMPTY));
		assertThrows(ComputerBlockHost.HostException.class, () -> behaviour.setPaused(false));
		assertThrows(ComputerBlockHost.HostException.class, () -> behaviour.install(ComputerRuntimeTest.counter(2), ComputerRuntime.MemoryPolicy.RESET));
		assertEquals(before, behaviour.snapshot().orElseThrow());
		block.selection = original;
		for (int i = 0; i < 30; i++) block.tick();
		assertEquals(ComputationMachine.Status.SUCCEEDED, behaviour.snapshot().orElseThrow().machine().status());
		assertEquals(new DataValue.IntegerValue(1), behaviour.snapshot().orElseThrow().machine().memory().field("count"));
	}

	@Test void eachCallSamplesOnlyOneCoherentHardwareSelection() {
		var block = new Harness(); var behaviour = block.getBehaviour(ComputerBehaviour.TYPE);
		block.selection = profile("minecraft:toolsmith", 1);
		int before = block.samples;
		behaviour.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		assertEquals(before + 1, block.samples);
		before = block.samples; behaviour.begin(EMPTY); assertEquals(before + 1, block.samples);
		before = block.samples; block.tick(); assertEquals(before + 1, block.samples);
	}

	private static HardwareSelection profile(String profession, int level) {

		return new BiocomputingConfig(new ModConfigSpec.Builder()).select(new ResidentHardwareIdentity(
			ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse(profession), level), false);
	}

	@Test void legacyQueuedDocumentRetainsItsSourceAndLayoutAcrossSaveLoadAndApply() {
		var block=new Harness();var computer=block.getBehaviour(ComputerBehaviour.TYPE);
		computer.install(ComputerRuntimeTest.counter(1),ComputerRuntime.MemoryPolicy.RESET);computer.begin(EMPTY);computer.setPaused(true);
		var legacy=new CompoundTag();block.saveAdditional(legacy,RegistryAccess.EMPTY);legacy.remove(ComputerBehaviour.EDITOR_ROOT);
		var loaded=new Harness();loaded.loadWithComponents(legacy,RegistryAccess.EMPTY);loaded.tick();
		var restored=loaded.getBehaviour(ComputerBehaviour.TYPE); var next=document(ComputerRuntimeTest.counter(2),81);
        var firstInstallation = restored.installedProgram().orElseThrow().installation();
		assertEquals(ComputerRuntime.InstallResult.QUEUED,restored.installDocument(next,ComputerRuntime.MemoryPolicy.PRESERVE));
        assertEquals(firstInstallation,restored.installedProgram().orElseThrow().installation());
		var saved=new CompoundTag();loaded.saveAdditional(saved,RegistryAccess.EMPTY);
		var reloaded=new Harness();reloaded.loadWithComponents(saved,RegistryAccess.EMPTY);reloaded.tick();
		var runtime=reloaded.getBehaviour(ComputerBehaviour.TYPE);assertEquals(next,runtime.document().orElseThrow());
        assertEquals(firstInstallation,runtime.installedProgram().orElseThrow().installation());
		runtime.setPaused(false);for(int i=0;i<30;i++)reloaded.tick();
		assertEquals(next,runtime.document().orElseThrow());assertTrue(runtime.snapshot().orElseThrow().pending().isEmpty());
        assertNotEquals(firstInstallation,runtime.installedProgram().orElseThrow().installation());
	}

	@Test void mismatchedPendingMetadataIsNotPromotedOrOverwrittenOnFirstTick() {
		var block=new Harness();var computer=block.getBehaviour(ComputerBehaviour.TYPE);
		computer.installDocument(document(ComputerRuntimeTest.counter(1),10),ComputerRuntime.MemoryPolicy.RESET);
		var saved=new CompoundTag();block.saveAdditional(saved,RegistryAccess.EMPTY);
		var editor=saved.getCompound(ComputerBehaviour.EDITOR_ROOT);
		editor.put("Pending",ValueNbtCodec.encode(ProgramDocumentCodec.encode(document(ComputerRuntimeTest.counter(1),999)),LIMITS.archive()));
		var original=editor.copy();var restored=new Harness();restored.loadWithComponents(saved,RegistryAccess.EMPTY);restored.tick();
		var behaviour=restored.getBehaviour(ComputerBehaviour.TYPE);behaviour.document();
		var rewritten=new CompoundTag();restored.saveAdditional(rewritten,RegistryAccess.EMPTY);
		assertEquals(original,rewritten.get(ComputerBehaviour.EDITOR_ROOT));
		assertEquals(ComputerBlockHost.Code.INVALID_SAVE,behaviour.diagnostic().orElseThrow().code());
	}

	@Test void sameProgramQueuedLayoutFollowsApplyAndResetNotCanonicalEquality() {
		var block=new Harness();var computer=block.getBehaviour(ComputerBehaviour.TYPE);var program=ComputerRuntimeTest.counter(1);
		var a=document(program,10);var b=document(program,20);
		computer.installDocument(a,ComputerRuntime.MemoryPolicy.RESET);computer.begin(EMPTY);computer.setPaused(true);
		computer.installDocument(b,ComputerRuntime.MemoryPolicy.PRESERVE);assertEquals(b,computer.document().orElseThrow());
		computer.reset();assertEquals(a,computer.document().orElseThrow());
		computer.begin(EMPTY);computer.installDocument(b,ComputerRuntime.MemoryPolicy.PRESERVE);computer.setPaused(false);
		for(int i=0;i<30;i++)block.tick();assertEquals(b,computer.document().orElseThrow());
		var saved=new CompoundTag();block.saveAdditional(saved,RegistryAccess.EMPTY);
		var restored=new Harness();restored.loadWithComponents(saved,RegistryAccess.EMPTY);restored.tick();
		assertEquals(b,restored.getBehaviour(ComputerBehaviour.TYPE).document().orElseThrow());
	}

	@Test void unreachableSourceNodesSurviveInstallationAndDocumentRestoration() {
		var block=new Harness();var computer=block.getBehaviour(ComputerBehaviour.TYPE);var original=ComputerRuntimeTest.counter(1);
		var nodes=new LinkedHashMap<>(original.program().graph().nodes());nodes.put("unused",new ComputationGraph.Constant(new DataValue.IntegerValue(90)));
		var graph=new ComputationGraph(original.program().graph().inputs(),nodes,original.program().graph().outputs());
		var source=new ProgramArchive(new ProgramDefinition(graph,original.program().cells(),original.program().writes()),original.modules());
		var document=document(source,11);computer.installDocument(document,ComputerRuntime.MemoryPolicy.RESET);
		assertFalse(computer.snapshot().orElseThrow().program().program().graph().nodes().containsKey("unused"));
		var saved=new CompoundTag();block.saveAdditional(saved,RegistryAccess.EMPTY);var restored=new Harness();restored.loadWithComponents(saved,RegistryAccess.EMPTY);restored.tick();
		assertEquals(document,restored.getBehaviour(ComputerBehaviour.TYPE).document().orElseThrow());
	}

	private static ProgramDocument document(ProgramArchive archive,double x) {
		String id=archive.program().graph().nodes().keySet().iterator().next();
		return new ProgramDocument(archive,Map.of(ProgramDocument.graphId(null),Map.of(id,new ProgramDocument.Position(x,20))));
	}

	@Test void queuedPreserveFailureRetainsActiveDocumentAndMemoryAfterRestoration() {
		var block=new Harness();var behaviour=block.getBehaviour(ComputerBehaviour.TYPE);var original=document(ComputerRuntimeTest.counter(1),10);
		behaviour.installDocument(original,ComputerRuntime.MemoryPolicy.RESET);behaviour.begin(EMPTY);behaviour.setPaused(true);
		var graph=new ComputationGraph(Map.of(),Map.of("text",new ComputationGraph.Constant(new DataValue.TextValue("new"))),Map.of("next","text"));
		var incompatible=new ProgramArchive(new ProgramDefinition(graph,Map.of("count",new ProgramDefinition.Cell(DataType.Scalar.TEXT,new DataValue.TextValue("initial"))),List.of(new ProgramDefinition.Write("count","next"))),Map.of());
		behaviour.installDocument(document(incompatible,20),ComputerRuntime.MemoryPolicy.PRESERVE);
		var saved=new CompoundTag();block.saveAdditional(saved,RegistryAccess.EMPTY);var restored=new Harness();restored.loadWithComponents(saved,RegistryAccess.EMPTY);restored.tick();
		var runtime=restored.getBehaviour(ComputerBehaviour.TYPE);runtime.setPaused(false);for(int i=0;i<30;i++)restored.tick();
		assertTrue(runtime.snapshot().orElseThrow().installationDiagnostic().isPresent());
		var projected=com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerStatusView.of(runtime.snapshot().orElseThrow());
		assertInstanceOf(DataValue.RecordValue.class,projected.field("installation_diagnostic"));
		assertEquals(original,runtime.document().orElseThrow());
		assertEquals(new DataValue.IntegerValue(1),runtime.snapshot().orElseThrow().machine().memory().field("count"));
	}

	@Test void periodicControlSurvivesNativeSaveLoadAndRejectsStaleCommandsBeforeReadingInputs() {
		var block = new Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
		computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
		var nonce = UUID.randomUUID(); var session = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession(nonce);
		var action = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.CONFIGURE_PERIOD;
		long revision = computer.programRevision();
		var settings = new com.nobodiiiii.createbiotech.content.biocomputing.editor.PeriodicControlData(2, EMPTY).encode();
		var answer = session.apply(nonce, 1, revision, action, () -> settings, computer).orElseThrow();
		assertEquals("ok", answer.code()); assertTrue(answer.revision() > revision);
		assertEquals("stale", session.apply(nonce, 2, revision, action, () -> { throw new AssertionError("stale payload decoded"); }, computer).orElseThrow().code());
		assertTrue(session.apply(UUID.randomUUID(), 3, answer.revision(), action, () -> { throw new AssertionError("wrong nonce decoded"); }, computer).isEmpty());
		block.tick(); long stableRevision = computer.programRevision();
		var saved = new CompoundTag(); block.saveAdditional(saved, RegistryAccess.EMPTY);
		var restored = new Harness(); restored.loadWithComponents(saved, RegistryAccess.EMPTY);
		for (int i = 0; i < 30; i++) restored.tick();
		assertTrue(((DataValue.IntegerValue)restored.getBehaviour(ComputerBehaviour.TYPE).snapshot().orElseThrow().machine().memory().field("count")).value() > 1);
		block.tick(); assertEquals(stableRevision, computer.programRevision());
	}

	private static ComputerBlockEntity nativeComputer() throws ReflectiveOperationException {
		var constructor = ComputerBlockEntity.class.getDeclaredConstructor(BlockEntityType.class, BlockPos.class, BlockState.class);
		constructor.setAccessible(true);
		return constructor.newInstance(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
	}

	@Test void periodicReadDisableAndClearRespectAuthorityWithoutCancellingTheActiveEvent() {
		var block=new Harness(); var computer=block.getBehaviour(ComputerBehaviour.TYPE);
		computer.install(ComputerRuntimeTest.counter(1),ComputerRuntime.MemoryPolicy.RESET);
		computer.configurePeriodic(1,EMPTY); block.tick(); var event=computer.snapshot().orElseThrow().machine();
		assertEquals(ComputationMachine.Status.RUNNING,event.status());
		var nonce=UUID.randomUUID(); var session=new com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession(nonce);
		var read=com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.READ_PERIOD;
		var disable=com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.DISABLE_PERIOD;
		var clear=com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession.Action.CLEAR_PERIOD;
		long revision=computer.programRevision(); var before=computer.snapshot();
		assertEquals("ok",session.apply(nonce,1,revision,read,()->{throw new AssertionError("read decoded input");},computer).orElseThrow().code());
		assertEquals(before,computer.snapshot()); assertEquals(revision,computer.programRevision());
		block.available=false;
		assertEquals("unavailable",session.apply(nonce,2,revision,disable,()->EMPTY,computer).orElseThrow().code());
		assertEquals(before,computer.snapshot()); assertEquals(revision,computer.programRevision()); block.available=true;
		assertEquals("ok",session.apply(nonce,3,revision,disable,()->EMPTY,computer).orElseThrow().code());
		assertEquals(event,computer.snapshot().orElseThrow().machine()); assertFalse(computer.snapshot().orElseThrow().periodic().orElseThrow().enabled());
		long disabledRevision=computer.programRevision(); assertTrue(disabledRevision>revision);
		assertTrue(session.apply(nonce,3,disabledRevision,clear,()->EMPTY,computer).isEmpty());
		assertTrue(computer.snapshot().orElseThrow().periodic().isPresent());
		assertEquals("ok",session.apply(nonce,4,disabledRevision,clear,()->EMPTY,computer).orElseThrow().code());
		assertEquals(event,computer.snapshot().orElseThrow().machine()); assertTrue(computer.snapshot().orElseThrow().periodic().isEmpty());
		for(int i=0;i<20;i++)block.tick();
		assertEquals(new DataValue.IntegerValue(1),computer.snapshot().orElseThrow().machine().memory().field("count"));
	}

	@Test void periodicSettingsTooLargeForCompleteReplyLeaveConfigurationAndRevisionUntouched() {
		var block=new Harness(); var computer=block.getBehaviour(ComputerBehaviour.TYPE);
		var graph=new ComputationGraph(Map.of("data",DataType.Scalar.TEXT),Map.of("read",new ComputationGraph.Input("data")),Map.of("value","read"));
		computer.install(new ProgramArchive(new ProgramDefinition(graph,Map.of(),List.of()),Map.of()),ComputerRuntime.MemoryPolicy.RESET);
		computer.configurePeriodic(5,new DataValue.RecordValue(Map.of("data",new DataValue.TextValue("x"))));
		var before=computer.snapshot(); long revision=computer.programRevision();
		assertThrows(IllegalArgumentException.class,()->computer.configurePeriodic(2,new DataValue.RecordValue(Map.of("data",new DataValue.TextValue("x".repeat(30_000))))));
		assertEquals(before,computer.snapshot()); assertEquals(revision,computer.programRevision());
	}

	static final class Harness extends SmartBlockEntity {
		boolean available = true;
		int changes;
		int samples;
		HardwareSelection selection = new HardwareSelection(HardwareSelection.Status.READY,
			Optional.of(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 1)), "parser", LIMITS);
		Harness() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
		@Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
			behaviours.add(new ComputerBehaviour(this, () -> available, () -> { samples++; return selection; }));
		}
		@Override public void setChanged() { changes++; }
	}
}
