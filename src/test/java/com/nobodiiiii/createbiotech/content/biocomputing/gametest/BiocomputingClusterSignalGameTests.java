package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingClusterXBusGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

/** Actual block/menu/tick/save verification, not a rendered-client or cross-process acceptance claim. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingClusterSignalGameTests {
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void assemblySleepUsesEachMembersActivityClockNotItsHardwareInstructionSpeed(GameTestHelper helper) {
        var f = fixture(helper, false, false, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
        var p = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("delay", "ticks"),
            new FlowProgram.XBusStatus("awake", "x0", "busy"))).withAssembly();
        var input = new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(40)));
        var hardwareA = f.a().hardware(); var hardwareB = f.b().hardware();
        helper.assertTrue(!hardwareA.equals(hardwareB), "Fixture has genuinely different resident profiles");
        helper.assertTrue(hardwareA.limits().hardware().workPerSlice() != hardwareB.limits().hardware().workPerSlice(), "Fixture has different per-tick instruction allowances");
        installThroughMenu(helper, f, "A", document(p, input)); installThroughMenu(helper, f, "B", document(p, input));
        f.a().step(); f.b().step();
        var firstA = f.a().flowSnapshot().orElseThrow(); var firstB = f.b().flowSnapshot().orElseThrow();
        helper.assertValueEqual(firstA.state().sleepTicks(), 40L, "Sleep admission does not spend a tick");
        helper.assertValueEqual(firstB.state().sleepTicks(), 40L, "Ordinary resident uses the same tick unit");
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow(), firstA, "Paused member keeps exact continuation");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow(), firstB, "Paused neighbour does not catch up");
            var a = reloadComputer(helper, f.config().members().get("A").position()); a.document();
            var b = reloadComputer(helper, f.config().members().get("B").position()); b.document();
            helper.assertValueEqual(a.flowSnapshot().orElseThrow(), firstA, "Reload restores admitted registers/time");
            helper.assertValueEqual(b.flowSnapshot().orElseThrow(), firstB, "Each owner restores separately");
            a.setPaused(false); b.setPaused(false);
            helper.runAfterDelay(4, () -> {
                var activeA = a.flowSnapshot().orElseThrow().state(); var activeB = b.flowSnapshot().orElseThrow().state();
                helper.assertTrue(activeA.sleepTicks() < 40 && activeA.sleepTicks() > 0, "Host clock progressed");
                helper.assertValueEqual(activeA.sleepTicks(), activeB.sleepTicks(), "Instruction speed cannot multiply local ticks");
                helper.assertValueEqual(activeA.work(), 1L, "Waiting consumes no further CPU");
                helper.assertValueEqual(activeB.work(), 1L, "No budget is borrowed from the neighbour");
                a.setPaused(true); var heldA = a.flowSnapshot().orElseThrow();
                helper.runAfterDelay(3, () -> {
                    helper.assertValueEqual(a.flowSnapshot().orElseThrow(), heldA, "Pausing A never advances A's local clock");
                    helper.assertTrue(b.flowSnapshot().orElseThrow().state().sleepTicks() < activeB.sleepTicks(), "B remains independently active");
                    b.setPaused(true); var heldB = b.flowSnapshot().orElseThrow();
                    a.step(); helper.assertValueEqual(a.flowSnapshot().orElseThrow().state().sleepTicks(), heldA.state().sleepTicks() - 1, "Sleep step means one local tick");
                    helper.assertValueEqual(b.flowSnapshot().orElseThrow(), heldB, "Debug step does not affect neighbouring machine");
                    a.setPaused(false); b.setPaused(false);
                    helper.runAfterDelay(42, () -> {
                        for (var member : List.of(a, b)) {
                            var done = member.flowSnapshot().orElseThrow().state();
                            helper.assertValueEqual(done.status(), FlowRuntime.Status.COMPLETE, "Normal activity wakes and runs following instruction");
                            helper.assertValueEqual(done.assembly().orElseThrow().pc(), 2, "Real register PC completes");
                            helper.assertValueEqual(done.work(), 2L, "Only Sleep admission and status instruction are charged");
                        }
                        helper.assertValueEqual(a.hardware(), hardwareA, "Librarian keeps its profile");
                        helper.assertValueEqual(b.hardware(), hardwareB, "Ordinary resident keeps its profile"); helper.succeed();
                    });
                });
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void assemblyRendezvousWaitsThroughDisconnectedWireAndAllMemberReloads(GameTestHelper helper) {
        var f = fixture(helper, false, false, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
        var write = new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))).withAssembly();
        var read = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x1", "packet", BiocomputingFlowFixture.PACKET))).withAssembly();
        installThroughMenu(helper, f, "A", document(write, BiocomputingFlowFixture.inputs(12)));
        installThroughMenu(helper, f, "B", document(read, new DataValue.RecordValue(Map.of())));
        f.player().moveTo(net.minecraft.world.phys.Vec3.atCenterOf(f.controller().getBlockPos().west()));
        var wire = f.config().wires().get("data");
        var disconnected = new ClusterConfiguration(f.config().members(), Map.of(), Map.of(), Map.of("data", new XBusWire(wire.left(), wire.right(), false)));
        configureThroughMenu(helper, f.controller(), f.player(), disconnected);
        var hardwareA = f.a().hardware(); var hardwareB = f.b().hardware(); f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(5, () -> {
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Disconnected sender has no implicit receiver");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_READ, "Disconnected reader retains its PC");
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().work(), 1L, "Waiting does not spend more cycles");
            f.a().setPaused(true); f.b().setPaused(true);
            var a = f.a().flowSnapshot().orElseThrow(); var b = f.b().flowSnapshot().orElseThrow();
            var left = reloadComputer(helper, f.config().members().get("A").position());
            var right = reloadComputer(helper, f.config().members().get("B").position()); left.document(); right.document();
            helper.assertValueEqual(left.flowSnapshot().orElseThrow(), a, "Paused write restores the same registers and operation");
            helper.assertValueEqual(right.flowSnapshot().orElseThrow(), b, "Paused read restores without making a payload");
            configureThroughMenu(helper, f.controller(), f.player(), f.config()); left.setPaused(false); right.setPaused(false);
            helper.runAfterDelay(6, () -> {
                var sent = left.flowSnapshot().orElseThrow(); var received = right.flowSnapshot().orElseThrow();
                helper.assertValueEqual(received.state().locals(), BiocomputingFlowFixture.inputs(12), "Publicly re-enabled real wire transfers the original value");
                helper.assertValueEqual(sent.state().status(), FlowRuntime.Status.COMPLETE, "Sender resumes in the new VM");
                helper.assertValueEqual(received.state().status(), FlowRuntime.Status.COMPLETE, "Reader resumes in the new VM");
                helper.assertValueEqual(sent.state().assembly().orElseThrow().pc(), 1, "Send advances exactly once");
                helper.assertValueEqual(received.state().assembly().orElseThrow().pc(), 1, "Read advances exactly once");
                helper.assertValueEqual(sent.state().work(), 1L, "Each member pays its own admission cycle");
                helper.assertValueEqual(received.state().work(), 1L, "No controller-funded computation");
                helper.assertValueEqual(left.hardware(), hardwareA, "Librarian hardware remains independent");
                helper.assertValueEqual(right.hardware(), hardwareB, "Ordinary resident retains independent hardware");
                helper.assertTrue(f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "No buffered transport substituted");
                helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void playerMenuSignalsExchangeFrozenValuesAndSurviveAllThreeBlockReloads(GameTestHelper helper) {
        verifySignals(helper, false);
    }
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void assemblySignalsUseFrozenRoundsAcrossDifferentResidentsAndBlockReloads(GameTestHelper helper) {
        verifySignals(helper, true);
    }
    private static void verifySignals(GameTestHelper helper, boolean assembly) {
        var f = assembly ? fixture(helper, false, false, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1) : fixture(helper, false, false);
        var program = assembly ? signalProgram(false).withAssembly() : signalProgram(false);
        var hardwareA = f.a().hardware(); var hardwareB = f.b().hardware();
        installThroughMenu(helper, f, "A", document(program, initial(2))); installThroughMenu(helper, f, "B", document(program, initial(7)));
        var config = configuration(f); f.player().moveTo(net.minecraft.world.phys.Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), config); f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(8, () -> {
            var a = f.a().flowSnapshot().orElseThrow(); var b = f.b().flowSnapshot().orElseThrow();
            helper.assertValueEqual(a.state().locals().field("sample"), new DataValue.IntegerValue(7), "A samples B's previous-round output");
            helper.assertValueEqual(b.state().locals().field("sample"), new DataValue.IntegerValue(2), "B samples before A's read clears its output");
            helper.assertValueEqual(a.state().work(), 2L, "Controller and block tick do not duplicate execution");
            helper.assertValueEqual(b.state().work(), 2L, "Each Computer owns its work");
            helper.assertValueEqual(a.state().assembly().isPresent(), assembly, "Actual menu installation preserves the execution target");
            helper.assertValueEqual(b.state().assembly().isPresent(), assembly, "No hidden legacy fallback on ordinary hardware");
            helper.assertValueEqual(f.a().hardware(), hardwareA, "Librarian resource profile remains independent");
            helper.assertValueEqual(f.b().hardware(), hardwareB, "Ordinary member cannot borrow its neighbour's profile");
            var level = helper.getLevel(); var pos = f.controller().getBlockPos(); var blockState = f.controller().getBlockState();
            var save = f.controller().saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(pos);
            var restored = (ClusterControllerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, blockState, save, level.registryAccess())); level.setBlockEntity(restored);
            var left = reloadComputer(helper, config.members().get("A").position()); var right = reloadComputer(helper, config.members().get("B").position());
            helper.runAfterDelay(8, () -> {
                helper.assertValueEqual(restored.routingState().orElseThrow().configuration(), config, "Real controller keeps typed explicit wires");
                helper.assertValueEqual(left.flowSnapshot().orElseThrow(), a, "Restored A does not replay signal operations");
                helper.assertValueEqual(right.flowSnapshot().orElseThrow(), b, "Restored B retains independent state");
                helper.assertTrue(restored.routingState().orElseThrow().mailbox().queued().isEmpty(), "No signal mailbox introduced"); helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void assemblyStatusObservesRealSenderWithoutConsumingOrDrivingItsPin(GameTestHelper helper) {
        var f = fixture(helper, false, false);
        var probe = new FlowProgram(Map.of(), List.of(new FlowProgram.XBusStatus("before_send", "x0", "initial_busy"),
            new FlowProgram.XBusStatus("status", "x0", "busy"))).withAssembly();
        var send = new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x1", "packet")));
        installThroughMenu(helper, f, "A", document(probe, new DataValue.RecordValue(Map.of())));
        installThroughMenu(helper, f, "B", document(send, BiocomputingFlowFixture.inputs(12)));
        f.player().moveTo(net.minecraft.world.phys.Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), f.config());
        helper.assertValueEqual(f.a().sampleFlowSignals().get("x0"), 0, "Paused observer has an inactive XBus, not a live debug bypass");
        // Both peers must be active: the existing pause contract ends a writer's wait against a paused peer.
        // Round one observes idle while B enters WRITE; round two observes the frozen occupied state.
        f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(4, () -> {
            var waiting = f.b().flowSnapshot().orElseThrow();
            helper.assertValueEqual(waiting.state().status(), FlowRuntime.Status.WAITING_WRITE, "Real sender reached its rendezvous wait");
            var observed = f.a().flowSnapshot().orElseThrow();
            helper.assertValueEqual(observed.state().locals().field("initial_busy"), new DataValue.IntegerValue(0), "First unified sample precedes sender execution");
            helper.assertValueEqual(observed.state().locals().field("busy"), new DataValue.IntegerValue(15), "New VM observes actual sender occupancy");
            helper.assertValueEqual(observed.state().work(), 2L, "Each status consumes one bounded local cycle");
            helper.assertTrue(observed.state().assembly().isPresent() && observed.receipts().isEmpty() && observed.state().signals().outputs().isEmpty(), "No message, drive or graph frame is invented");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow(), waiting, "Observation does not execute or consume the writer");
            helper.assertValueEqual(waiting.state().work(), 1L, "Status never wakes or advances the writer");
            helper.assertValueEqual(waiting.state().locals(), BiocomputingFlowFixture.inputs(12), "Original payload stays owned by its sender");
            var restored = reloadComputer(helper, f.config().members().get("A").position()); restored.document();
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), observed, "Device continuation restores without replaying the query");
            f.b().setPaused(true);
            helper.assertValueEqual(restored.sampleFlowSignals().get("x0"), 0, "Paused sender no longer occupies the bus");
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void brokenRealShellDoesNotPauseLocalSleepButMakesSignalReadUnavailable(GameTestHelper helper) {
        brokenShellSleep(helper, false);
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void brokenRealShellDoesNotPauseAssemblySleepOrInventAZeroSignal(GameTestHelper helper) {
        brokenShellSleep(helper, true);
    }
    private static void brokenShellSleep(GameTestHelper helper, boolean assembly) {
        var f = fixture(helper, false, false); var program = assembly ? signalProgram(true).withAssembly() : signalProgram(true);
        installThroughMenu(helper, f, "A", document(program, initial(2))); installThroughMenu(helper, f, "B", document(program, initial(7)));
        f.player().moveTo(net.minecraft.world.phys.Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), configuration(f)); f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.SLEEPING, "Actual member entered local sleep");
            helper.getLevel().setBlockAndUpdate(f.min(), Blocks.AIR.defaultBlockState());
            helper.assertValueEqual(f.controller().enclosure().status(), EnclosureScanner.Status.OPEN_SHELL, "Real casing break invalidates the enclosure");
            helper.runAfterDelay(10, () -> {
                var state = f.a().flowSnapshot().orElseThrow().state();
                helper.assertValueEqual(state.status(), FlowRuntime.Status.FAILED, "Local clock wakes even without a sound Cluster");
                helper.assertTrue(!state.locals().fields().containsKey("sample"), "Unknown source is not zero");
                helper.assertValueEqual(state.signals().outputs(), Map.of("p0", 2), "Failed read does not partially clear local drive"); helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void closedSignalGrantFailsReadWithoutRunningPausedPeer(GameTestHelper helper) {
        var f = fixture(helper, false, false);
        var read = new FlowProgram(Map.of(), List.of(new FlowProgram.SignalRead("read", "p0", "sample")));
        installThroughMenu(helper, f, "A", document(read, new DataValue.RecordValue(Map.of())));
        installThroughMenu(helper, f, "B", document(signalProgram(true), initial(7)));
        f.player().moveTo(net.minecraft.world.phys.Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), configuration(f)); f.b().step(); var peer = f.b().flowSnapshot().orElseThrow();
        f.b().disableEndpoint(); f.a().setPaused(false);
        helper.runAfterDelay(5, () -> {
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.FAILED, "Closed explicit grant propagates unavailability");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow(), peer, "Sampling never executes the paused writer");
            helper.assertValueEqual(f.controller().wireDiagnostics().get("voltage"), "signal_closed", "Player can distinguish a closed grant"); helper.succeed();
        });
    }
    private static FlowProgram signalProgram(boolean sleep) {
        var steps = new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.SignalWrite("drive", "p0", "level"));
        if (sleep) steps.add(new FlowProgram.Sleep("delay", "ticks")); steps.add(new FlowProgram.SignalRead("read", "p0", "sample"));
        return new FlowProgram(Map.of("level", DataType.Scalar.INTEGER, "ticks", DataType.Scalar.INTEGER), steps);
    }
    private static DataValue.RecordValue initial(long value) { return new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(value), "ticks", new DataValue.IntegerValue(5))); }
    private static ProgramDocument document(FlowProgram p, DataValue.RecordValue input) {
        return new ProgramDocument(p.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.fromProgram(p, input)));
    }
    private static ClusterConfiguration configuration(Fixture f) {
        return new ClusterConfiguration(f.config().members(), Map.of(), Map.of(), Map.of(), Map.of("voltage",
            new SimpleIoWire(new SimpleIoWire.Pin("A", "left", "p0"), new SimpleIoWire.Pin("B", "right", "p0"), true)));
    }
}
