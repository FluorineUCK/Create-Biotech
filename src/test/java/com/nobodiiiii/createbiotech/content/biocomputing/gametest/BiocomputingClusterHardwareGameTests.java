package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingClusterXBusGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.*;

/** Real captured residents, physical shell and registered player request/response path. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingClusterHardwareGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void mixedThroughputsReadTheSameFrozenRoundInsteadOfEachOthersClearedOutput(GameTestHelper helper) {
        var f = mixed(helper);
        var program = new FlowProgram(Map.of("private_value", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.SignalWrite("drive", "p0", "private_value"),
            new FlowProgram.SignalRead("sample", "p0", "received")));
        installThroughMenu(helper, f, "A", document(program, 2));
        installThroughMenu(helper, f, "B", document(program, 7));
        configureSignal(helper, f);
        helper.assertTrue(f.a().hardware().limits().hardware().workPerSlice() < f.b().hardware().limits().hardware().workPerSlice(), "Use genuinely different resident budgets");
        f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(4, () -> {
            var a = f.a().flowSnapshot().orElseThrow(); var b = f.b().flowSnapshot().orElseThrow();
            helper.assertValueEqual(a.state().locals().field("received"), new DataValue.IntegerValue(7), "Slow member samples fast member's previous-round drive");
            helper.assertValueEqual(b.state().locals().field("received"), new DataValue.IntegerValue(2), "Fast member does not see a drive cleared earlier in this same round");
            for (var snapshot : List.of(a, b)) {
                helper.assertValueEqual(snapshot.state().status(), FlowRuntime.Status.COMPLETE, "Both residents can execute ordinary signal operations");
                helper.assertValueEqual(snapshot.state().work(), 2L, "Exactly the two executed operations, no pooled or duplicate work");
                helper.assertTrue(snapshot.state().signals().outputs().isEmpty(), "Read changes only its own pin to input mode");
                helper.assertTrue(snapshot.receipts().isEmpty(), "Continuous signal is not an XBus handoff");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pausedSlowSignalDriverSurvivesReloadAndDoesNotRunToServeFastReader(GameTestHelper helper) {
        var f = mixed(helper);
        var write = new FlowProgram(Map.of("private_value", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.SignalWrite("drive", "p0", "private_value")));
        var read = new FlowProgram(Map.of("private_value", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.SignalRead("sample", "p0", "received")));
        installThroughMenu(helper, f, "A", document(write, 45));
        installThroughMenu(helper, f, "B", document(read, 99));
        configureSignal(helper, f); f.a().step();
        var beforeA = f.a().flowSnapshot().orElseThrow(); var beforeB = f.b().flowSnapshot().orElseThrow();
        helper.assertTrue(beforeA.state().paused() && beforeB.state().paused(), "Only the explicit single step drives an output");
        helper.assertValueEqual(beforeA.state().signals().outputs(), Map.of("p0", 15), "Paused driver retains its redstone-limited output");
        helper.assertValueEqual(beforeA.state().locals().field("private_value"), new DataValue.IntegerValue(45), "Electrical limiting does not edit source data");
        var a = reloadComputer(helper, f.config().members().get("A").position());
        var b = reloadComputer(helper, f.config().members().get("B").position());
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(a.flowSnapshot().orElseThrow(), beforeA, "Reload and sampling never execute the paused slow member");
            helper.assertValueEqual(b.flowSnapshot().orElseThrow(), beforeB, "Fast peer remains independently paused");
            b.setPaused(false);
            helper.runAfterDelay(3, () -> {
                var result = b.flowSnapshot().orElseThrow();
                helper.assertValueEqual(result.state().locals().field("received"), new DataValue.IntegerValue(15), "Read restored continuous output without resuming its owner");
                helper.assertValueEqual(result.state().locals().field("private_value"), new DataValue.IntegerValue(99), "No shared RAM or overwritten peer input");
                helper.assertValueEqual(result.state().work(), 1L, "Only the fast reader spent work");
                helper.assertValueEqual(a.flowSnapshot().orElseThrow(), beforeA, "Reading does not consume or clear another Computer's output");
                helper.assertTrue(result.receipts().isEmpty() && f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "Neither receipts nor a controller mailbox implement Simple I/O");
                helper.succeed();
            });
        });
    }

    private static void configureSignal(GameTestHelper helper, Fixture f) {
        f.player().moveTo(net.minecraft.world.phys.Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), new ClusterConfiguration(f.config().members(), Map.of(), Map.of(), Map.of(), Map.of("voltage",
            new SimpleIoWire(new SimpleIoWire.Pin("A", "left", "p0"), new SimpleIoWire.Pin("B", "right", "p0"), true))));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void memberMenuPublishesEachResidentsEffectiveHardwareWithoutPrivateState(GameTestHelper helper) {
        var f = mixed(helper);
        var beforeA = f.a().flowSnapshot(); var beforeB = f.b().flowSnapshot();
        var view = refresh(helper, f);
        var limits = CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded()).archive();
        var encoded = (DataValue.RecordValue)ProgramEditorWire.decodeValue(ClusterControlWire.encodeView(view, limits), limits);
        var candidates = ((DataValue.ListValue)encoded.field("candidates")).values();
        helper.assertValueEqual(candidates.size(), 2, "Two actual shell-contacting residents");
        for (var value : candidates) {
            var candidate = (DataValue.RecordValue)value;
            helper.assertTrue(candidate.field("hardware") instanceof DataValue.RecordValue,
                "Actual player response must contain a hardware summary for each accessible Computer");
        }
        for (String name : List.of("A", "B")) {
            var position = f.config().members().get(name).position();
            var summary = view.candidates().stream().filter(c -> c.position().equals(position)).findFirst().orElseThrow().hardware().orElseThrow();
            var actual = name.equals("A") ? f.a().hardware() : f.b().hardware();
            helper.assertValueEqual(summary.resident(), actual.resident(), "Public resident identity, profession and level belong to this member");
            helper.assertValueEqual(summary.profile(), name.equals("A") ? "control" : "speed", "Distinct effective preset");
            helper.assertValueEqual(summary.effective().orElseThrow().resources(), actual.limits().hardware(), "Effective capacity and speed are not summed or swapped");
            helper.assertValueEqual(summary.effective().orElseThrow().pins(), actual.limits().pins(), "Both physical pin inventories survive menu bytes");
        }
        helper.assertValueEqual(f.a().flowSnapshot(), beforeA, "Inspection never advances or installs A");
        helper.assertValueEqual(f.b().flowSnapshot(), beforeB, "Inspection never advances or installs B");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void hardwareSummaryDisappearsWhenMemberAccessIsDenied(GameTestHelper helper) {
        var f = mixed(helper); var border = helper.getLevel().getWorldBorder();
        double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
        try {
            var controller = f.controller().getBlockPos();
            border.setCenter(controller.getX() + .5, controller.getZ() + .5); border.setSize(1);
            var view = refresh(helper, f);
            helper.assertValueEqual(view.candidates().size(), 2, "Configured members retain unavailable diagnostics");
            for (var c : view.candidates()) {
                helper.assertValueEqual(c.diagnostic(), "access_denied", "Member access checked independently of controller access");
                helper.assertTrue(c.hardware().isEmpty() && c.identity().isEmpty() && c.endpoints().isEmpty(), "No private hardware fallback on denied access");
                helper.assertTrue(!c.editable() && !c.selectable(), "Summary does not grant physical access");
            }
        } finally { border.setCenter(x, z); border.setSize(size); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void invalidPinsShowUnavailableHardwareNotFallbackServerCapacities(GameTestHelper helper) {
        var f = mixed(helper); ModConfigSpec.ConfigValue<List<String>> pins = setting("xbusPins"); var original = pins.get();
        var beforeA = f.a().flowSnapshot();
        try {
            pins.set(List.of("p0")); // Individually valid list, but overlaps the actual Simple I/O inventory.
            var view = refresh(helper, f);
            var a = view.candidates().stream().filter(c -> c.position().equals(f.config().members().get("A").position())).findFirst().orElseThrow();
            helper.assertValueEqual(a.hardware().orElseThrow().status(), HardwareSelection.Status.INVALID_PINS, "Real config failure is readable");
            helper.assertTrue(a.hardware().orElseThrow().effective().isEmpty(), "Unavailable selection's global ceilings must not be advertised as usable capacity");
            helper.assertTrue(a.editable() && !a.selectable(), "Diagnosis retains physical editing but not binding");
            helper.assertValueEqual(f.a().flowSnapshot(), beforeA, "Inspection does not reset the installed document/checkpoint");
        } finally { pins.set(original); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void samePlayerDocumentFitsProgramMemberButNotFasterSmallerMember(GameTestHelper helper) {
        var f = mixed(helper);
        var steps = new ArrayList<FlowProgram.Step>();
        long smallCapacity = f.b().hardware().limits().hardware().programUnits();
        helper.assertTrue(smallCapacity < f.a().hardware().limits().hardware().programUnits(), "Real profiles have horizontal capacity tradeoffs");
        for (int i = 0; i <= smallCapacity; i++) steps.add(new FlowProgram.SignalWrite("drive" + i, "p0", "private_value"));
        var document = document(new FlowProgram(Map.of("private_value", DataType.Scalar.INTEGER), steps), 11);
        installThroughMenu(helper, f, "A", document);
        var before = f.b().flowSnapshot(); var previousDocument = f.b().document();
        var replies = new ArrayList<ProgramEditorResponse>();
        f.player().connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(),
            new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), f.player(),
            net.minecraft.server.network.CommonListenerCookie.createInitial(f.player().getGameProfile(), false)) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof ProgramEditorResponse reply) {
                    var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
                    try { reply.write(buffer); replies.add(new ProgramEditorResponse(buffer)); }
                    finally { buffer.release(); }
                }
            }
        };
        requestInstallThroughMenu(helper, f.config().members().get("B").position(), f.player(), document);
        helper.assertValueEqual(replies.size(), 1, "Actual install request was handled, not silently fenced");
        helper.assertValueEqual(replies.getFirst().code(), "invalid", "Explicit rejection reply");
        helper.assertTrue(replies.getFirst().detail().startsWith("PROGRAM_LIMIT:"), "Capacity, not permission, nonce or decoding, rejected the same document");
        helper.assertValueEqual(f.b().flowSnapshot(), before, "Rejected install leaves smaller member's checkpoint unchanged");
        helper.assertValueEqual(f.b().document(), previousDocument, "Rejected install does not replace its editable document");
        helper.assertValueEqual(f.a().document().orElseThrow(), document, "Same public document is installed on the larger member");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void waitingSlowMemberDoesNotThrottleOrDonateBudgetToFastMember(GameTestHelper helper) {
        var f = mixed(helper);
        var loop = new FlowProgram(Map.of("private_value", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end")));
        installThroughMenu(helper, f, "B", document(loop, 29));
        f.a().step(); f.a().setPaused(false); f.b().setPaused(false);
        var waiting = f.a().flowSnapshot();
        helper.assertValueEqual(waiting.orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Slow member actually blocks at an unconnected write");
        verifyTicks(helper, f, 0, f.b().hardware().limits().hardware().workPerSlice(), 3, () -> {
            helper.assertValueEqual(f.a().flowSnapshot(), waiting, "Polling does not consume or invent a reader");
            helper.assertTrue(f.a().flowSnapshot().orElseThrow().receipts().isEmpty(), "No buffered acknowledgement");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void differentThroughputsDoNotAccelerateSleepAndPauseIsLocal(GameTestHelper helper) {
        var f = mixed(helper);
        var sleep = new FlowProgram(Map.of("private_value", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Sleep("sleep", "private_value"), new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end")));
        installThroughMenu(helper, f, "A", document(sleep, 10));
        installThroughMenu(helper, f, "B", document(sleep, 10));
        f.a().step(); f.b().step();
        helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().sleepTicks(), 10L, "Slow member entered sleep");
        helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().sleepTicks(), 10L, "Fast member entered identical sleep");
        f.a().setPaused(false); f.b().setPaused(false);
        verifySleepTicks(helper, f, 3, () -> {
            f.a().setPaused(true); var pausedA = f.a().flowSnapshot(); long bSleep = f.b().flowSnapshot().orElseThrow().state().sleepTicks();
            helper.runAfterDelay(1, () -> {
                drainSameTick(f);
                helper.assertValueEqual(f.a().flowSnapshot(), pausedA, "Pausing A freezes only A's active sleep ticks");
                helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().sleepTicks(), bSleep - 1, "B still advances one local tick");
                f.b().setPaused(true); var pausedB = f.b().flowSnapshot();
                var restoredA = reloadComputer(helper, f.config().members().get("A").position());
                var restoredB = reloadComputer(helper, f.config().members().get("B").position());
                restoredA.communicationIdentity(); restoredB.communicationIdentity();
                helper.assertValueEqual(restoredA.flowSnapshot(), pausedA, "Save/load preserves slow member's remaining sleep");
                helper.assertValueEqual(restoredB.flowSnapshot(), pausedB, "Save/load preserves a different remaining sleep on B");
                helper.succeed();
            });
        });
    }

    private static void verifySleepTicks(GameTestHelper helper, Fixture f, int remaining, Runnable after) {
        drainSameTick(f);
        if (remaining == 0) { after.run(); return; }
        long a = f.a().flowSnapshot().orElseThrow().state().sleepTicks(), b = f.b().flowSnapshot().orElseThrow().state().sleepTicks();
        helper.runAfterDelay(1, () -> {
            drainSameTick(f);
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().sleepTicks(), a - 1, "Slow clock ticks once");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().sleepTicks(), b - 1, "More instruction rounds cannot accelerate sleep");
            verifySleepTicks(helper, f, remaining - 1, after);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void removedMemberPinKeepsItsWaitingCheckpointAndHasItsOwnDiagnostic(GameTestHelper helper) {
        var f = mixed(helper);
        configureThroughMenu(helper, f.controller(), f.player(), f.config());
        f.a().step(); f.b().step();
        var beforeA = f.a().flowSnapshot(); var beforeB = f.b().flowSnapshot();
        ModConfigSpec.ConfigValue<List<String>> setting = setting("xbusPins");
        var previous = setting.get();
        try {
            setting.set(List.of("x1"));
            helper.assertTrue(f.a().communicationIdentity().isEmpty(), "Removed x0 invalidates only its installed writer");
            helper.assertValueEqual(f.a().diagnostic().orElseThrow().code(), ComputerBlockHost.Code.HARDWARE_MISMATCH, "Not a missing program or capacity failure");
            var view = refresh(helper, f);
            var failed = view.candidates().stream().filter(c -> c.position().equals(f.config().members().get("A").position())).findFirst().orElseThrow();
            helper.assertValueEqual(failed.diagnostic(), "hardware_mismatch", "Player sees the specific missing-pin reason");
            helper.assertValueEqual(failed.hardware().orElseThrow().effective().orElseThrow().pins().xbus(), List.of("x1"), "Summary reports the reduced current inventory, not the installed program's requirements");
            helper.assertTrue(failed.editable() && !failed.selectable(), "Hardware failure keeps the explicit editor route");
            helper.assertValueEqual(f.controller().configure(f.config(), f.player()), "hardware_mismatch", "Configuration agrees with the player diagnosis");
            helper.assertTrue(f.b().communicationIdentity().isPresent(), "Other profile keeps its pins");
            helper.assertValueEqual(f.b().flowSnapshot(), beforeB, "Peer wait remains unchanged");
            var restored = reloadComputer(helper, f.config().members().get("A").position());
            helper.assertTrue(restored.communicationIdentity().isEmpty(), "Cold load still rejects absent hardware");
            helper.assertValueEqual(restored.flowSnapshot(), beforeA, "Validated waiting checkpoint survives while the pin is absent");
        } finally { setting.set(previous); }
        var restored = reloadComputer(helper, f.config().members().get("A").position());
        helper.assertTrue(restored.communicationIdentity().isPresent(), "Restored hardware makes the same identity usable");
        helper.assertValueEqual(restored.flowSnapshot(), beforeA, "No implicit reset or wire handoff on restore");
        helper.assertValueEqual(f.controller().routingState().orElseThrow().configuration(), f.config(), "Previously installed wire is retained");
        f.controller().tick();
        helper.assertValueEqual(restored.flowSnapshot(), beforeA, "A retained wire never executes its paused writer");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wiredSignalPreservesHardwareAndCapacityFailureReasons(GameTestHelper helper) {
        var f = mixed(helper);
        var program = new FlowProgram(Map.of("private_value", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.SignalWrite("drive", "p0", "private_value")));
        installThroughMenu(helper, f, "A", document(program, 2));
        installThroughMenu(helper, f, "B", document(program, 7));
        f.player().moveTo(net.minecraft.world.phys.Vec3.atCenterOf(f.controller().getBlockPos().west()));
        var configuration = new ClusterConfiguration(f.config().members(), Map.of(), Map.of(), Map.of(), Map.of("voltage",
            new SimpleIoWire(new SimpleIoWire.Pin("A", "left", "p0"), new SimpleIoWire.Pin("B", "right", "p0"), true)));
        configureThroughMenu(helper, f.controller(), f.player(), configuration);
        f.a().step(); f.b().step(); f.controller().tick();
        var beforeA = f.a().flowSnapshot(); var beforeB = f.b().flowSnapshot();
        ModConfigSpec.ConfigValue<List<String>> pins = setting("simpleIoPins");
        var oldPins = pins.get();
        try {
            pins.set(List.of("p1")); f.controller().tick();
            helper.assertValueEqual(f.controller().wireDiagnostics().get("voltage"), "signal_hardware_mismatch",
                "Signal line must not downgrade a missing hardware pin to missing member");
        } finally { pins.set(oldPins); }
        ModConfigSpec.ConfigValue<List<Long>> capacity = setting("memoryUnits");
        var oldCapacity = capacity.get();
        try {
            var reduced = new ArrayList<>(oldCapacity); reduced.set(0, 0L); capacity.set(List.copyOf(reduced)); f.controller().tick();
            helper.assertValueEqual(f.controller().wireDiagnostics().get("voltage"), "signal_resource_limit",
                "Signal line must distinguish an over-capacity retained owner");
        } finally { capacity.set(oldCapacity); }
        f.controller().tick();
        helper.assertValueEqual(f.a().flowSnapshot(), beforeA, "Fault/recovery preserves original driver state");
        helper.assertValueEqual(f.b().flowSnapshot(), beforeB, "Fault observation never executes the paused peer");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void mixedResidentMembersKeepIndependentBudgetAndReloadedCheckpoints(GameTestHelper helper) {
        verifyResidentLoops(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void mixedResidentAssemblyLoopsKeepIndependentBudgetAndReloadedCheckpoints(GameTestHelper helper) {
        verifyResidentLoops(helper, true);
    }

    private static void verifyResidentLoops(GameTestHelper helper, boolean assembly) {
        var f = mixed(helper);
        var loop = new FlowProgram(Map.of("private_value", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end")));
        if (assembly) loop = loop.withAssembly();
        installThroughMenu(helper, f, "A", document(loop, 11));
        installThroughMenu(helper, f, "B", document(loop, 29));
        for (var host : List.of(f.a(), f.b())) helper.assertValueEqual(host.flowSnapshot().orElseThrow().state().assembly().isPresent(),
            assembly, "Menu installation preserves the chosen backend without a fallback");
        var aBudget = f.a().hardware().limits().hardware().workPerSlice();
        var bBudget = f.b().hardware().limits().hardware().workPerSlice();
        helper.assertTrue(aBudget < bBudget, "Actual different residents select different throughput");
        f.a().setPaused(false); f.b().setPaused(false);
        verifyTicks(helper, f, aBudget, bBudget, 3, () -> {
            f.a().setPaused(true); var frozenA = f.a().flowSnapshot();
            verifyTicks(helper, f, 0, bBudget, 3, () -> {
                helper.assertValueEqual(f.a().flowSnapshot(), frozenA, "Only selected member pauses");
                f.b().setPaused(true); var frozenB = f.b().flowSnapshot();
                var restoredA = reloadComputer(helper, f.config().members().get("A").position());
                var restoredB = reloadComputer(helper, f.config().members().get("B").position());
                helper.assertTrue(restoredA.communicationIdentity().isPresent() && restoredB.communicationIdentity().isPresent(), "Both saved owners revalidate");
                helper.assertValueEqual(restoredA.flowSnapshot(), frozenA, "Slow member preserves exact private checkpoint");
                helper.assertValueEqual(restoredB.flowSnapshot(), frozenB, "Fast member preserves its different private checkpoint");
                helper.assertValueEqual(restoredA.flowSnapshot().orElseThrow().state().locals().field("private_value"), new DataValue.IntegerValue(11), "No shared RAM");
                helper.assertValueEqual(restoredB.flowSnapshot().orElseThrow().state().locals().field("private_value"), new DataValue.IntegerValue(29), "Peer RAM remains separate");
                var restored = new Fixture(restoredA, restoredB, f.controller(), f.player(), f.min(), f.empty(), f.config());
                restoredA.setPaused(false); restoredB.setPaused(false);
                verifyTicks(helper, restored, aBudget, bBudget, 3, helper::succeed);
            });
        });
    }

    private static void verifyTicks(GameTestHelper helper, Fixture f, long aBudget, long bBudget, int remaining, Runnable after) {
        drainSameTick(f);
        if (remaining == 0) { after.run(); return; }
        var aBefore = f.a().flowSnapshot().orElseThrow(); var bBefore = f.b().flowSnapshot().orElseThrow();
        long beforeTick = helper.getLevel().getGameTime();
        helper.runAfterDelay(1, () -> {
            helper.assertValueEqual(helper.getLevel().getGameTime() - beforeTick, 1L, "Observe each actual world tick, not a multi-tick average");
            drainSameTick(f);
            var aAfter = f.a().flowSnapshot(); var bAfter = f.b().flowSnapshot();
            helper.assertValueEqual(aAfter.orElseThrow().state().work() - aBefore.state().work(), aBudget, "Slow member owns its exact per-tick allowance");
            helper.assertValueEqual(bAfter.orElseThrow().state().work() - bBefore.state().work(), bBudget, "Fast member neither pools nor loses budget");
            drainSameTick(f); drainSameTick(f);
            helper.assertValueEqual(f.a().flowSnapshot(), aAfter, "Reentry cannot refill slow member");
            helper.assertValueEqual(f.b().flowSnapshot(), bAfter, "Reentry cannot refill fast member");
            verifyTicks(helper, f, aBudget, bBudget, remaining - 1, after);
        });
    }

    private static ProgramDocument document(FlowProgram flow, int value) {
        return new ProgramDocument(flow.expressions(), Map.of(), Optional.empty(), Optional.of(new FlowDocumentBody(
            flow.inputs(), flow.steps(), new DataValue.RecordValue(Map.of("private_value", new DataValue.IntegerValue(value))),
            FlowBlockLayout.DEFAULT, Map.of(), flow.execution())));
    }

    private static void drainSameTick(Fixture f) {
        f.a().tick(); f.b().tick();
        var level = (net.minecraft.server.level.ServerLevel) f.controller().getLevel();
        var cycle = new com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionCycle(level, List.of(f.a(), f.b()), level.getGameTime());
        for (int round = 0; cycle.advanceRound(com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionTicks.seed(level.getGameTime(), round)).progressed(); round++) {}
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void memberCapacityFailureIsNotReportedAsAnAbsentProgram(GameTestHelper helper) {
        var f = mixed(helper);
        helper.assertValueEqual(f.a().hardware().profile(), "control", "Librarian selects the approved multi-context hardware");
        helper.assertValueEqual(f.b().hardware().profile(), "speed", "Fletcher selects speed hardware");
        helper.assertValueEqual(f.a().hardware().resident().orElseThrow().level(), 1, "Librarian keeps actual level");
        helper.assertValueEqual(f.b().hardware().resident().orElseThrow().level(), 2, "Fletcher keeps actual level");
        var beforeA = f.a().flowSnapshot(); var beforeB = f.b().flowSnapshot();
        ModConfigSpec.ConfigValue<List<Long>> setting = setting("programUnits");
        var previous = setting.get();
        try {
            var reduced = new ArrayList<>(previous); reduced.set(0, 0L); setting.set(List.copyOf(reduced));
            helper.assertTrue(f.a().communicationIdentity().isEmpty(), "Changed hardware really rejects installed program");
            helper.assertValueEqual(f.a().diagnostic().orElseThrow().code(), ComputerBlockHost.Code.RESOURCE_LIMIT, "Host reports actual capacity failure");
            var view = refresh(helper, f);
            var failed = view.candidates().stream().filter(c -> c.position().equals(f.config().members().get("A").position())).findFirst().orElseThrow();
            helper.assertValueEqual(failed.diagnostic(), "resource_limit", "Player must not be told the retained program is missing");
            helper.assertTrue(failed.editable() && !failed.selectable(), "Diagnostic editor stays accessible but failed member cannot be newly bound");
            helper.assertValueEqual(f.controller().configure(f.config(), f.player()), "resource_limit", "Routing and menu must report the same cause");
            helper.assertTrue(f.b().communicationIdentity().isPresent(), "Only the changed profile is affected");
            helper.assertValueEqual(f.b().flowSnapshot(), beforeB, "Another member's program and state are untouched");
        } finally { setting.set(previous); }
        helper.assertTrue(f.a().communicationIdentity().isPresent(), "Restored capacity revalidates the original installation");
        helper.assertValueEqual(f.a().flowSnapshot(), beforeA, "Restoration does not reinstall, truncate or reset the program");
        helper.succeed();
    }

    static Fixture mixed(GameTestHelper helper) {
        return fixture(helper, false, true, VillagerProfession.LIBRARIAN, 1, VillagerProfession.FLETCHER, 2);
    }

    @SuppressWarnings("unchecked")
    private static <T> ModConfigSpec.ConfigValue<T> setting(String field) {
        return (ModConfigSpec.ConfigValue<T>)CBConfigs.SERVER_SPEC.getValues().get("biocomputing.hardware.profiles.control." + field);
    }

    private static ClusterControlView refresh(GameTestHelper helper, Fixture f) {
        var player = f.player(); var replies = new ArrayList<ClusterControlResponse>();
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(),
            new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
            net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof ClusterControlResponse reply) {
                    var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
                    try { reply.write(buffer); replies.add(new ClusterControlResponse(buffer)); }
                    finally { buffer.release(); }
                }
            }
        };
        var provider = ClusterControllerMenu.provider(f.controller());
        player.containerMenu = provider.createMenu(12, player.getInventory(), player);
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        UUID nonce;
        try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); }
        finally { buffer.release(); }
        new ClusterControlRequest(12, nonce, 1, f.controller().configurationRevision(), ClusterControlSession.Action.REFRESH, new byte[0]).handle(player);
        helper.assertValueEqual(replies.size(), 1, "Authorized real menu request returns one response");
        var reply = replies.getFirst();
        return ClusterControlWire.decodeView(reply.state(), reply.archiveLimits());
    }
}
