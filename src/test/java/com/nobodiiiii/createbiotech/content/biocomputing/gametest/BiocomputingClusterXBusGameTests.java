package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Actual shell, menu packets, Computer hosts and server ticks; packaged examples use the player install path. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingClusterXBusGameTests {
    @GameTest(template = "empty", timeoutTicks = 130)
    public static void holderResetReleasesTheBusWhilePausedCompetitorReloadsAndSettlesWithoutSending(GameTestHelper helper) {
        var f = fixture(helper, false, false, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
        var empty = new DataValue.RecordValue(Map.of()); var packet = BiocomputingFlowFixture.PACKET;
        var writer = new FlowProgram(Map.of("packet", packet), List.of(new FlowProgram.Write("write", "x0", "packet")));
        var sleeping = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("sleep", "ticks")));
        var sleepDoc = new ProgramDocument(sleeping.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.fromProgram(sleeping,
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(100))))));
        installThroughMenu(helper, f, "A", new ProgramDocument(writer.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(writer, BiocomputingFlowFixture.inputs(12)))));
        installThroughMenu(helper, f, "B", sleepDoc);
        var cPos = f.config().members().get("B").position().north();
        var c = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, cPos));
        helper.getLevel().setBlock(cPos.below(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        installThroughMenu(helper, cPos, f.player(), sleepDoc); c.configureEndpoint(new EndpointConfiguration("third", true));
        var members = new LinkedHashMap<>(f.config().members()); members.put("C", new ClusterConfiguration.Member(c.communicationIdentity().orElseThrow(), cPos));
        var data = f.config().wires().get("data");
        var config = new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("data", data,
            "branch", new XBusWire(data.right(), new XBusWire.Pin("C", "third", "x0"), true)));
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west())); configureThroughMenu(helper, f.controller(), f.player(), config);
        var hardware = List.of(f.a().hardware(), f.b().hardware(), c.hardware());
        List.of(f.a(), f.b(), c).forEach(host -> host.setPaused(false));
        helper.runAfterDelay(5, () -> {
            resetThroughControl(helper, c, empty);
            installThroughMenu(helper, cPos, f.player(), new ProgramDocument(writer.expressions(), Map.of(), Optional.empty(),
                Optional.of(FlowDocumentBody.fromProgram(writer, BiocomputingFlowFixture.inputs(99)))));
            c.setPaused(false);
            helper.runAfterDelay(5, () -> {
                helper.assertValueEqual(c.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Competitor joined the original operation");
                c.setPaused(true); var paused = c.flowSnapshot().orElseThrow().state();
                resetThroughControl(helper, f.a(), empty);
                installThroughMenu(helper, f, "A", new ProgramDocument(writer.expressions(), Map.of(), Optional.empty(),
                    Optional.of(FlowDocumentBody.fromProgram(writer, BiocomputingFlowFixture.inputs(77)))));
                resetThroughControl(helper, f.b(), empty);
                var reader = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", packet)));
                installThroughMenu(helper, f, "B", new ProgramDocument(reader.expressions(), Map.of(), Optional.empty(),
                    Optional.of(FlowDocumentBody.fromProgram(reader, empty))));
                f.a().setPaused(false); f.b().setPaused(false);
                helper.runAfterDelay(6, () -> {
                    helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(77), "Fresh traffic is not blocked by a paused cancelled writer");
                    helper.assertValueEqual(c.flowSnapshot().orElseThrow().state(), paused, "Paused task neither runs nor receives a fabricated result");
                    helper.assertTrue(!c.flowSnapshot().orElseThrow().cancellations().isEmpty(), "Paused owner persists private cancellation proof");
                    var before = c.flowSnapshot(); var restored = reloadComputer(helper, cPos); restored.document();
                    helper.assertValueEqual(restored.flowSnapshot(), before, "Actual book-free Computer save/reload retains cancellation and pause");
                    restored.setPaused(false);
                    helper.runAfterDelay(5, () -> {
                        var failed = restored.flowSnapshot().orElseThrow();
                        helper.assertValueEqual(failed.state().status(), FlowRuntime.Status.FAILED, "Only the retired operation fails when resumed");
                        helper.assertValueEqual(failed.state().diagnostic().orElseThrow().code(), ComputationException.Code.XBUS_OPERATION_RETIRED, "No successful delivery result is invented");
                        helper.assertValueEqual(failed.state().locals(), paused.locals(), "Discarded input stays local and is never resent");
                        helper.assertValueEqual(failed.state().work(), paused.work(), "Cancellation grants no instruction cycles");
                        helper.assertTrue(failed.receipts().isEmpty(), "Cancellation is not a consumption receipt");
                        helper.assertValueEqual(List.of(f.a().hardware(), f.b().hardware(), restored.hardware()), hardware, "Residents and hardware remain independent");
                        helper.assertTrue(f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "Controller does not buffer cancelled data");
                        helper.succeed();
                    });
                });
            });
        });
    }
    private static void resetThroughControl(GameTestHelper helper, ComputerBehaviour computer, DataValue.RecordValue empty) {
        var nonce = UUID.randomUUID(); var controls = new ComputerControlSession(nonce);
        helper.assertValueEqual(controls.apply(nonce, 1, computer.programRevision(), ComputerControlSession.Action.RESET,
            () -> empty, computer).orElseThrow().code(), "ok", "Player explicitly resets only the selected member");
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void explicitlyResettingTheSoleWaitingSenderAllowsANewProgramAfterActualReload(GameTestHelper helper) {
        var f = fixture(helper, false, false, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
        var empty = new DataValue.RecordValue(Map.of());
        var writer = new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("write", "x0", "packet")));
        var sleep = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("sleep", "ticks")));
        installThroughMenu(helper, f, "A", new ProgramDocument(writer.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(writer, BiocomputingFlowFixture.inputs(12)))));
        installThroughMenu(helper, f, "B", new ProgramDocument(sleep.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(sleep, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(100)))))));
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), f.config());
        var hardwareA = f.a().hardware(); var hardwareB = f.b().hardware();
        f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(5, () -> {
            var original = f.a().flowSnapshot().orElseThrow();
            helper.assertValueEqual(original.state().status(), FlowRuntime.Status.WAITING_WRITE, "Original sender occupies the bus without a receiver");
            var reader = f.b().flowSnapshot();
            var nonce = UUID.randomUUID(); var controls = new ComputerControlSession(nonce);
            helper.assertValueEqual(controls.apply(nonce, 1, f.a().programRevision(), ComputerControlSession.Action.RESET,
                () -> empty, f.a()).orElseThrow().code(), "ok", "Player explicitly retires the old sender");
            installThroughMenu(helper, f, "A", new ProgramDocument(writer.expressions(), Map.of(), Optional.empty(),
                Optional.of(FlowDocumentBody.fromProgram(writer, BiocomputingFlowFixture.inputs(77)))));
            helper.assertValueEqual(f.b().flowSnapshot(), reader, "Resetting one member never resets another member");
            var before = f.a().flowSnapshot();
            var restored = reloadComputer(helper, f.config().members().get("A").position()); restored.document();
            helper.assertValueEqual(restored.flowSnapshot(), before, "Actual replacement preserves the new paused program and retired generation");
            var bNonce = UUID.randomUUID(); var bControls = new ComputerControlSession(bNonce);
            helper.assertValueEqual(bControls.apply(bNonce, 1, f.b().programRevision(), ComputerControlSession.Action.RESET,
                () -> empty, f.b()).orElseThrow().code(), "ok", "Player replaces the sleeping receiver explicitly");
            var reading = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET)));
            installThroughMenu(helper, f, "B", new ProgramDocument(reading.expressions(), Map.of(), Optional.empty(),
                Optional.of(FlowDocumentBody.fromProgram(reading, empty))));
            restored.setPaused(false); f.b().setPaused(false);
            helper.runAfterDelay(6, () -> {
                helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(77),
                    "Retired occupancy neither locks the new program nor replays the original value");
                helper.assertValueEqual(restored.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE, "New sender completes");
                helper.assertValueEqual(restored.hardware(), hardwareA, "Librarian hardware remains local after reset and reload");
                helper.assertValueEqual(f.b().hardware(), hardwareB, "Ordinary resident does not inherit the sender's hardware");
                helper.assertTrue(f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "The controller never buffers the retired input");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void firstSenderSurvivesLaterInputAndActualMemberReloadWithIndependentResidentHardware(GameTestHelper helper) {
        var f = fixture(helper, false, false, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
        var packet = BiocomputingFlowFixture.PACKET;
        var sleeping = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("sleep", "ticks")));
        var sleepArgs = new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(100)));
        var sleepDoc = new ProgramDocument(sleeping.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.fromProgram(sleeping, sleepArgs)));
        var writing = new FlowProgram(Map.of("packet", packet), List.of(new FlowProgram.Write("send", "x0", "packet")));
        var sender = new ProgramDocument(writing.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.fromProgram(writing, BiocomputingFlowFixture.inputs(12))));
        installThroughMenu(helper, f, "A", sender); installThroughMenu(helper, f, "B", sleepDoc);
        var cPos = f.config().members().get("B").position().north();
        var c = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, cPos));
        helper.getLevel().setBlock(cPos.below(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        installThroughMenu(helper, cPos, f.player(), sleepDoc); c.configureEndpoint(new EndpointConfiguration("third", true));
        var members = new LinkedHashMap<>(f.config().members()); members.put("C", new ClusterConfiguration.Member(c.communicationIdentity().orElseThrow(), cPos));
        var data = f.config().wires().get("data");
        var config = new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("data", data,
            "branch", new XBusWire(data.right(), new XBusWire.Pin("C", "third", "x0"), true)));
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west())); configureThroughMenu(helper, f.controller(), f.player(), config);
        var hosts = List.of(f.a(), f.b(), c); var hardware = hosts.stream().map(ComputerBehaviour::hardware).toList();
        helper.assertTrue(!hardware.get(0).equals(hardware.get(1)), "The fixture contains different resident hardware, not one shared Cluster CPU");
        hosts.forEach(host -> host.setPaused(false));
        helper.runAfterDelay(5, () -> {
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "First sender waits without an implicit receiver");
            var nonce = UUID.randomUUID(); var controls = new ComputerControlSession(nonce); var empty = new DataValue.RecordValue(Map.of());
            helper.assertValueEqual(controls.apply(nonce, 1, c.programRevision(), ComputerControlSession.Action.RESET, () -> empty, c).orElseThrow().code(), "ok", "Reset the sleeping third member explicitly");
            installThroughMenu(helper, cPos, f.player(), new ProgramDocument(writing.expressions(), Map.of(), Optional.empty(),
                Optional.of(FlowDocumentBody.fromProgram(writing, BiocomputingFlowFixture.inputs(99)))));
            c.setPaused(false);
            helper.runAfterDelay(5, () -> {
                helper.assertValueEqual(c.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Later sender waits for the existing operation, not a queued second send");
                hosts.forEach(host -> host.setPaused(true)); var snapshots = hosts.stream().map(ComputerBehaviour::flowSnapshot).toList();
                var restored = List.of(reloadComputer(helper, members.get("A").position()), reloadComputer(helper, members.get("B").position()), reloadComputer(helper, cPos));
                restored.forEach(ComputerBehaviour::document);
                helper.assertValueEqual(restored.stream().map(ComputerBehaviour::flowSnapshot).toList(), snapshots, "Actual BE save/reload retains every paused continuation");
                helper.assertValueEqual(restored.stream().map(ComputerBehaviour::hardware).toList(), hardware, "Resident levels and hardware remain per member");
                var journal = XBusHistorySavedData.get(helper.getLevel()).save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
                helper.assertValueEqual(XBusHistorySavedData.load(journal, helper.getLevel().registryAccess()).save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()), journal, "Dimension occupancy round-trips without payloads");
                var b = restored.get(1); var resetNonce = UUID.randomUUID(); var reset = new ComputerControlSession(resetNonce);
                helper.assertValueEqual(reset.apply(resetNonce, 1, b.programRevision(), ComputerControlSession.Action.RESET, () -> empty, b).orElseThrow().code(), "ok", "Receiver replaces its sleeping program explicitly");
                var reading = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x1", "packet", packet)));
                installThroughMenu(helper, members.get("B").position(), f.player(), new ProgramDocument(reading.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.fromProgram(reading, empty))));
                restored.forEach(host -> host.setPaused(false));
                helper.runAfterDelay(6, () -> {
                    helper.assertValueEqual(b.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "First sender survives later input and new arbitration rounds");
                    helper.assertValueEqual(restored.get(2).flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE, "Discarded writer finishes without a delivery acknowledgement");
                    helper.assertValueEqual(restored.get(2).flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(99), "No hidden winner or delivery result is injected");
                    helper.assertTrue(f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "No buffered transport was introduced");
                    var complete = restored.stream().map(ComputerBehaviour::flowSnapshot).toList();
                    helper.runAfterDelay(4, () -> {
                        helper.assertValueEqual(restored.stream().map(ComputerBehaviour::flowSnapshot).toList(), complete, "Following ticks cannot send the discarded input again");
                        helper.assertValueEqual(restored.stream().map(ComputerBehaviour::hardware).toList(), hardware, "Member hardware remains independent after consumption");
                        helper.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void terminalTaskAndMainShareAReadableProbeWithoutConsumingAndKeepTheirPausedContinuationsOnReload(GameTestHelper helper) {
        var f = fixture(helper, false, false, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
        var packet = BiocomputingFlowFixture.PACKET; var empty = new DataValue.RecordValue(Map.of());
        var key = new ModuleDefinition.Key("probe", 1); var inputs = Map.of("hold", (DataType)DataType.Scalar.INTEGER);
        var args = new DataValue.RecordValue(Map.of("hold", new DataValue.IntegerValue(100)));
        var receiver = new FlowProgram(inputs, List.of(new FlowProgram.AwaitReadable("main_probe", "x1"),
            new FlowProgram.XBusStatus("main_status", "x1", "busy"),
            new FlowProgram.Sleep("main_hold", "hold"), new FlowProgram.Read("main_read", "x1", "packet", packet),
            new FlowProgram.Procedure("probe", key, inputs, Map.of(), Map.of()),
            new FlowProgram.AwaitReadable("child_probe", "x1"), new FlowProgram.Sleep("child_hold", "hold"),
            new FlowProgram.EndProcedure("return")));
        var terminal = new TerminalDefinition("start", Map.of("start", new TerminalDefinition.Button(
            new TerminalBinding.Literal(new DataValue.TextValue("Probe")), new TerminalBinding.Literal(new DataValue.BooleanValue(true)),
            new TerminalTarget.Task(key), new TerminalBinding.Literal(args))));
        installThroughMenu(helper, f, "B", new ProgramDocument(receiver.expressions(), Map.of(), Optional.of(terminal),
            Optional.of(FlowDocumentBody.fromProgram(receiver, args))));
        var writer = new FlowProgram(Map.of("packet", packet), List.of(new FlowProgram.Write("write", "x0", "packet")));
        installThroughMenu(helper, f, "A", new ProgramDocument(writer.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(writer, BiocomputingFlowFixture.inputs(12)))));
        var hardwareA = f.a().hardware(); var hardwareB = f.b().hardware();
        f.a().setPaused(false); f.b().setPaused(false);
        var nonce = UUID.randomUUID(); var menu = new ComputerControlSession(nonce); long revision = f.b().programRevision();
        menu.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> empty, f.b());
        helper.assertValueEqual(menu.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> new TerminalClick(1, "5:start").encode(), f.b()).orElseThrow().code(), "ok", "Player terminal starts the probe task");
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().pinClaims().entries().size(), 2, "Both contexts wait before the wire is enabled");
            var child = f.b().flowSnapshot().orElseThrow().tasks().contexts().getFirst().flow();
            var waitingWriter = f.a().flowSnapshot().orElseThrow();
            f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west())); configureThroughMenu(helper, f.controller(), f.player(), f.config());
            helper.runAfterDelay(3, () -> {
                helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.SLEEPING, "Main passed the readiness probe");
                helper.assertValueEqual(f.b().flowTask(child).orElseThrow().status(), FlowRuntime.Status.SLEEPING, "Child passed the same readiness probe");
                helper.assertValueEqual(f.a().flowSnapshot().orElseThrow(), waitingWriter, "Readiness never consumes or advances the writer");
                helper.assertTrue(f.b().flowSnapshot().orElseThrow().receipts().isEmpty(), "Readiness creates no data receipt");
                helper.assertTrue(((DataValue.ListValue)f.controller().recentEvents().field("entries")).values().stream()
                    .map(DataValue.RecordValue.class::cast).noneMatch(event -> event.field("code").equals(new DataValue.TextValue("xbus_exchanged"))),
                    "Controller status page never calls a readiness probe a data transfer");
                helper.assertTrue(!f.b().flowSnapshot().orElseThrow().state().locals().fields().containsKey("packet"), "No hidden data delivery");
                helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().locals().field("busy"), new DataValue.IntegerValue(15),
                    "The public status instruction still observes the original sender occupying the bus");
                f.a().setPaused(true); f.b().setPaused(true);
                var beforeA = f.a().flowSnapshot().orElseThrow(); var beforeB = f.b().flowSnapshot().orElseThrow();
                var restored = reloadComputer(helper, f.config().members().get("B").position()); restored.document();
                helper.runAfterDelay(4, () -> {
                    helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), beforeB, "Main and child sleep continuations survive actual BE replacement");
                    helper.assertValueEqual(f.a().flowSnapshot().orElseThrow(), beforeA, "Reload never restarts or acknowledges the sender");
                    helper.assertValueEqual(f.a().hardware(), hardwareA, "Sender retains independent librarian hardware");
                    helper.assertValueEqual(restored.hardware(), hardwareB, "Receiver retains its ordinary resident hardware");
                    helper.succeed();
                });
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void configuredClusterBroadcastsToBothMembersAndTheirWaitingTasksThenReloadsWithoutReplay(GameTestHelper helper) {
        var f = fixture(helper, false, false, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
        var empty = new DataValue.RecordValue(Map.of()); var packet = BiocomputingFlowFixture.PACKET;
        var key = new ModuleDefinition.Key("receive", 1);
        var reader = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("main", "x1", "packet", packet),
            new FlowProgram.Procedure("receiver", key, Map.of(), Map.of("packet", packet), Map.of("packet", "packet")),
            new FlowProgram.Read("task", "x1", "packet", packet), new FlowProgram.EndProcedure("return")));
        var terminal = new TerminalDefinition("start", Map.of("start", new TerminalDefinition.Button(
            new TerminalBinding.Literal(new DataValue.TextValue("Receive")), new TerminalBinding.Literal(new DataValue.BooleanValue(true)),
            new TerminalTarget.Task(key), new TerminalBinding.Literal(empty))));
        installThroughMenu(helper, f, "B", new ProgramDocument(reader.expressions(), Map.of(), Optional.of(terminal),
            Optional.of(FlowDocumentBody.fromProgram(reader, empty))));
        var writer = new FlowProgram(Map.of("packet", packet), List.of(new FlowProgram.Write("send", "x0", "packet")));
        installThroughMenu(helper, f, "A", new ProgramDocument(writer.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(writer, BiocomputingFlowFixture.inputs(12)))));
        var cPos = f.config().members().get("B").position().north();
        var c = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, cPos));
        helper.getLevel().setBlock(cPos.below(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        var third = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "packet", packet)));
        installThroughMenu(helper, cPos, f.player(), new ProgramDocument(third.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(third, empty))));
        c.configureEndpoint(new EndpointConfiguration("third", true));
        var hardwareA = f.a().hardware(); var hardwareB = f.b().hardware(); var hardwareC = c.hardware();
        var initialMembers = new LinkedHashMap<>(f.config().members());
        initialMembers.put("C", new ClusterConfiguration.Member(c.communicationIdentity().orElseThrow(), cPos));
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), new ClusterConfiguration(initialMembers, Map.of()));
        f.a().setPaused(false); f.b().setPaused(false); c.setPaused(false);
        var nonce = UUID.randomUUID(); var terminalMenu = new ComputerControlSession(nonce); long revision = f.b().programRevision();
        terminalMenu.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> empty, f.b());
        helper.assertValueEqual(terminalMenu.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> new TerminalClick(1, "5:start").encode(), f.b()).orElseThrow().code(), "ok", "Public terminal starts the receiving task");
        helper.runAfterDelay(5, () -> {
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().pinClaims().entries().size(), 2, "Main and task are both waiting before wiring");
            var child = f.b().flowSnapshot().orElseThrow().tasks().contexts().getFirst().flow();
            var members = new LinkedHashMap<>(f.config().members()); members.put("C", new ClusterConfiguration.Member(c.communicationIdentity().orElseThrow(), cPos));
            var data = f.config().wires().get("data");
            var config = new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("data", data,
                "branch", new XBusWire(data.right(), new XBusWire.Pin("C", "third", "x0"), true)));
            f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west())); configureThroughMenu(helper, f.controller(), f.player(), config);
            helper.runAfterDelay(6, () -> {
                var a = f.a().flowSnapshot().orElseThrow(); var b = f.b().flowSnapshot().orElseThrow(); var thirdSave = c.flowSnapshot().orElseThrow();
                helper.assertValueEqual(b.state().locals(), BiocomputingFlowFixture.inputs(12), "First physical reader receives the selected value");
                helper.assertValueEqual(f.b().flowTask(child).orElseThrow().locals(), BiocomputingFlowFixture.inputs(12), "Same-pin task receives its own value copy");
                helper.assertValueEqual(thirdSave.state().locals(), BiocomputingFlowFixture.inputs(12), "Branched physical reader receives the same value");
                helper.assertValueEqual(a.receipts().getFirst().positions().size(), 4, "One receipt freezes all actual participants");
                helper.assertValueEqual(b.receipts(), a.receipts(), "Owners retain identical complete evidence");
                helper.assertValueEqual(thirdSave.receipts(), a.receipts(), "Third owner is not reduced to a pair");
                var events = ((DataValue.ListValue)f.controller().recentEvents().field("entries")).values();
                for (var wire : List.of("data", "branch")) helper.assertValueEqual(events.stream().map(DataValue.RecordValue.class::cast)
                    .filter(event -> event.field("code").equals(new DataValue.TextValue("xbus_exchanged"))
                        && event.field("subject").equals(new DataValue.TextValue(wire))).count(), 1L,
                    "Controller status logs every explicit branch exactly once without a payload");
                var journal = XBusHistorySavedData.get(helper.getLevel()).save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
                helper.assertTrue(journal.getInt("Version") >= 3 && journal.getInt("Version") <= 6,
                    "Task-aware cohort identities coexist with other tests' unconsumed occupancy witnesses");
                var ownClaims = journal.getList("Claims", 10).stream().map(net.minecraft.nbt.CompoundTag.class::cast)
                    .filter(claim -> claim.getCompound("Writer").getUUID("Computer").equals(a.state().computer())).toList();
                helper.assertValueEqual(ownClaims.size(), 1, "This Computer owns one complete consumption fence");
                helper.assertValueEqual(ownClaims.getFirst().getList("OtherReaders", 10).size(), 2, "Both extra physical/task readers are retained");
                helper.assertTrue(ownClaims.getFirst().getList("OtherReaders", 10).stream().map(net.minecraft.nbt.CompoundTag.class::cast)
                    .anyMatch(end -> end.getInt("Slot") != 0) || ownClaims.getFirst().getCompound("Reader").getInt("Slot") != 0,
                    "The actual child task identity is preserved, not merely a new root schema number");
                helper.assertTrue(journal.getList("Occupancies", 10).stream().map(net.minecraft.nbt.CompoundTag.class::cast)
                    .noneMatch(hold -> hold.getCompound("Writer").getUUID("Computer").equals(a.state().computer())), "Consumption retires this sender's occupancy");
                var restoredB = reloadComputer(helper, f.config().members().get("B").position()); restoredB.document();
                var restoredC = reloadComputer(helper, cPos); restoredC.document();
                helper.runAfterDelay(5, () -> {
                    helper.assertValueEqual(restoredB.flowSnapshot().orElseThrow(), b, "Real block-entity reload does not receive or run the task again");
                    helper.assertValueEqual(restoredC.flowSnapshot().orElseThrow(), thirdSave, "Third receiver survives reload without replay");
                    helper.assertValueEqual(f.a().flowSnapshot().orElseThrow(), a, "Reloaded receivers do not restart the writer");
                    helper.assertValueEqual(f.a().hardware(), hardwareA, "Librarian keeps its own hardware");
                    helper.assertValueEqual(restoredB.hardware(), hardwareB, "Ordinary receiver keeps its own hardware");
                    helper.assertValueEqual(restoredC.hardware(), hardwareC, "Third member never pools resources");
                    helper.assertTrue(f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "No controller buffering is used");
                    helper.succeed();
                });
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void menuInstalledStatusReadsDoNotConsumeAndReloadedWriterStillTransfers(GameTestHelper helper) {
        var f = fixture(helper, false, false); var empty = new DataValue.RecordValue(Map.of());
        var probe = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER, "hold", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Sleep("delay", "ticks"), new FlowProgram.XBusStatus("probe", "x0", "code"), new FlowProgram.Sleep("hold", "hold")));
        var args = new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(2), "hold", new DataValue.IntegerValue(100)));
        var sender = new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x1", "packet")));
        installThroughMenu(helper, f, "A", new ProgramDocument(probe.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.fromProgram(probe, args))));
        installThroughMenu(helper, f, "B", new ProgramDocument(sender.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(sender, BiocomputingFlowFixture.inputs(12)))));
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), f.config()); f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(7, () -> {
            var state = f.a().flowSnapshot().orElseThrow();
            helper.assertValueEqual(state.state().locals().field("code"), new DataValue.IntegerValue(15), "Public status instruction sees physical busy bus");
            helper.assertTrue(state.receipts().isEmpty() && state.state().signals().outputs().isEmpty(), "Query neither consumes nor drives Simple I/O");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Writer still owns its data");
            f.a().setPaused(true); f.b().setPaused(true);
            var savedA = f.a().flowSnapshot(); var savedB = f.b().flowSnapshot();
            var restoredA = reloadComputer(helper, f.config().members().get("A").position());
            var restoredB = reloadComputer(helper, f.config().members().get("B").position());
            restoredA.document(); restoredB.document();
            helper.assertValueEqual(restoredA.flowSnapshot(), savedA, "Status value and sleep survive actual BE reload");
            helper.assertValueEqual(restoredB.flowSnapshot(), savedB, "Reload preserves original unconsumed writer");
            restoredA.setPaused(false);
            helper.assertValueEqual(restoredA.sampleFlowSignals().get("x0"), 0, "Paused writer has no active XBus status");
            restoredB.setPaused(false);
            helper.assertValueEqual(restoredA.sampleFlowSignals().get("x0"), 15, "Resuming restores busy status, not a new message");
            var nonce = UUID.randomUUID(); var controls = new ComputerControlSession(nonce);
            helper.assertValueEqual(controls.apply(nonce, 1, restoredA.programRevision(), ComputerControlSession.Action.RESET,
                () -> empty, restoredA).orElseThrow().code(), "ok", "Explicit reset leaves sleeping probe at a safe replacement boundary");
            var reader = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "packet", BiocomputingFlowFixture.PACKET)));
            installThroughMenu(helper, f, "A", new ProgramDocument(reader.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.fromProgram(reader, empty))));
            restoredA.setPaused(false);
            helper.runAfterDelay(6, () -> {
                helper.assertValueEqual(restoredA.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Only explicit data read consumes the retained payload");
                helper.assertValueEqual(restoredA.flowSnapshot().orElseThrow().receipts().size(), 1, "Exactly one actual handoff");
                helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void terminalTaskCompletesWhileMainWaitsThenSurvivesConfiguredClusterHandoff(GameTestHelper helper) {
        var f = fixture(helper, false, false, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, 2,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
        var key = new ModuleDefinition.Key("copy", 1); var packet = BiocomputingFlowFixture.PACKET;
        var empty = new DataValue.RecordValue(Map.of());
        var receiver = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x1", "packet", packet),
            new FlowProgram.Procedure("copy", key, packet.fields(), packet.fields(), Map.of("value", "value")),
            new FlowProgram.EndProcedure("return")));
        var terminal = new TerminalDefinition("start", Map.of("start", new TerminalDefinition.Button(
            new TerminalBinding.Literal(new DataValue.TextValue("Copy")), new TerminalBinding.Literal(new DataValue.BooleanValue(true)),
            new TerminalTarget.Task(key), new TerminalBinding.Literal(new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(42)))))));
        installThroughMenu(helper, f, "B", new ProgramDocument(receiver.expressions(), Map.of(), Optional.of(terminal),
            Optional.of(FlowDocumentBody.fromProgram(receiver, empty))));
        var sender = new FlowProgram(Map.of("packet", packet), List.of(new FlowProgram.Write("send", "x0", "packet")));
        installThroughMenu(helper, f, "A", new ProgramDocument(sender.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(sender, BiocomputingFlowFixture.inputs(12)))));
        var hardwareA = f.a().hardware(); var hardwareB = f.b().hardware();
        f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(5, () -> {
            var waitingA = f.a().flowSnapshot().orElseThrow(); var waitingB = f.b().flowSnapshot().orElseThrow().state();
            helper.assertValueEqual(waitingB.status(), FlowRuntime.Status.WAITING_READ, "Unwired main really waits at its physical pin");
            var nonce = UUID.randomUUID(); var menu = new ComputerControlSession(nonce); long revision = f.b().programRevision();
            menu.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> empty, f.b());
            helper.assertValueEqual(menu.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
                () -> new TerminalClick(1, "5:start").encode(), f.b()).orElseThrow().code(), "ok", "Public terminal starts a task while XBus waits");
            helper.runAfterDelay(4, () -> {
                var child = f.b().flowSnapshot().orElseThrow().tasks().contexts().getFirst();
                helper.assertValueEqual(child.status(), FlowRuntime.Status.COMPLETE, "Actual world scheduler runs terminal task");
                helper.assertValueEqual(child.locals().field("value"), new DataValue.IntegerValue(42), "Task owns its argument and result");
                helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state(), waitingB, "Task never consumes or replaces the main pin wait");
                helper.assertValueEqual(f.a().flowSnapshot().orElseThrow(), waitingA, "Another resident's execution and budget are unchanged");
                f.b().setPaused(true); var paused = f.b().flowSnapshot().orElseThrow();
                var restored = reloadComputer(helper, f.config().members().get("B").position());
                restored.document(); helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), paused, "Paused wait and result survive real BE replacement");
                restored.setPaused(false); var retained = restored.flowTask(child.flow()).orElseThrow();
                f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west()));
                configureThroughMenu(helper, f.controller(), f.player(), f.config());
                helper.runAfterDelay(8, () -> {
                    helper.assertValueEqual(restored.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Explicit physical wire performs the original rendezvous");
                    helper.assertValueEqual(restored.flowTask(child.flow()).orElseThrow(), retained, "Handoff preserves the retained terminal result");
                    helper.assertValueEqual(restored.flowSnapshot().orElseThrow().receipts().size(), 1, "Only one handoff is recorded");
                    helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().control().peers().get(waitingB.computer()).slots().get(1),
                        new FlowControl.Frontier(child.flow(), 0, -1, false), "Real handoff durably observes the ordinary member's task identity");
                    helper.assertValueEqual(f.a().hardware(), hardwareA, "Librarian hardware remains independent");
                    helper.assertValueEqual(restored.hardware(), hardwareB, "Ordinary member never borrows librarian capacity");
                    helper.assertTrue(f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "XBus is not a buffered task inbox");
                    var fresh = UUID.randomUUID(); var claimMenu = new ComputerControlSession(fresh);
                    long claimRevision = restored.programRevision();
                    claimMenu.apply(fresh, 1, claimRevision, ComputerControlSession.Action.READ_TERMINAL, () -> empty, restored);
                    helper.assertValueEqual(claimMenu.apply(fresh, 2, claimRevision, ComputerControlSession.Action.TERMINAL_CLAIM,
                        () -> new TerminalTaskClaim(1, 0).encode(), restored).orElseThrow().code(), "ok", "Public menu releases the last result");
                    restored.setPaused(true); var retired = restored.flowSnapshot().orElseThrow();
                    helper.assertTrue(retired.tasks().contexts().isEmpty(), "Result no longer occupies a live task");
                    helper.assertValueEqual(retired.tasks().slots().entries(), List.of(new FlowTaskSlots.Slot(child.flow(), 0, true)),
                        "Result release retains only the bounded retirement identity");
                    var journal = XBusHistorySavedData.get(helper.getLevel());
                    var retiredHistory = journal.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
                    var ownerWitness = retiredHistory.getList("Frontiers", 10).stream().map(tag -> (net.minecraft.nbt.CompoundTag)tag)
                        .filter(tag -> tag.getUUID("Computer").equals(waitingB.computer())).findFirst().orElseThrow();
                    var taskWitness = ownerWitness.getList("Slots", 10).stream().map(tag -> (net.minecraft.nbt.CompoundTag)tag)
                        .filter(tag -> tag.getInt("Slot") == 1).findFirst().orElseThrow();
                    helper.assertTrue(taskWitness.getBoolean("Retired"), "Actual lifecycle callback retires the slot in dimension-owned history");
                    helper.assertValueEqual(taskWitness.getUUID("Flow"), child.flow(), "Dimension witness never aliases the main or librarian");
                    var again = reloadComputer(helper, f.config().members().get("B").position()); again.document();
                    helper.assertValueEqual(again.flowSnapshot().orElseThrow(), retired, "Real BE reload retains retired slot and original handoff evidence");
                    again.setPaused(false); var startNonce = UUID.randomUUID(); var startMenu = new ComputerControlSession(startNonce);
                    long startRevision = again.programRevision();
                    startMenu.apply(startNonce, 1, startRevision, ComputerControlSession.Action.READ_TERMINAL, () -> empty, again);
                    helper.assertValueEqual(startMenu.apply(startNonce, 2, startRevision, ComputerControlSession.Action.TERMINAL_CLICK,
                        () -> new TerminalClick(1, "5:start").encode(), again).orElseThrow().code(), "ok", "Public terminal reuses the freed hardware capacity");
                    helper.runAfterDelay(4, () -> {
                        var current = again.flowSnapshot().orElseThrow(); var later = current.tasks().contexts().getFirst();
                        helper.assertValueEqual(later.status(), FlowRuntime.Status.COMPLETE, "Reused slot executes normally");
                        helper.assertValueEqual(current.tasks().slots().entries(), List.of(new FlowTaskSlots.Slot(later.flow(), 1, false)),
                            "Reused slot advances its identity instead of growing one tombstone per task");
                        helper.assertTrue(!later.flow().equals(child.flow()), "New task cannot alias the released result");
                        helper.assertValueEqual(current.receipts(), retired.receipts(), "Task churn neither deletes nor duplicates the main XBus handoff");
                        helper.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void capturedEndpointAccessCannotUseAnUnloadedOwnerButTheConfiguredWireCanResume(GameTestHelper helper) {
        var f = fixture(helper, false); var a = f.a(); var b = f.b();
        a.step(); b.step(); a.setPaused(false); b.setPaused(false);
        var left = a.xbusAccess("left", a.xbusPort("left", "x0").orElseThrow()).orElseThrow();
        var right = b.xbusAccess("right", b.xbusPort("right", "x1").orElseThrow()).orElseThrow();
        var beforeA = a.flowSnapshot().orElseThrow(); var beforeB = b.flowSnapshot().orElseThrow();
        var restored = reloadComputer(helper, f.config().members().get("B").position());
        // Real owners lazily restore their durable flow on the first authorized observation, not in read().
        helper.assertTrue(restored.xbusPort("right", "x1").isPresent(), "Restored owner exposes its original granted pin");
        helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), beforeB, "Reload preserves the exact waiting reader");
        helper.assertValueEqual(left.exchange(right), XBusExchange.Status.UNAVAILABLE,
            "A captured owner is unavailable even when its identity and position have been restored");
        helper.assertValueEqual(a.flowSnapshot().orElseThrow(), beforeA, "Stale access does not consume the writer");
        helper.assertValueEqual(b.flowSnapshot().orElseThrow(), beforeB, "Stale access does not mutate the detached reader");
        helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), beforeB, "Stale access cannot redirect to a new owner");
        configureThroughMenu(helper, f.controller(), f.player(), f.config());
        helper.runAfterDelay(8, () -> {
            helper.assertValueEqual(restored.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12),
                "The real configured controller obtains fresh endpoint access and resumes the original identity");
            helper.assertValueEqual(a.flowSnapshot().orElseThrow().receipts().size(), 1, "Only one real read consumes the write");
            helper.assertTrue(f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "No controller inbox participates");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void hardwarePinsRejectInventedPlayerDocumentWithoutReplacingThenAllowRealWire(GameTestHelper helper) {
        var f = fixture(helper, false, false); var before = f.a().document().orElseThrow(); var revision = f.a().programRevision();
        helper.assertValueEqual(f.a().hardware().limits().pins().xbus(), List.of("x0", "x1"), "Real resident supplies fixed physical pins");
        var bad = new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wait", "x9")));
        var document = new ProgramDocument(bad.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(bad.inputs(), bad.steps(), new DataValue.RecordValue(Map.of()))));
        requestInstallThroughMenu(helper, f.config().members().get("A").position(), f.player(), document);
        helper.assertValueEqual(f.a().document().orElseThrow(), before, "Server rejects unknown pin, keeps installed document");
        helper.assertValueEqual(f.a().programRevision(), revision, "Rejected install does not publish a revision");
        helper.assertTrue(f.a().xbusPort("left", "x9").isEmpty(), "Program text cannot create a physical port");
        var writer = new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x0", "packet")));
        var reader = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET)));
        installThroughMenu(helper, f, "A", new ProgramDocument(writer.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(writer.inputs(), writer.steps(), BiocomputingFlowFixture.inputs(12)))));
        installThroughMenu(helper, f, "B", new ProgramDocument(reader.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(reader.inputs(), reader.steps(), new DataValue.RecordValue(Map.of())))));
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west())); configureThroughMenu(helper, f.controller(), f.player(), f.config());
        f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(8, () -> {
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Real hardware pins still exchange normally");
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE, "Writer completed a real read, not an inbox ACK");
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void conditionalPlayerDocumentSkipsUnconnectedWaitAndReloadKeepsConsumedBranch(GameTestHelper helper) {
        var f = fixture(helper, false, false); var calculate = BiocomputingFlowFixture.calculator();
        var inputs = new LinkedHashMap<>(calculate.inputs()); inputs.put("ok", DataType.Scalar.BOOLEAN);
        var steps = new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.If("choose", "ok"));
        steps.add(new FlowProgram.AwaitReadable("never_read", "x1")); steps.add(new FlowProgram.Otherwise("otherwise"));
        steps.addAll(calculate.steps()); steps.add(new FlowProgram.EndIf("end"));
        var initial = new LinkedHashMap<>(BiocomputingFlowFixture.inputs(12).fields()); initial.put("ok", new DataValue.BooleanValue(false));
        var document = new ProgramDocument(calculate.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(inputs, steps, new DataValue.RecordValue(initial))));
        var reader = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET)));
        installThroughMenu(helper, f, "A", document);
        installThroughMenu(helper, f, "B", new ProgramDocument(reader.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(reader.inputs(), reader.steps(), new DataValue.RecordValue(Map.of())))));
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), f.config());
        f.a().setPaused(false); f.b().setPaused(false);
        helper.runAfterDelay(20, () -> {
            var a = f.a().flowSnapshot().orElseThrow();
            helper.assertValueEqual(a.state().status(), FlowRuntime.Status.COMPLETE, "Unconnected unselected read never waits");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(19), "Selected branch calculated and transferred");
            helper.assertValueEqual(a.receipts().size(), 1, "Only the actual branch write is acknowledged");
            var restored = reloadComputer(helper, f.config().members().get("A").position());
            helper.assertValueEqual(restored.document().orElseThrow(), document, "Branch groups and initial condition survive owner reload");
            helper.runAfterDelay(8, () -> {
                helper.assertValueEqual(restored.flowSnapshot().orElseThrow(), a, "Reload cannot replay or enter the skipped branch");
                helper.succeed();
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void packagedCalculatorReturnsOnSameWireAndReloadDoesNotReplay(GameTestHelper helper) {
        var f = fixture(helper, false, false);
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open = id ->
            BiocomputingClusterXBusGameTests.class.getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath());
        var examples = ProgramExampleCatalog.load(open);
        var documents = new LinkedHashMap<String, ProgramDocument>();
        for (String role : List.of("caller", "worker")) {
            var example = examples.stream().filter(entry -> entry.id().equals("xbus_calculator_" + role)).findFirst().orElseThrow();
            documents.put(role, ProgramExampleCatalog.preview(example, open, f.a().hardware().limits().archive()));
        }
        installThroughMenu(helper, f, "A", documents.get("caller"));
        installThroughMenu(helper, f, "B", documents.get("worker"));
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), f.config());
        f.a().setPaused(false);
        helper.runAfterDelay(8, () -> {
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.FAILED,
                "A legacy write to a paused peer ends explicitly instead of blocking forever");
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().diagnostic().orElseThrow().code(),
                ComputationException.Code.XBUS_PEER_PAUSED, "The pause result is not a transport or task failure");
            helper.assertTrue(f.a().flowSnapshot().orElseThrow().receipts().isEmpty(), "No inbox acknowledgement");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().work(), 0L, "The wire never runs paused B");
            f.a().reset(); f.a().setPaused(false); f.b().setPaused(false);
            helper.runAfterDelay(20, () -> {
                var a = f.a().flowSnapshot().orElseThrow(); var b = f.b().flowSnapshot().orElseThrow();
                helper.assertValueEqual(a.state().status(), FlowRuntime.Status.COMPLETE, "Caller received calculated return");
                helper.assertValueEqual(b.state().status(), FlowRuntime.Status.COMPLETE, "Worker finished return write");
                helper.assertValueEqual(a.state().locals().field("result"), BiocomputingFlowFixture.inputs(19).field("packet"), "Result is 19");
                helper.assertValueEqual(a.state().locals().field("packet"), BiocomputingFlowFixture.inputs(12).field("packet"), "Caller's original packet remains 12");
                helper.assertValueEqual(b.state().locals(), BiocomputingFlowFixture.inputs(19), "Worker owns a separate changed copy");
                helper.assertValueEqual(a.receipts().size(), 2, "One forward and one reverse handoff");
                helper.assertValueEqual(b.receipts().size(), 2, "No hidden query transport");
                helper.assertTrue(f.controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "No buffered controller messages");
                var restoredA = reloadComputer(helper, f.config().members().get("A").position());
                var restoredB = reloadComputer(helper, f.config().members().get("B").position());
                helper.assertValueEqual(restoredA.document().orElseThrow(), documents.get("caller"), "Portable initial values survive separately");
                helper.assertValueEqual(restoredB.document().orElseThrow(), documents.get("worker"), "Editable sum graph and layout survive");
                helper.runAfterDelay(8, () -> {
                    helper.assertValueEqual(restoredA.flowSnapshot().orElseThrow(), a, "Completed caller does not replay after reload");
                    helper.assertValueEqual(restoredB.flowSnapshot().orElseThrow(), b, "Completed worker does not replay after reload");
                    helper.succeed();
                });
            });
        });
    }
    static ComputerBehaviour reloadComputer(GameTestHelper helper, BlockPos pos) {
        var level = helper.getLevel(); var saved = level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        var restored = (com.simibubi.create.foundation.blockEntity.SmartBlockEntity)Objects.requireNonNull(
            BlockEntity.loadStatic(pos, level.getBlockState(pos), saved, level.registryAccess()));
        level.setBlockEntity(restored); return restored.getBehaviour(ComputerBehaviour.TYPE);
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void playerMenuFlowDocumentsInstallPausedComputeAndSurviveRealReload(GameTestHelper helper) {
        var f = fixture(helper, false, false); var level = helper.getLevel();
        var sender = BiocomputingFlowFixture.calculator();
        var receiver = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x1", "packet", BiocomputingFlowFixture.PACKET)));
        var senderDocument = new ProgramDocument(sender.expressions(), Map.of(ProgramDocument.entryGraphId("sum"),
            Map.of("sum", new ProgramDocument.Position(123, 234))), Optional.empty(), Optional.of(
                new FlowDocumentBody(sender.inputs(), sender.steps(), BiocomputingFlowFixture.inputs(12))));
        var receiverDocument = new ProgramDocument(receiver.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(receiver.inputs(), receiver.steps(), new DataValue.RecordValue(Map.of()))));
        installThroughMenu(helper, f, "A", senderDocument); installThroughMenu(helper, f, "B", receiverDocument);
        helper.assertTrue(f.a().flowSnapshot().orElseThrow().state().paused(), "Menu install explicitly starts paused");
        helper.assertTrue(f.b().flowSnapshot().orElseThrow().state().paused(), "Receiver is independently paused");
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(helper, f.controller(), f.player(), f.config());
        var senderBefore = f.a().flowSnapshot();
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(f.a().flowSnapshot(), senderBefore, "Editing and wiring do not run paused program");
            f.a().setPaused(false);
            helper.runAfterDelay(8, () -> {
                helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.FAILED,
                    "Writer computes 12 + 7 then reports the paused peer without blocking");
                helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().diagnostic().orElseThrow().code(),
                    ComputationException.Code.XBUS_PEER_PAUSED, "Paused pin is explicitly invalid");
                helper.assertTrue(f.a().flowSnapshot().orElseThrow().receipts().isEmpty(), "A passive wire is not a buffered queue");
                f.a().reset(); f.a().setPaused(false); f.b().setPaused(false);
                helper.runAfterDelay(6, () -> {
                    helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(19), "Named record delivered once");
                    var pos = f.config().members().get("A").position(); var entity = level.getBlockEntity(pos);
                    var saved = entity.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(pos);
                    var restored = (com.simibubi.create.foundation.blockEntity.SmartBlockEntity)Objects.requireNonNull(
                        BlockEntity.loadStatic(pos, level.getBlockState(pos), saved, level.registryAccess())); level.setBlockEntity(restored);
                    var computer = restored.getBehaviour(ComputerBehaviour.TYPE);
                    helper.assertValueEqual(computer.document().orElseThrow(), senderDocument, "Reload keeps initial 12, pure graph layout and source IDs, not runtime 19");
                    helper.assertValueEqual(computer.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(19), "Runtime remains separate from document");
                    helper.assertTrue(computer.diagnostic().isEmpty(), "Metadata matches restored sequential host");
                    helper.succeed();
                });
            });
        });
    }
    static void installThroughMenu(GameTestHelper helper, Fixture fixture, String member, ProgramDocument document) {
        var pos = fixture.config().members().get(member).position();
        installThroughMenu(helper, pos, fixture.player(), document);
    }
    private static void installThroughMenu(GameTestHelper helper, BlockPos pos, FakePlayer player, ProgramDocument document) {
        requestInstallThroughMenu(helper, pos, player, document);
        var owner = (com.simibubi.create.foundation.blockEntity.SmartBlockEntity)helper.getLevel().getBlockEntity(pos);
        helper.assertValueEqual(owner.getBehaviour(ComputerBehaviour.TYPE).document().orElseThrow(), document, "Public menu installs complete flow document");
    }
    static void requestInstallThroughMenu(GameTestHelper helper, BlockPos pos, FakePlayer player, ProgramDocument document) {
        var owner = (com.simibubi.create.foundation.blockEntity.SmartBlockEntity)helper.getLevel().getBlockEntity(pos);
        var computer = owner.getBehaviour(ComputerBehaviour.TYPE); player.moveTo(Vec3.atCenterOf(pos));
        var provider = ComputerProgramMenu.provider(owner);
        player.containerMenu = provider.createMenu(2, player.getInventory(), player);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        UUID nonce; try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        new ProgramEditorRequest(2, nonce, 1, computer.programRevision(), true, true,
            ProgramDocumentWire.encode(document, computer.hardware().limits().archive())).handle(player);
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void explicitPinsRequireLiveShellGrantAndActualReadBeforeAutomaticHandoff(GameTestHelper helper) {
        var f = fixture(helper, false); var a = f.a(); var b = f.b(); var controller = f.controller();
        var level = helper.getLevel(); var min = f.min(); var controllerPos = controller.getBlockPos();
        var player = f.player(); var config = f.config(); var empty = f.empty();
        a.disableEndpoint();
        helper.assertValueEqual(controller.configure(config, player), "closed", "Closed grant is distinct from a renamed one");
        helper.assertValueEqual(controller.routingState().orElseThrow().configuration(), empty, "Rejected configuration is atomic");
        a.configureEndpoint(new EndpointConfiguration("renamed", true));
        helper.assertValueEqual(controller.configure(config, player), "endpoint_changed", "A new grant name is not the old wire");
        a.configureEndpoint(new EndpointConfiguration("left", true));
        a.setPaused(false); b.setPaused(false);
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(a.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Same Cluster does not implicitly connect pins");
            helper.assertValueEqual(b.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_READ, "A read alone cannot invent data");
            configureThroughMenu(helper, controller, player, config);
            helper.assertTrue(controller.advertisements().isEmpty(), "Private wires do not expose service endpoints");
            b.disableEndpoint(); controller.tick();
            helper.assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty(), "Closed endpoint cannot transfer");
            b.configureEndpoint(new EndpointConfiguration("right", true));
            level.setBlock(min, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); controller.tick();
            helper.assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty(), "Broken shell blocks physical wire");
            level.setBlock(min, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
            b.setPaused(true); controller.tick();
            helper.assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty(), "Paused reader cannot transfer");
            helper.assertValueEqual(controller.configure(empty, player), "configured", "A passive wire may be unplugged while waiting");
            b.setPaused(false); controller.tick();
            helper.assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty(), "Unplugging cannot consume pending write");
            configureThroughMenu(helper, controller, player, config);
            // Rebuild the real controller from disk-shaped data before the automatic server tick.
            var saved = controller.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(controllerPos);
            var restored = (ClusterControllerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(controllerPos,
                level.getBlockState(controllerPos), saved, level.registryAccess())); level.setBlockEntity(restored);
            helper.runAfterDelay(5, () -> {
                helper.assertValueEqual(b.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Real configured controller ticks must hand off the record");
                helper.assertValueEqual(a.flowSnapshot().orElseThrow().receipts().size(), 1, "One consumed write, not mailbox admission");
                helper.assertTrue(restored.routingState().orElseThrow().mailbox().queued().isEmpty(), "XBus never enters controller mailbox");
                helper.assertValueEqual(restored.routingState().orElseThrow().configuration(), config, "Physical wire survives reload");
                helper.runAfterDelay(4, () -> {
                    helper.assertValueEqual(b.flowSnapshot().orElseThrow().receipts().size(), 1, "Following ticks do not redeliver");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void reverseDirectionAndReadableWaitUseTheSamePassiveWireWithoutConsumingEarly(GameTestHelper helper) {
        var f = fixture(helper, true); var a = f.a(); var b = f.b(); var controller = f.controller();
        // The dimension now runs logical instruction rounds. Sleep is an explicit observation barrier:
        // a host tick is no longer a promise to stop immediately after a readable notification.
        a.installFlow(new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.AwaitReadable("wait", "x0"), new FlowProgram.Sleep("barrier", "ticks"),
            new FlowProgram.Read("read", "x0", "packet", BiocomputingFlowFixture.PACKET))),
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(3))));
        a.step(); b.step(); a.setPaused(false); b.setPaused(false);
        configureThroughMenu(helper, controller, f.player(), f.config());
        controller.tick();
        helper.assertValueEqual(a.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_READABLE,
            "Controller observation never executes the reader");
        helper.runAfterDelay(1, () -> {
        helper.assertTrue(b.flowSnapshot().orElseThrow().receipts().isEmpty(), "slx must not consume sender or create receipt");
        helper.assertValueEqual(b.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Writer remains blocked after readable notification");
        helper.assertValueEqual(a.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.SLEEPING, "Reader moves past slx, then explicitly waits before reading");
        helper.assertValueEqual(a.flowSnapshot().orElseThrow().state().sleepTicks(), 3L, "Logical rounds do not consume activity ticks");
        controller.tick();
        helper.assertTrue(b.flowSnapshot().orElseThrow().receipts().isEmpty(), "Another controller call cannot skip the local Sleep");
        a.tick(); controller.tick();
        helper.assertTrue(b.flowSnapshot().orElseThrow().receipts().isEmpty(), "Same-tick member call cannot consume the writer either");
        });
        helper.runAfterDelay(8, () -> {
            helper.assertValueEqual(a.flowSnapshot().orElseThrow().state().locals().field("packet"), BiocomputingFlowFixture.inputs(23).field("packet"), "Same physical wire transfers right to left");
            helper.assertValueEqual(b.flowSnapshot().orElseThrow().receipts().size(), 1, "Exactly one actual read consumes the write");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void observingABranchAndReplacingIdentityCannotSilentlyChooseOrRebindAPeer(GameTestHelper helper) {
        var f = fixture(helper, false); var a = f.a(); var b = f.b(); var controller = f.controller(); var level = helper.getLevel();
        var bPos = f.config().members().get("B").position(); var cPos = bPos.north();
        var c = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, cPos));
        level.setBlock(cPos.below(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        c.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
        c.configureEndpoint(new EndpointConfiguration("third", true));
        var members = new LinkedHashMap<>(f.config().members()); members.put("C", new ClusterConfiguration.Member(c.communicationIdentity().orElseThrow(), cPos));
        var wire = f.config().wires().get("data");
        var branch = new XBusWire(wire.left(), new XBusWire.Pin("C", "third", "x0"), true);
        var branched = new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("data", wire, "branch", branch));
        configureThroughMenu(helper, controller, f.player(), branched);
        a.step(); b.step(); c.step(); a.setPaused(false); b.setPaused(false); c.setPaused(false); controller.tick();
        helper.assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty(), "Branch must not choose the first ready reader");
        helper.assertTrue(controller.wireDiagnostics().values().stream().allMatch("xbus_write_read"::equals),
            "Supported branches are observed without a controller-local consumption loop");
        // Keep the third member binding; only unplug its branch, without resetting anyone's program.
        var isolated = new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("data", wire));
        helper.assertValueEqual(controller.configure(isolated, f.player()), "configured", "Passive branch may be unplugged");
        var bProgram = b.flowSnapshot().orElseThrow().program();
        var savedB = level.getBlockEntity(bPos).saveWithFullMetadata(level.registryAccess());
        level.setBlock(bPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        var replacement = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, bPos));
        level.setBlock(bPos.below(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        replacement.installFlow(bProgram, new DataValue.RecordValue(Map.of()));
        replacement.configureEndpoint(new EndpointConfiguration("right", true)); replacement.step(); replacement.setPaused(false);
        controller.tick();
        helper.assertValueEqual(controller.wireDiagnostics().get("data"), "identity_changed", "Same coordinates and endpoint name do not rebind identity");
        helper.assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty(), "Replacement cannot receive old pending write");
        level.removeBlockEntity(bPos);
        var restored = Objects.requireNonNull(BlockEntity.loadStatic(bPos, level.getBlockState(bPos), savedB, level.registryAccess())); level.setBlockEntity(restored);
        controller.tick();
        helper.assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty(), "Observation alone cannot consume after replacement either");
        helper.runAfterDelay(4, () -> {
        helper.assertValueEqual(BiocomputingWorldGameTests.host((com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity)restored)
            .flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Restoring original identity resumes exact wire");
        helper.assertTrue(c.flowSnapshot().orElseThrow().receipts().isEmpty(), "Unwired third Computer never receives the packet");
        helper.succeed();
        });
    }

    record Fixture(ComputerBehaviour a, ComputerBehaviour b, ClusterControllerBlockEntity controller,
        FakePlayer player, BlockPos min, ClusterConfiguration empty, ClusterConfiguration config) {}
    private static Fixture fixture(GameTestHelper helper, boolean reverse) {
        return fixture(helper, reverse, true);
    }
    static Fixture fixture(GameTestHelper helper, boolean reverse, boolean trustedSetup) {
        return fixture(helper, reverse, trustedSetup, net.minecraft.world.entity.npc.VillagerProfession.NONE, 1,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
    }
    static Fixture fixture(GameTestHelper helper, boolean reverse, boolean trustedSetup,
            net.minecraft.world.entity.npc.VillagerProfession left, int leftLevel,
            net.minecraft.world.entity.npc.VillagerProfession right, int rightLevel) {
        var level = helper.getLevel(); var min = helper.absolutePos(new BlockPos(3, 1, 1)); var max = min.offset(2, 3, 4);
        var controllerPos = min.offset(0, 1, 1); var aPos = controllerPos.east(); var bPos = aPos.south(2);
        var a = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, aPos, left, leftLevel));
        var b = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, bPos, right, rightLevel));
        for (var pos : BlockPos.betweenClosed(min, max))
            if (pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY()
                || pos.getZ() == min.getZ() || pos.getZ() == max.getZ())
                level.setBlock(pos, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "xbus-wiring"));
        if (trustedSetup) {
        a.installFlow(new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))), BiocomputingFlowFixture.inputs(12));
        b.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
        if (reverse) {
            a.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wait", "x0"),
                new FlowProgram.Read("read", "x0", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
            b.installFlow(new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET),
                List.of(new FlowProgram.Write("write", "x1", "packet"))), BiocomputingFlowFixture.inputs(23));
        }
        } else {
            // Fresh Computers have no communication identity until a program is installed.
            // Exercise that first installation through the public menu too, not a trusted host call.
            var blank = new FlowProgram(Map.of(), List.of());
            var document = new ProgramDocument(blank.expressions(), Map.of(), Optional.empty(), Optional.of(FlowDocumentBody.empty()));
            installThroughMenu(helper, aPos, player, document); installThroughMenu(helper, bPos, player, document);
        }
        a.configureEndpoint(new EndpointConfiguration("left", true)); b.configureEndpoint(new EndpointConfiguration("right", true));
        player.moveTo(Vec3.atCenterOf(controllerPos.west()));
        var members = Map.of("A", new ClusterConfiguration.Member(a.communicationIdentity().orElseThrow(), aPos),
            "B", new ClusterConfiguration.Member(b.communicationIdentity().orElseThrow(), bPos));
        var empty = new ClusterConfiguration(members, Map.of());
        helper.assertValueEqual(controller.configure(empty, player), "configured", "Configure real touching members while paused");
        var wire = new XBusWire(new XBusWire.Pin("A", "left", "x0"), new XBusWire.Pin("B", "right", "x1"), true);
        var config = new ClusterConfiguration(members, Map.of(), Map.of(), Map.of("data", wire));
        return new Fixture(a, b, controller, player, min, empty, config);
    }

    static void configureThroughMenu(GameTestHelper helper, ClusterControllerBlockEntity controller, FakePlayer player, ClusterConfiguration config) {
        var provider = ClusterControllerMenu.provider(controller);
        player.containerMenu = provider.createMenu(1, player.getInventory(), player);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        UUID nonce; try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        var limits = CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded());
        new ClusterControlRequest(1, nonce, 1, controller.configurationRevision(), ClusterControlSession.Action.CONFIGURE,
            ClusterControlWire.encodeConfiguration(config, limits.archive())).handle(player);
        helper.assertValueEqual(controller.routingState().orElseThrow().configuration(), config, "Server menu publishes exact explicit wires");
    }
}
