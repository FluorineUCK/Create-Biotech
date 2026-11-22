package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

/** Actual cable/capability service: normal world execution plus focused explicit capture/recovery tests. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingCableXBusRouteGameTests {
    private static final Set<Direction> EAST = Set.of(Direction.EAST);
    record Fixture(BiocomputingClusterXBusGameTests.Fixture cluster, SmartBlockEntity owner, ComputerBehaviour caller) {}

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void realExternalWiringFaultsAndRecoveryReachBothDeviceLogsWithoutRunningPrograms(GameTestHelper helper) {
        var f = fixture(helper); var level = helper.getLevel(); var controller = f.cluster().controller();
        f.caller().setPaused(true); f.cluster().b().setPaused(true);
        var gateway = XBusWiringService.open(controller, "io", f.cluster().player(), () -> true);
        gateway.add(2, 1, "outside", "x1", callerPin(gateway.read(1))); gateway.setEnabled(3, 2, "outside", true);
        var store = XBusWiringSavedData.get(level);
        var first = store.enabledConnections().stream().filter(c -> c.owner().device().equals(controller.controllerId())).findFirst().orElseThrow();
        // A second explicit alias belongs to the Computer, so both physical diagnostic lifecycles are exercised.
        var reverse = store.add(store.view(first.entry().peer().device()), "gateway", first.entry().peer(), first.entry().origin(), 8, ignored -> {});
        store.setEnabled(reverse, "gateway", true, ignored -> {});
        var beforeCaller = f.caller().flowSnapshot(); var beforeMember = f.cluster().b().flowSnapshot();
        var callerId = beforeCaller.orElseThrow().state().computer(); var memberId = beforeMember.orElseThrow().state().computer();
        helper.assertTrue(!XBusHistorySavedData.get(level).tracks(callerId) && !XBusHistorySavedData.get(level).tracks(memberId),
            "Fixture starts without handoffs or history enrollment");
        int interval = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.xbusIndicatorInterval(true);
        var cut = controller.getBlockPos().west(); level.setBlockAndUpdate(cut, Blocks.AIR.defaultBlockState());
        helper.runAfterDelay(2 * interval + 1, () -> {
            assertWiringEvent(helper, f.caller().recentEvents(), "wiring_unreachable", "gateway", 1);
            assertWiringEvent(helper, controller.recentEvents(), "wiring_unreachable", "outside", 1);
            level.setBlockAndUpdate(cut, CBBlocks.DATA_CABLE.get().defaultBlockState());
            helper.runAfterDelay(2 * interval + 1, () -> {
                assertWiringEvent(helper, f.caller().recentEvents(), "wiring_restored", "gateway", 1);
                assertWiringEvent(helper, controller.recentEvents(), "wiring_restored", "outside", 1);
                helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "Diagnostics do not execute, acknowledge or unpause the caller");
                helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeMember, "Private member state remains independent");
                // Other concurrently running GameTests legitimately change the dimension-wide history.
                helper.assertTrue(!XBusHistorySavedData.get(level).tracks(callerId) && !XBusHistorySavedData.get(level).tracks(memberId),
                    "Read-only feedback cannot enroll either paused Computer in delivery history");
                helper.assertTrue(!f.caller().recentEvents().toString().contains("packet") && !controller.recentEvents().toString().contains("packet"), "Logs do not expose typed payloads");
                f.caller().disableEndpoint();
                helper.runAfterDelay(2 * interval + 1, () -> {
                    assertWiringEvent(helper, controller.recentEvents(), "wiring_closed", "outside", 1);
                    f.caller().configureEndpoint(new EndpointConfiguration("caller", true));
                    helper.runAfterDelay(2 * interval + 1, () -> {
                        assertWiringEvent(helper, controller.recentEvents(), "wiring_restored", "outside", 2);
                        helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "Closing/reopening the grant does not execute or replace the program");
                        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeMember, "Controller diagnostics do not access member RAM");
                        helper.succeed();
                    });
                });
            });
        });
    }
    private static void assertWiringEvent(GameTestHelper helper, DataValue.RecordValue log, String code, String subject, long count) {
        helper.assertValueEqual(((DataValue.ListValue)log.field("entries")).values().stream().map(DataValue.RecordValue.class::cast)
            .filter(e -> e.field("code").equals(new DataValue.TextValue(code)) && e.field("subject").equals(new DataValue.TextValue(subject))).count(), count,
            "Expected deduplicated external wiring event: " + code + " / " + subject);
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void normalExternalTicksRespectEnablePauseCableRepairAndArchiveReload(GameTestHelper helper) {
        var f = fixture(helper); var level = helper.getLevel();
        var gateway = XBusWiringService.open(f.cluster().controller(), "io", f.cluster().player(), () -> true);
        gateway.add(2, 1, "controlled", "x1", callerPin(gateway.read(1)));
        f.caller().reset(); f.cluster().b().reset(); f.caller().setPaused(false); f.cluster().b().setPaused(false);
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Declaration alone cannot start a handoff");
            helper.assertTrue(f.caller().flowSnapshot().orElseThrow().receipts().isEmpty(), "Disabled declaration does not consume");
            f.cluster().b().setPaused(true); gateway.setEnabled(3, 2, "controlled", true);
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(f.caller().flowSnapshot().orElseThrow().receipts().isEmpty(), "Enabled line cannot consume a paused peer");
                var cut = f.cluster().controller().getBlockPos().west(); level.setBlockAndUpdate(cut, Blocks.AIR.defaultBlockState());
                f.cluster().b().setPaused(false);
                helper.runAfterDelay(3, () -> {
                    helper.assertTrue(f.caller().flowSnapshot().orElseThrow().receipts().isEmpty(), "Unpaused but physically disconnected peer remains unavailable");
                    level.setBlockAndUpdate(cut, CBBlocks.DATA_CABLE.get().defaultBlockState());
                    helper.runAfterDelay(4, () -> {
                        var caller = f.caller().flowSnapshot().orElseThrow(); var member = f.cluster().b().flowSnapshot().orElseThrow();
                        helper.assertValueEqual(caller.state().status(), FlowRuntime.Status.COMPLETE, "Repair resumes the original operation");
                        helper.assertValueEqual(caller.receipts().size(), 2, "No duplicate or buffered delivery");
                        var restoredCaller = BiocomputingClusterXBusGameTests.reloadComputer(helper, f.owner().getBlockPos());
                        var restoredMember = BiocomputingClusterXBusGameTests.reloadComputer(helper, f.cluster().config().members().get("B").position());
                        helper.runAfterDelay(4, () -> {
                            helper.assertValueEqual(restoredCaller.flowSnapshot().orElseThrow(), caller, "Normal ticks after archive reload do not replay caller");
                            helper.assertValueEqual(restoredMember.flowSnapshot().orElseThrow(), member, "Normal ticks after archive reload do not replay member");
                            helper.succeed();
                        });
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void normalMixedNetworkChoosesOnlyOneWriterWithoutTheOldInternalLoop(GameTestHelper helper) {
        var f = combined(helper).fixture();
        f.cluster().b().reset(); f.caller().reset();
        f.cluster().b().installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET))),
            new DataValue.RecordValue(Map.of()));
        f.caller().installFlow(new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))),
            BiocomputingFlowFixture.inputs(12));
        f.cluster().a().reset();
        f.cluster().a().setPaused(false); f.cluster().b().setPaused(false); f.caller().setPaused(false);
        helper.runAfterDelay(5, () -> {
            var a = f.cluster().a().flowSnapshot().orElseThrow(); var b = f.cluster().b().flowSnapshot().orElseThrow(); var caller = f.caller().flowSnapshot().orElseThrow();
            helper.assertValueEqual(b.state().status(), FlowRuntime.Status.COMPLETE, "Multi-point read completes on normal ticks");
            helper.assertValueEqual(b.receipts().size(), 1, "Exactly one writer was consumed");
            helper.assertValueEqual(a.receipts().size() + caller.receipts().size(), 1, "Internal and external legs share one arbitration domain");
            boolean internalWon = !a.receipts().isEmpty();
            helper.assertValueEqual((internalWon ? caller : a).state().status(), FlowRuntime.Status.WAITING_WRITE, "Other writer keeps waiting, not buffered");
            helper.assertValueEqual(b.state().locals().field("packet"), BiocomputingFlowFixture.inputs(internalWon ? 31 : 12).field("packet"), "Selected typed value arrives intact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void enabledExternalWireRunsInstalledProgramsInNormalWorldTicks(GameTestHelper helper) {
        var f = fixture(helper, net.minecraft.world.entity.npc.VillagerProfession.CLERIC, 5);
        var gateway = XBusWiringService.open(f.cluster().controller(), "io", f.cluster().player(), () -> true);
        gateway.add(2, 1, "automatic", "x1", callerPin(gateway.read(1))); gateway.setEnabled(3, 2, "automatic", true);
        // Rewind fixture preparation: execution below is exclusively normal world ticks, not step/exchange/cycle calls.
        f.caller().reset(); f.cluster().b().reset(); f.caller().setPaused(false); f.cluster().b().setPaused(false);
        var callerHardware = f.caller().hardware(); var memberHardware = f.cluster().b().hardware();
        helper.runAfterDelay(5, () -> {
            helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE,
                "Normal dimension ticks must execute both directions of the explicitly enabled external wire");
            helper.assertValueEqual(f.cluster().b().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE, "Member completes");
            helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().state().locals().field("answer"),
                BiocomputingFlowFixture.inputs(12).field("packet"), "Named typed record returns intact");
            helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().receipts().size(), 2, "Exactly two durable handoffs");
            helper.assertTrue(!callerHardware.equals(memberHardware), "Genuinely different resident hardware");
            helper.assertValueEqual(f.caller().hardware(), callerHardware, "Caller keeps its own hardware");
            helper.assertValueEqual(f.cluster().b().hardware(), memberHardware, "Member keeps its own hardware");
            helper.assertTrue(f.cluster().controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "Gateway does not buffer the record");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void anExecutionCycleCannotConsumeASameIdentityReplacementOutsideItsPhysicalRoster(GameTestHelper helper) {
        var mixed = combined(helper); var f = mixed.fixture(); var level = helper.getLevel();
        var cycle = new ComputerExecutionCycle(level, List.of(f.cluster().a(), f.caller(), f.cluster().b()), level.getGameTime());
        var beforeA = f.cluster().a().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot();
        var saved = f.owner().saveWithFullMetadata(level.registryAccess()); var pos = f.owner().getBlockPos(); var state = f.owner().getBlockState();
        level.removeBlockEntity(pos);
        var replacement = (SmartBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(replacement);
        helper.assertValueEqual(cycle.advanceRound(0x100010001L).exchanges(), 0,
            "Restoring the same UUID does not enroll a different physical instance in an already opened cycle");
        helper.assertValueEqual(f.cluster().a().flowSnapshot(), beforeA, "Do not drop the replaced branch and consume the internal subset");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "The reader remains pending");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void installedExternalProgramsCompleteRoundTripThroughExplicitUnifiedRounds(GameTestHelper helper) {
        var f = fixture(helper); var gateway = XBusWiringService.open(f.cluster().controller(), "io", f.cluster().player(), () -> true);
        gateway.add(2, 1, "round_trip", "x1", callerPin(gateway.read(1))); gateway.setEnabled(3, 2, "round_trip", true);
        var cycle = new ComputerExecutionCycle(helper.getLevel(), List.of(f.caller(), f.cluster().b()), helper.getLevel().getGameTime());
        int exchanges = 0;
        for (int round = 0; round < 16; round++) {
            var result = cycle.advanceRound(0x100010001L + round); exchanges += result.exchanges();
            if (!result.progressed()) break;
        }
        helper.assertValueEqual(exchanges, 2, "Public write/read programs complete both directions without direct test exchange calls");
        helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE, "Caller completes");
        helper.assertValueEqual(f.cluster().b().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE, "Member completes");
        helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().state().locals().field("answer"),
            BiocomputingFlowFixture.inputs(12).field("packet"), "Named record returns through the same passive public pin");
        helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().receipts().size(), 2, "Exactly two durable handoffs");
        helper.assertTrue(f.cluster().controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "No gateway payload queue");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void unifiedRoundsRetainActualProfessionLevelBudgetsWithoutPooling(GameTestHelper helper) {
        var f = fixture(helper, net.minecraft.world.entity.npc.VillagerProfession.CLERIC, 5);
        var a = f.cluster().a(); var b = f.cluster().b(); a.reset(); b.reset();
        var loop = new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end")));
        var empty = new DataValue.RecordValue(Map.of()); a.installFlow(loop, empty); b.installFlow(loop, empty);
        a.setPaused(false); b.setPaused(false);
        long aLimit = a.hardware().limits().hardware().workPerSlice(), bLimit = b.hardware().limits().hardware().workPerSlice();
        helper.assertTrue(aLimit != bLimit, "Actual professions/levels in this fixture have different execution speeds");
        var cycle = new ComputerExecutionCycle(helper.getLevel(), List.of(b, a), helper.getLevel().getGameTime());
        long spent = 0;
        for (int round = 0; round <= Math.max(aLimit, bLimit); round++) spent += cycle.advanceRound(0x100010001L + round).work();
        helper.assertValueEqual(a.flowSnapshot().orElseThrow().state().work(), aLimit, "Lower-speed Computer stops at its own allowance");
        helper.assertValueEqual(b.flowSnapshot().orElseThrow().state().work(), bLimit, "Other member retains its independent allowance");
        helper.assertValueEqual(spent, aLimit + bLimit, "No pooled, multiplied or controller-supplied work");
        helper.assertTrue(!new ComputerExecutionCycle(helper.getLevel(), List.of(a, b), helper.getLevel().getGameTime())
            .advanceRound(999).progressed(), "Constructing a second same-tick cycle cannot refill either machine");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void unifiedAvailabilityWakeDoesNotConsumeWriterOrRunTheFollowingReadInTheSameRound(GameTestHelper helper) {
        var f = fixture(helper); var reader = f.cluster().b(); reader.reset();
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wait", "x1"),
            new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
        reader.setPaused(false);
        var gateway = XBusWiringService.open(f.cluster().controller(), "io", f.cluster().player(), () -> true);
        gateway.add(2, 1, "availability", "x1", callerPin(gateway.read(1))); gateway.setEnabled(3, 2, "availability", true);
        var beforeWriter = f.caller().flowSnapshot();
        var cycle = new ComputerExecutionCycle(helper.getLevel(), List.of(reader, f.caller()), helper.getLevel().getGameTime());
        var wake = cycle.advanceRound(0x100010001L);
        helper.assertValueEqual(wake.wakes(), 1, "Availability wait wakes once"); helper.assertValueEqual(wake.exchanges(), 0, "Peek does not consume");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeWriter, "Writer retains the entire pending value and continuation");
        helper.assertValueEqual(reader.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.READY, "Read has not been entered yet");
        helper.assertValueEqual(cycle.advanceRound(0x100010002L).exchanges(), 1, "The next instruction round executes the actual read");
        helper.assertValueEqual(reader.flowSnapshot().orElseThrow().state().locals().field("packet"),
            BiocomputingFlowFixture.inputs(12).field("packet"), "Typed named record arrives only on consumption");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void unifiedInstructionRoundUsesOneMixedNetworkAndDoesNotRunAnAcknowledgedWriterTwice(GameTestHelper helper) {
        var mixed = combined(helper); var f = mixed.fixture();
        var beforeA = f.cluster().a().flowSnapshot().orElseThrow();
        var beforeCaller = f.caller().flowSnapshot().orElseThrow();
        var hardware = List.of(f.cluster().a().hardware(), f.cluster().b().hardware(), f.caller().hardware());
        var cycle = new ComputerExecutionCycle(helper.getLevel(),
            List.of(f.caller(), f.cluster().b(), f.cluster().a()), helper.getLevel().getGameTime());
        var result = cycle.advanceRound(0x100010001L);
        helper.assertValueEqual(result.exchanges(), 1, "Internal and external declarations share one successful exchange this round");
        helper.assertValueEqual(result.wakes(), 0, "A read is not a non-consuming availability wake");
        boolean internalWon = f.cluster().a().flowSnapshot().orElseThrow().state().cursor() != beforeA.state().cursor();
        var selectedBefore = internalWon ? beforeA : beforeCaller;
        var selectedAfter = (internalWon ? f.cluster().a() : f.caller()).flowSnapshot().orElseThrow();
        helper.assertValueEqual(selectedAfter.state().operation(), selectedBefore.state().operation() + 1,
            "Acknowledgement completes the write; the later CPU turn must not also execute the next instruction");
        helper.assertValueEqual(f.cluster().b().flowSnapshot().orElseThrow().state().locals().field("packet"),
            new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(internalWon ? 31 : 12))), "Copied selected record");
        helper.assertValueEqual(List.of(f.cluster().a().hardware(), f.cluster().b().hardware(), f.caller().hardware()), hardware,
            "Execution cycle does not replace or pool heterogeneous hardware");
        helper.assertTrue(f.cluster().controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "No controller buffer");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void unifiedRoundRepairsTheOriginalReadBeforeConsideringACompetingWriter(GameTestHelper helper) {
        var mixed = combined(helper); var f = mixed.fixture(); var level = helper.getLevel();
        var block = f.cluster().b().blockEntity; var oldRead = block.saveWithFullMetadata(level.registryAccess());
        var readerPin = mixed.internal().right().pin(); var competitor = mixed.internal().left().pin();
        helper.assertValueEqual(mixed.network().exchange(mixed.outside(), readerPin), XBusExchange.Status.EXCHANGED, "Fixture originally delivers 12");
        level.removeBlockEntity(block.getBlockPos());
        var restored = (SmartBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(block.getBlockPos(), block.getBlockState(), oldRead, level.registryAccess()));
        level.setBlockEntity(restored); var reader = restored.getBehaviour(ComputerBehaviour.TYPE);
        // Choose a fixture seed that WOULD select the competing 31 without the recovery barrier.
        long seed = java.util.stream.LongStream.range(0, 256).filter(value -> mixed.topology().round(value)
            .peek(readerPin, Set.of(competitor, mixed.outside())).orElseThrow().writer().equals(competitor)).findFirst().orElseThrow();
        var beforeCompetitor = f.cluster().a().flowSnapshot();
        var cycle = new ComputerExecutionCycle(level, List.of(f.cluster().a(), f.caller(), reader), level.getGameTime());
        helper.assertValueEqual(cycle.advanceRound(seed).exchanges(), 1, "One original handoff is repaired, not a fresh competing write");
        helper.assertValueEqual(reader.flowSnapshot().orElseThrow().state().locals().field("packet"),
            BiocomputingFlowFixture.inputs(12).field("packet"), "Restored reader gets the original copied record, never 31");
        helper.assertValueEqual(f.cluster().a().flowSnapshot(), beforeCompetitor, "Competing writer is left pending");
        helper.assertValueEqual(reader.flowSnapshot().orElseThrow().receipts().size(), 1, "Repair retains the original handoff identity");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void unifiedRoundRepairsAnOldWriterEvenWhenNoComputerCurrentlyWaitsToRead(GameTestHelper helper) {
        var mixed = combined(helper); var f = mixed.fixture(); var level = helper.getLevel();
        var oldWriter = f.owner().saveWithFullMetadata(level.registryAccess());
        helper.assertValueEqual(mixed.network().exchange(mixed.outside(), mixed.internal().right().pin()),
            XBusExchange.Status.EXCHANGED, "Fixture saves the reader after accepting 12");
        level.removeBlockEntity(f.owner().getBlockPos());
        var restored = (SmartBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(f.owner().getBlockPos(), f.owner().getBlockState(), oldWriter, level.registryAccess()));
        level.setBlockEntity(restored); var writer = restored.getBehaviour(ComputerBehaviour.TYPE);
        var beforeCompetitor = f.cluster().a().flowSnapshot();
        var cycle = new ComputerExecutionCycle(level, List.of(f.cluster().a(), writer, f.cluster().b()), level.getGameTime());
        helper.assertValueEqual(cycle.advanceRound(0x100010001L).exchanges(), 1,
            "Historical writer acknowledgement must not depend on a new ordinary read being ready");
        helper.assertValueEqual(writer.flowSnapshot().orElseThrow().state().cursor(), 1, "Old write is acknowledged exactly once");
        helper.assertValueEqual(f.cluster().b().flowSnapshot().orElseThrow().state().locals().field("packet"),
            BiocomputingFlowFixture.inputs(12).field("packet"), "Already committed reader data is unchanged");
        helper.assertValueEqual(f.cluster().a().flowSnapshot(), beforeCompetitor, "Third writer still retains 31");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void realInternalWireAndCableAliasShareOnePhysicalNetworkWithoutPoolingHardware(GameTestHelper helper) {
        var combined = combined(helper); var f = combined.fixture(); var internal = combined.internal();
        var writer = internal.left().pin(); var reader = internal.right().pin(); var outside = combined.outside();
        var positions = Map.of(writer, internal.left().position(), reader, internal.right().position(),
            outside, f.owner().getBlockPos());
        var canonical = positions.keySet().stream().sorted(Comparator
            .comparingInt((XBusNetwork.Pin p) -> positions.get(p).getY())
            .thenComparingInt(p -> positions.get(p).getZ()).thenComparingInt(p -> positions.get(p).getX())).toList();
        var topology = combined.topology();
        helper.assertValueEqual(topology.components().size(), 1, "Internal and public aliases refer to the same physical member pin");
        helper.assertValueEqual(topology.components().getFirst(), canonical, "Discovery orders actual Computers Y/Z/X, not gateway coordinates");
        // Original-counter fixture seed only. This does not select Minecraft's clock mapping.
        var round = topology.round(0x100010001L);
        var beforeA = f.cluster().a().flowSnapshot().orElseThrow(); var beforeB = f.cluster().b().flowSnapshot().orElseThrow();
        var beforeCaller = f.caller().flowSnapshot().orElseThrow();
        var hardware = List.of(f.cluster().a().hardware(), f.cluster().b().hardware(), f.caller().hardware());
        helper.assertTrue(!hardware.get(0).limits().equals(hardware.get(1).limits()), "Distinct resident profession/level yields independent hardware");
        var selected = new java.util.concurrent.atomic.AtomicReference<XBusNetwork.Selection>();
        helper.assertValueEqual(round.attempt(reader, Set.of(writer, outside), pair -> {
            selected.set(pair); return combined.network().exchange(pair.reader(), pair.writer());
        }), XBusExchange.Status.EXCHANGED, "The complete mixed group performs one real rendezvous");
        boolean internalWon = selected.get().writer().equals(writer);
        helper.assertValueEqual(f.cluster().b().flowSnapshot().orElseThrow().state().locals().field("packet"),
            new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(internalWon ? 31 : 12))),
            "Exactly the selected writer's copied record reaches the member");
        helper.assertValueEqual(internalWon ? f.caller().flowSnapshot().orElseThrow() : f.cluster().a().flowSnapshot().orElseThrow(),
            internalWon ? beforeCaller : beforeA, "The unselected writer retains its entire pending state");
        helper.assertTrue(round.occupied(writer) && round.occupied(outside), "Both internal and external legs share network occupancy");
        helper.assertValueEqual(round.attempt(reader, Set.of(writer, outside), ignored -> {
            throw new IllegalStateException("An alias cannot obtain a second consumption this round");
        }), XBusExchange.Status.NOT_READY, "No second handoff through another route to the same pin");
        helper.assertValueEqual(List.of(f.cluster().a().hardware(), f.cluster().b().hardware(), f.caller().hardware()), hardware,
            "Communication never pools or replaces member hardware");
        helper.assertValueEqual(f.cluster().a().flowSnapshot().orElseThrow().state().work(), beforeA.state().work(), "No extra local execution at A");
        helper.assertValueEqual(f.cluster().b().flowSnapshot().orElseThrow().state().work(), beforeB.state().work(), "No extra local execution at B");
        helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().state().work(), beforeCaller.state().work(), "No extra caller execution");
        helper.assertTrue(f.cluster().controller().routingState().orElseThrow().mailbox().queued().isEmpty(), "Controller remains passive, without payload buffering");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void revokingAnUnselectedInternalBranchBlocksTheWholeCapturedMixedNetwork(GameTestHelper helper) {
        var combined = combined(helper); var f = combined.fixture();
        var beforeA = f.cluster().a().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot(); var beforeCaller = f.caller().flowSnapshot();
        f.cluster().a().disableEndpoint();
        helper.assertValueEqual(combined.network().exchange(combined.outside(), combined.internal().right().pin()),
            XBusExchange.Status.CLOSED, "External pair cannot bypass the revoked internal participant");
        helper.assertValueEqual(f.cluster().a().flowSnapshot(), beforeA, "Unselected internal owner retains its pending value");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "Member reader remains unconsumed");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "External writer remains unconsumed");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void disablingAnExternalAliasAlsoRevokesAnAlreadyCapturedInternalPair(GameTestHelper helper) {
        var combined = combined(helper); var f = combined.fixture(); var controller = f.cluster().controller();
        var beforeA = f.cluster().a().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot(); var beforeCaller = f.caller().flowSnapshot();
        var edit = XBusWiringService.open(controller, "io", f.cluster().player(), () -> true);
        edit.read(1); edit.setEnabled(2, 1, "mixed", false);
        helper.assertValueEqual(combined.network().exchange(combined.internal().left().pin(), combined.internal().right().pin()),
            XBusExchange.Status.STALE, "The internal pair cannot ignore a changed external declaration in its captured network");
        helper.assertValueEqual(f.cluster().a().flowSnapshot(), beforeA, "Internal writer remains unconsumed");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "Internal reader remains unconsumed");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "External pending value is not discarded");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void attachingAnExternalBranchRevokesAnInternalOnlyCapture(GameTestHelper helper) {
        var mixed = combined(helper); var f = mixed.fixture(); var controller = f.cluster().controller();
        var edit = XBusWiringService.open(controller, "io", f.cluster().player(), () -> true);
        edit.read(1); edit.setEnabled(2, 1, "mixed", false);
        var isolated = XBusConnectedNetwork.capture(helper.getLevel(), f.cluster().a(), "x0").connected().orElseThrow();
        helper.assertValueEqual(isolated.topology().components().getFirst().size(), 2, "Disabled external declaration is not a branch");
        var beforeA = f.cluster().a().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot();
        edit.read(3); edit.setEnabled(4, 3, "mixed", true);
        helper.assertValueEqual(isolated.network().exchange(mixed.internal().left().pin(), mixed.internal().right().pin()),
            XBusExchange.Status.STALE, "A newly enabled external branch revokes an internal-only capture");
        var complete = XBusConnectedNetwork.capture(helper.getLevel(), f.cluster().a(), "x0").connected().orElseThrow();
        helper.assertValueEqual(complete.topology().components().getFirst().size(), 3, "Starting inside discovers the external writer too");
        helper.assertValueEqual(f.cluster().a().flowSnapshot(), beforeA, "Discovery does not execute or consume A");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "Discovery does not execute or consume B");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void attachingAnInternalBranchRevokesAnExternalOnlyCapture(GameTestHelper helper) {
        var mixed = combined(helper); var f = mixed.fixture(); var controller = f.cluster().controller();
        var old = controller.routingState().orElseThrow().configuration(); var wire = old.wires().get("data");
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, f.cluster().player(),
            new ClusterConfiguration(old.members(), old.ports(), old.connections(),
                Map.of("data", new XBusWire(wire.left(), wire.right(), false)), old.signals(), old.publicPins()));
        var isolated = XBusConnectedNetwork.capture(helper.getLevel(), f.caller(), "x0").connected().orElseThrow();
        helper.assertValueEqual(isolated.topology().components().getFirst().size(), 2, "Initially only the external pair is connected");
        var beforeB = f.cluster().b().flowSnapshot(); var beforeCaller = f.caller().flowSnapshot();
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, f.cluster().player(), old);
        helper.assertValueEqual(isolated.network().exchange(mixed.outside(), mixed.internal().right().pin()),
            XBusExchange.Status.STALE, "A new internal branch must invalidate an external-only component");
        var complete = XBusConnectedNetwork.capture(helper.getLevel(), f.caller(), "x0").connected().orElseThrow();
        helper.assertValueEqual(complete.topology().components().getFirst().size(), 3, "Recapture includes the internal writer");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "Old pair cannot consume B");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "Old pair cannot consume external writer");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void unavailableConfiguredBranchesCannotDisappearFromMixedDiscovery(GameTestHelper helper) {
        var mixed = combined(helper); var f = mixed.fixture();
        var beforeA = f.cluster().a().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot(); var beforeCaller = f.caller().flowSnapshot();
        f.cluster().a().disableEndpoint();
        helper.assertValueEqual(XBusConnectedNetwork.capture(helper.getLevel(), f.caller(), "x0").code(), "closed",
            "A closed internal branch prevents capturing only the usable external pair");
        f.cluster().a().configureEndpoint(new EndpointConfiguration("left", true));
        helper.getLevel().setBlockAndUpdate(f.cluster().controller().getBlockPos().west(2), Blocks.AIR.defaultBlockState());
        helper.assertValueEqual(XBusConnectedNetwork.capture(helper.getLevel(), f.cluster().a(), "x0").code(), "unreachable",
            "A broken external cable prevents capturing only the usable internal pair");
        helper.getLevel().setBlockAndUpdate(f.cluster().controller().getBlockPos().west(2), CBBlocks.DATA_CABLE.get().defaultBlockState());
        helper.assertValueEqual(XBusConnectedNetwork.capture(helper.getLevel(), f.cluster().a(), "x0").code(), "ready", "Physical repair permits full recapture");
        helper.getLevel().setBlockAndUpdate(f.owner().getBlockPos(), Blocks.AIR.defaultBlockState());
        helper.assertValueEqual(XBusConnectedNetwork.capture(helper.getLevel(), f.cluster().a(), "x0").code(), "not_found",
            "An absent external device is not an unwired pin");
        helper.assertValueEqual(f.cluster().a().flowSnapshot(), beforeA, "Internal source is untouched");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "Internal destination is untouched");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "Observation did not consume removed caller's data");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void physicalHardwarePinOrderSurvivesReversedWireInsertion(GameTestHelper helper) {
        var mixed = combined(helper); var f = mixed.fixture(); var controller = f.cluster().controller();
        var old = controller.routingState().orElseThrow().configuration();
        var wires = new LinkedHashMap<String, XBusWire>();
        wires.put("first", new XBusWire(new XBusWire.Pin("A", "left", "x1"), new XBusWire.Pin("B", "right", "x1"), true));
        wires.putAll(old.wires());
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, f.cluster().player(),
            new ClusterConfiguration(old.members(), old.ports(), old.connections(), wires, old.signals(), old.publicPins()));
        var complete = XBusConnectedNetwork.capture(helper.getLevel(), f.caller(), "x0").connected().orElseThrow();
        var canonical = complete.topology().components().getFirst(); var a = f.cluster().a().communicationIdentity().orElseThrow();
        helper.assertValueEqual(canonical.size(), 4, "Physical pin aliases are deduplicated, distinct hardware pins are not");
        helper.assertValueEqual(canonical.stream().filter(p -> p.computer().equals(a)).map(XBusNetwork.Pin::pin).toList(),
            f.cluster().a().hardware().limits().pins().xbus(), "Hardware pin order, not declaration insertion order");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void controllerObservationCannotConsumeAnEnabledMixedNetwork(GameTestHelper helper) {
        var mixed = combined(helper); var f = mixed.fixture();
        var beforeA = f.cluster().a().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot();
        f.cluster().controller().tick();
        helper.assertValueEqual(f.cluster().controller().wireDiagnostics().get("data"), "xbus_write_read",
            "Multi-point networks are supported, but controller observation never acts as a second execution loop");
        helper.assertValueEqual(f.cluster().a().flowSnapshot(), beforeA, "Internal writer is not consumed behind the external writer's back");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "Shared reader is not consumed by a separate loop");
        helper.succeed();
    }
    private record Combined(Fixture fixture, XBusWireBinding internal, XBusNetwork.Pin outside,
            XBusAccess.Network network, XBusNetwork topology) {}
    private static Combined combined(GameTestHelper helper) {
        var f = fixture(helper, net.minecraft.world.entity.npc.VillagerProfession.CLERIC, 5);
        var controller = f.cluster().controller();
        f.cluster().a().installFlow(new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET),
            List.of(new FlowProgram.Write("send", "x0", "packet"))), BiocomputingFlowFixture.inputs(31));
        f.cluster().a().step(); f.cluster().a().setPaused(false);
        var old = controller.routingState().orElseThrow().configuration();
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, f.cluster().player(),
            new ClusterConfiguration(old.members(), old.ports(), old.connections(), f.cluster().config().wires(), old.signals(), old.publicPins()));
        var gateway = XBusWiringService.open(controller, "io", f.cluster().player(), () -> true);
        gateway.add(2, 1, "mixed", "x1", callerPin(gateway.read(1))); gateway.setEnabled(3, 2, "mixed", true);
        var internal = controller.captureXBusWire("data").binding().orElseThrow();
        // Start from just one real pin. Production capture, not a hand-written test edge list, finds all branches.
        var captured = XBusConnectedNetwork.capture(helper.getLevel(), f.caller(), "x0");
        helper.assertValueEqual(captured.code(), "ready", "Complete internal/external discovery is authorized");
        var connected = captured.connected().orElseThrow(); var network = connected.network();
        helper.assertTrue(network.inspect().rejection().isEmpty(), "Every actual internal and external alias remains authorized");
        return new Combined(f, internal, new XBusNetwork.Pin(f.caller().communicationIdentity().orElseThrow(), "x0"),
            network, connected.topology());
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void actualDevicesOwnReadOnlyWorldIndicatorsWithoutAControllerRoutingLoop(GameTestHelper helper) {
        var f = fixture(helper); var controller = f.cluster().controller(); var level = helper.getLevel();
        var callerIndicator = f.owner().getBehaviour(XBusWiringIndicatorBehaviour.TYPE);
        var gatewayIndicator = controller.getBehaviour(XBusWiringIndicatorBehaviour.TYPE);
        helper.assertTrue(callerIndicator != null && gatewayIndicator != null, "Both registered physical devices own the indicator lifecycle");
        var gateway = XBusWiringService.open(controller, "io", f.cluster().player(), () -> true);
        gateway.add(2, 1, "visible_wire", "x1", callerPin(gateway.read(1)));
        gateway.setEnabled(3, 2, "visible_wire", true);
        var store = XBusWiringSavedData.get(level);
        var ticket = store.enabledConnections().stream().filter(c -> c.owner().device().equals(controller.controllerId())).findFirst().orElseThrow();
        var beforeCaller = f.caller().flowSnapshot(); var beforeMember = f.cluster().b().flowSnapshot();
        var history = XBusHistorySavedData.get(level).save(new net.minecraft.nbt.CompoundTag(), level.registryAccess());
        helper.assertValueEqual(XBusWiringObservation.inspect(level, store, ticket).code(), "ready", "Physical cable and passive public pin are observable");
        callerIndicator.tick(); gatewayIndicator.tick();
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "Observation does not advance or acknowledge the external Computer");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeMember, "Observation does not advance or acknowledge a private member");
        helper.assertValueEqual(XBusHistorySavedData.get(level).save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()),
            history, "Observation cannot add or retire recovery evidence");
        level.setBlockAndUpdate(controller.getBlockPos().west(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(XBusWiringObservation.inspect(level, store, ticket).modes().isEmpty(), "Broken cable is not a valid gray connection");
        level.setBlockAndUpdate(controller.getBlockPos().west(), CBBlocks.DATA_CABLE.get().defaultBlockState());
        helper.assertValueEqual(XBusWiringObservation.inspect(level, store, ticket).code(), "ready", "Restoring the physical cable restores the same binding");
        gateway.setEnabled(4, 3, "visible_wire", false);
        helper.assertValueEqual(XBusWiringObservation.inspect(level, store, ticket).code(), "stale", "Disabled declarations stop old feedback");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void anotherPhysicalOwnersEnabledAliasRevokesCapturedComponentWithoutBorrowingMemberResources(GameTestHelper helper) {
        var f = fixture(helper, net.minecraft.world.entity.npc.VillagerProfession.CLERIC, 5);
        var level = helper.getLevel(); var controller = f.cluster().controller(); var player = f.cluster().player();
        var gateway = XBusWiringService.open(controller, "io", player, () -> true);
        gateway.add(2, 1, "gateway", "x1", callerPin(gateway.read(1)));
        gateway.setEnabled(3, 2, "gateway", true);
        var store = XBusWiringSavedData.get(level);
        var initial = store.enabledConnections().stream().filter(c -> c.owner().device().equals(controller.controllerId())).toList();
        var first = XBusCableNetwork.captureEnabled(level, initial);
        helper.assertValueEqual(first.code(), "ready", "Actual cable and passive gateway form the captured component");
        var beforeCaller = f.caller().flowSnapshot(); var beforeMember = f.cluster().b().flowSnapshot();
        var callerHardware = f.caller().hardware(); var memberHardware = f.cluster().b().hardware();
        helper.assertTrue(!callerHardware.equals(memberHardware), "The member and external Computer really use different resident hardware");
        var position = f.owner().getBlockPos(); player.setPos(position.getX() + .5, position.getY() + .5, position.getZ() + .5);
        var caller = XBusWiringService.open(f.owner(), player, () -> true);
        var peers = WiringStatusView.decode(caller.read(1)).peers(); int selected = -1;
        for (int i = 0; i < peers.size(); i++) if (peers.get(i).endpoint().equals("io") && peers.get(i).pin().equals("x1")) selected = i;
        helper.assertTrue(selected >= 0, "The caller discovers the explicitly exposed gateway pin");
        caller.add(2, 1, "caller_alias", "x0", selected);
        caller.setEnabled(3, 2, "caller_alias", true);
        var left = new XBusNetwork.Pin(f.caller().communicationIdentity().orElseThrow(), "x0");
        var right = new XBusNetwork.Pin(f.cluster().b().communicationIdentity().orElseThrow(), "x1");
        helper.assertValueEqual(first.network().orElseThrow().exchange(left, right), XBusExchange.Status.STALE,
            "Another owner added a physical-pin alias: the old captured network cannot consume");
        helper.assertValueEqual(XBusCableNetwork.captureEnabled(level, initial).code(), "incomplete_configuration",
            "Recapturing only the old declaration cannot hide the newly enabled alias");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "Rejected capture does not spend caller state");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeMember, "Rejected capture does not spend member state");
        var complete = store.enabledConnections().stream().filter(c -> c.owner().device().equals(controller.controllerId())
            || c.owner().device().equals(left.computer())).toList();
        var fresh = XBusCableNetwork.captureEnabled(level, complete);
        helper.assertValueEqual(fresh.code(), "ready", "Recapture includes both owners and retains both route guards");
        helper.assertValueEqual(fresh.diagnostics().size(), 4, "Both directions of both aliases are verified");
        helper.assertValueEqual(fresh.network().orElseThrow().exchange(left, right), XBusExchange.Status.EXCHANGED,
            "The original waiting read still receives once through a fresh complete capture");
        helper.assertValueEqual(f.cluster().b().flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12),
            "The member gets the exact original value, not a controller buffer");
        helper.assertValueEqual(f.caller().hardware(), callerHardware, "Caller hardware remains independent");
        helper.assertValueEqual(f.cluster().b().hardware(), memberHardware, "Member profession, level and budget remain independent");
        helper.assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty(), "The passive gateway does not receive the packet");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void actualControllerMenuConfiguresItsPublicSourceWithoutExecutingEitherComputer(GameTestHelper helper) {
        var f = fixture(helper); var controller = f.cluster().controller(); var player = f.cluster().player();
        var pos = controller.getBlockPos(); player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        var provider = com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControllerMenu.provider(controller);
        player.containerMenu = provider.createMenu(9, player.getInventory(), player);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        UUID nonce; try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        var beforeCaller = f.caller().flowSnapshot(); var beforeMember = f.cluster().b().flowSnapshot();
        var archive = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.cluster(true).archive();
        var read = new DataValue.RecordValue(Map.of("endpoint", new DataValue.TextValue("io")));
        new com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlRequest(9, nonce, 1, controller.configurationRevision(),
            com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlSession.Action.valueOf("READ_WIRING"),
            ProgramEditorWire.encodeValue(read, archive)).handle(player);
        var add = new WiringControlData.Add(1, "controller_menu_wire", "x1", 0).encode();
        new com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlRequest(9, nonce, 2, controller.configurationRevision(),
            com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlSession.Action.valueOf("ADD_WIRING"),
            ProgramEditorWire.encodeValue(add, archive)).handle(player);
        var store = XBusWiringSavedData.get(helper.getLevel()); var saved = store.view(controller.controllerId()).entries();
        helper.assertValueEqual(saved.size(), 1, "Real controller menu requests publish a controller-owned declaration");
        helper.assertValueEqual(saved.getFirst().origin().position(), pos, "Origin remains the physical controller");
        helper.assertValueEqual(saved.getFirst().origin().endpoint(), "io", "Only the selected public source is used");
        new com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlRequest(9, nonce, 3, controller.configurationRevision(),
            com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlSession.Action.valueOf("REMOVE_WIRING"),
            ProgramEditorWire.encodeValue(new WiringControlData.Remove(2, "controller_menu_wire").encode(), archive)).handle(player);
        helper.assertTrue(store.view(controller.controllerId()).entries().isEmpty(), "Actual menu explicitly removes its declaration");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "Configuration does not run the caller");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeMember, "Configuration does not run or buffer for the member");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void controllerPublicPinIsAnActualWiringSourceWithoutBorrowingMemberCoordinates(GameTestHelper helper) {
        var f = fixture(helper); var controller = f.cluster().controller(); var player = f.cluster().player();
        var pos = controller.getBlockPos(); player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        var beforeCaller = f.caller().flowSnapshot(); var beforeMember = f.cluster().b().flowSnapshot();
        var session = XBusWiringService.open(controller, "io", player, () -> true);
        var read = (DataValue.RecordValue) session.read(1).field("wiring");
        helper.assertValueEqual(read.field("local_pins"), new DataValue.ListValue(List.of(new DataValue.TextValue("x1"))),
            "Only this explicitly exposed member pin is a local source");
        var peers = ((DataValue.ListValue) read.field("peer_pins")).values(); int selected = -1;
        for (int i = 0; i < peers.size(); i++) {
            var peer = (DataValue.RecordValue) peers.get(i);
            if (peer.field("endpoint").equals(new DataValue.TextValue("caller"))
                    && peer.field("pin").equals(new DataValue.TextValue("x0"))) selected = i;
        }
        helper.assertTrue(selected >= 0, "Controller outside cable discovers the actual caller, without a member cable");
        session.add(2, 1, "gateway_wire", "x1", selected);
        var store = XBusWiringSavedData.get(helper.getLevel()); var saved = store.view(controller.controllerId()).entries().getFirst();
        helper.assertValueEqual(saved.origin().position(), pos, "Physical source is the controller, not the member");
        helper.assertValueEqual(saved.origin().device(), controller.controllerId(), "Namespace and physical identity belong to the controller");
        helper.assertValueEqual(saved.origin().computer(), f.cluster().b().communicationIdentity().orElseThrow(), "Keep the final independent Computer identity");
        helper.assertValueEqual(saved.origin().endpoint(), "io", "Bind the explicitly selected public alias");
        helper.assertValueEqual(saved.peer().position(), f.owner().getBlockPos(), "Actual external Computer is the peer");
        helper.assertTrue(store.view(f.cluster().b().communicationIdentity().orElseThrow()).entries().isEmpty(), "Do not populate the member namespace");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeCaller, "Do not execute the caller");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeMember, "Do not execute or buffer on behalf of the member");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void twoPublicAliasesOfTheSameHardwarePinKeepScopedViewsButOneControllerNamespace(GameTestHelper helper) {
        var f = fixture(helper); var controller = f.cluster().controller(); var player = f.cluster().player();
        var original = controller.routingState().orElseThrow().configuration(); var mapping = original.publicPins().get("io");
        var config = new ClusterConfiguration(original.members(), original.ports(), original.connections(), original.wires(), original.signals(),
            Map.of("io", mapping, "backup", mapping));
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, player, config);
        var first = XBusWiringService.open(controller, "io", player, () -> true);
        var second = XBusWiringService.open(controller, "backup", player, () -> true);
        var published = first.read(1); first.add(2, 1, "primary_wire", "x1", callerPin(published));
        var secondView = second.read(1);
        helper.assertTrue(WiringStatusView.decode(secondView).entries().isEmpty(), "A public alias view does not display another alias's declaration");
        second.add(2, 1, "backup_wire", "x1", callerPin(secondView));
        var store = XBusWiringSavedData.get(helper.getLevel()); var all = store.view(controller.controllerId());
        helper.assertValueEqual(all.entries().size(), 2, "Both aliases use the existing controller-wide store");
        helper.assertValueEqual(all.revision(), 2L, "Both aliases share one CAS namespace");
        helper.assertValueEqual(WiringStatusView.decode(first.read(3)).entries().getFirst().name(), "primary_wire", "Primary scope remains exact");
        helper.assertValueEqual(WiringStatusView.decode(second.read(3)).entries().getFirst().name(), "backup_wire", "Backup scope remains exact");
        boolean rejected = false;
        try { second.remove(4, 3, "primary_wire"); } catch (PeerDiscovery.DiscoveryException expected) { rejected = true; }
        helper.assertTrue(rejected, "A name guessed from another public alias cannot be deleted in this view");
        helper.assertValueEqual(store.view(controller.controllerId()), all, "Out-of-scope removal does not publish");
        second.read(5); second.remove(6, 5, "backup_wire");
        boolean stale = false;
        try { first.remove(4, 3, "primary_wire"); } catch (IllegalArgumentException expected) { stale = true; }
        helper.assertTrue(stale, "Another alias update invalidates the old device-wide version");
        first.read(5); first.remove(6, 5, "primary_wire");
        helper.assertTrue(store.view(controller.controllerId()).entries().isEmpty(), "Each declaration can be explicitly removed in its own view");
        helper.succeed();
    }

    private static int callerPin(DataValue.RecordValue status) {
        var peers = WiringStatusView.decode(status).peers();
        for (int i = 0; i < peers.size(); i++) if (peers.get(i).endpoint().equals("caller") && peers.get(i).pin().equals("x0")) return i;
        throw new IllegalArgumentException("The fixture's real caller pin was not discovered");
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void controllerSourceRemappingDoesNotRebindOldSelectionsOrSavedDeclarations(GameTestHelper helper) {
        var f = fixture(helper); var controller = f.cluster().controller(); var player = f.cluster().player();
        var session = XBusWiringService.open(controller, "io", player, () -> true);
        var offered = session.read(1); int peer = callerPin(offered); session.add(2, 1, "original", "x1", peer);
        var store = XBusWiringSavedData.get(helper.getLevel()); var before = store.view(controller.controllerId());
        var config = controller.routingState().orElseThrow().configuration();
        var remapped = new ClusterConfiguration(config.members(), config.ports(), config.connections(), config.wires(), config.signals(),
            Map.of("io", new XBusPublicPin(new XBusWire.Pin("A", "left", "x1"), true)));
        var caller = f.caller().flowSnapshot(); var member = f.cluster().b().flowSnapshot();
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, player, remapped);
        boolean refused = false;
        try { session.add(3, 2, "stale", "x1", peer); } catch (PeerDiscovery.DiscoveryException expected) { refused = true; }
        helper.assertTrue(refused, "Old local source choice cannot follow its public name to another member");
        var refreshed = WiringStatusView.decode(session.read(4));
        helper.assertValueEqual(refreshed.entries().getFirst().availability(), "identity_changed", "Read keeps the old member identity");
        helper.assertValueEqual(store.view(controller.controllerId()), before, "Refreshing and refusal never rebind the declaration");
        session.remove(5, 4, "original");
        helper.assertTrue(store.view(controller.controllerId()).entries().isEmpty(), "Explicit old metadata removal remains possible");
        helper.assertValueEqual(f.caller().flowSnapshot(), caller, "No caller execution");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), member, "No member execution");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void controllerSourceCloseBrokenShellAndPhysicalMenuLossFailWithoutImplicitConnections(GameTestHelper helper) {
        var f = fixture(helper); var controller = f.cluster().controller(); var player = f.cluster().player();
        var active = new boolean[] {true}; var session = XBusWiringService.open(controller, "io", player, () -> active[0]);
        var published = session.read(1); int peer = callerPin(published); session.add(2, 1, "original", "x1", peer);
        var store = XBusWiringSavedData.get(helper.getLevel()); var before = store.view(controller.controllerId());
        var config = controller.routingState().orElseThrow().configuration();
        var closed = new ClusterConfiguration(config.members(), config.ports(), config.connections(), config.wires(), config.signals(),
            Map.of("io", new XBusPublicPin(config.publicPins().get("io").target(), false)));
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, player, closed);
        boolean refused = false;
        try { session.add(3, 2, "closed", "x1", peer); } catch (PeerDiscovery.DiscoveryException expected) { refused = true; }
        helper.assertTrue(refused, "Closing the public source revokes a frozen selection");
        var view = WiringStatusView.decode(session.read(4));
        helper.assertValueEqual(view.discovery(), "local_closed", "Explicit closed source diagnosis");
        helper.assertTrue(view.localPins().isEmpty(), "A closed alias cannot offer a member pin");
        helper.assertValueEqual(store.view(controller.controllerId()), before, "Close and refresh do not drop saved metadata");
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, player, config);
        session.read(5); var shell = controller.getBlockPos().above(); var shellState = helper.getLevel().getBlockState(shell);
        helper.getLevel().setBlockAndUpdate(shell, Blocks.AIR.defaultBlockState());
        refused = false;
        try { session.add(6, 5, "broken", "x1", peer); } catch (PeerDiscovery.DiscoveryException expected) { refused = true; }
        helper.assertTrue(refused, "Broken shell revokes the public source");
        var broken = WiringStatusView.decode(session.read(7));
        helper.assertTrue(!broken.discovery().equals("ready") && broken.localPins().isEmpty(), "Broken shell is not an empty ready source");
        helper.assertValueEqual(broken.entries().size(), 1, "Original metadata stays inspectable");
        session.remove(8, 7, "original");
        helper.getLevel().setBlockAndUpdate(shell, shellState); session.read(9); active[0] = false;
        refused = false;
        try { session.add(10, 9, "closed_menu", "x1", peer); } catch (PeerDiscovery.DiscoveryException expected) { refused = true; }
        helper.assertTrue(refused, "Current physical menu must still be valid");
        active[0] = true; session.read(11); player.setPos(controller.getBlockPos().getX() + 20, player.getY(), player.getZ());
        refused = false;
        try { session.add(12, 11, "too_far", "x1", peer); } catch (PeerDiscovery.DiscoveryException expected) { refused = true; }
        helper.assertTrue(refused, "Controller reach remains required");
        helper.assertTrue(store.view(controller.controllerId()).entries().isEmpty(), "No rejected operation recreates a connection");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void closingAndReopeningTheSamePublicSourceDoesNotReviveItsOldSelection(GameTestHelper helper) {
        var f = fixture(helper); var controller = f.cluster().controller(); var player = f.cluster().player();
        var session = XBusWiringService.open(controller, "io", player, () -> true);
        var offered = session.read(1); int peer = callerPin(offered);
        var config = controller.routingState().orElseThrow().configuration();
        var closed = new ClusterConfiguration(config.members(), config.ports(), config.connections(), config.wires(), config.signals(),
            Map.of("io", new XBusPublicPin(config.publicPins().get("io").target(), false)));
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, player, closed);
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, player, config);
        boolean refused = false;
        try { session.add(2, 1, "old", "x1", peer); } catch (PeerDiscovery.DiscoveryException expected) { refused = true; }
        helper.assertTrue(refused, "Reopening the identical public pin must not revive an earlier configuration view");
        var store = XBusWiringSavedData.get(helper.getLevel());
        helper.assertTrue(store.view(controller.controllerId()).entries().isEmpty(), "Rejected old view does not publish metadata");
        var fresh = session.read(3); session.add(4, 3, "fresh", "x1", callerPin(fresh));
        helper.assertValueEqual(store.view(controller.controllerId()).entries().getFirst().name(), "fresh", "Explicit refresh can use the reopened source");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void controllerSourceCapacityLossIsNotReportedAsAClosedPublicPin(GameTestHelper helper) {
        var f = fixture(helper); var controller = f.cluster().controller();
        var session = XBusWiringService.open(controller, "io", f.cluster().player(), () -> true);
        var cap = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.enclosureMaxComputers;
        int previous = cap.get();
        try {
            cap.set(1);
            var read = WiringStatusView.decode(session.read(1));
            helper.assertValueEqual(read.discovery(), "computer_limit", "Enabled pin is unavailable due to capacity, not closed by the player");
            helper.assertTrue(read.localPins().isEmpty(), "Over-capacity structure offers no active member pin");
        } finally { cap.set(previous); }
        helper.assertValueEqual(WiringStatusView.decode(session.read(2)).discovery(), "ready", "Restoring the existing configured limit restores availability");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void actualComputerMenuPublishesExplicitWiringAndReopeningCanDeleteWithoutExchanging(GameTestHelper helper) {
        var f = fixture(helper); var player = f.cluster().player(); var pos = f.owner().getBlockPos();
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        var provider = ComputerProgramMenu.provider(f.owner());
        player.containerMenu = provider.createMenu(7, player.getInventory(), player);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        UUID nonce; try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        long revision = f.caller().programRevision(); var archive = f.caller().hardware().limits().archive();
        var beforeA = f.caller().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot();
        new ProgramControlRequest(7, nonce, 1, revision, ComputerControlSession.Action.READ_WIRING, new byte[0]).handle(player);
        var add = new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(1), "name", new DataValue.TextValue("player_wire"),
            "local_pin", new DataValue.TextValue("x0"), "peer", new DataValue.IntegerValue(0)));
        new ProgramControlRequest(7, nonce, 2, revision, ComputerControlSession.Action.ADD_WIRING, ProgramEditorWire.encodeValue(add, archive)).handle(player);
        var store = XBusWiringSavedData.get(helper.getLevel()); var id = f.caller().communicationIdentity().orElseThrow();
        helper.assertValueEqual(store.view(id).entries().size(), 1, "Actual menu packets save the chosen server-published declaration");
        helper.assertValueEqual(store.view(id).entries().getFirst().name(), "player_wire", "Literal player connection name");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeA, "Wiring metadata cannot execute the sender");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "Wiring metadata cannot execute the member");
        var another = new LinkedHashMap<>(add.fields()); another.put("view", new DataValue.IntegerValue(2)); another.put("name", new DataValue.TextValue("not_published"));
        new ProgramControlRequest(7, nonce, 2, revision, ComputerControlSession.Action.ADD_WIRING,
            ProgramEditorWire.encodeValue(new DataValue.RecordValue(another), archive)).handle(player);
        helper.assertValueEqual(store.view(id).revision(), 1L, "Replayed outer intent cannot add a different named declaration");
        var forged = new LinkedHashMap<>(another); forged.put("position", new DataValue.TextValue("not an authority"));
        new ProgramControlRequest(7, nonce, 3, revision, ComputerControlSession.Action.ADD_WIRING,
            ProgramEditorWire.encodeValue(new DataValue.RecordValue(forged), archive)).handle(player);
        new ProgramControlRequest(7, nonce, 4, revision, ComputerControlSession.Action.ADD_WIRING,
            ProgramEditorWire.encodeValue(new DataValue.RecordValue(another), archive)).handle(player);
        helper.assertValueEqual(store.view(id).revision(), 1L, "Malformed fields are rejected and invalidate the previously usable selection");
        new ProgramControlRequest(7, nonce, 5, revision, ComputerControlSession.Action.READ_WIRING, new byte[0]).handle(player);
        new ProgramControlRequest(7, nonce, 6, revision, ComputerControlSession.Action.READ_ENDPOINT, new byte[0]).handle(player);
        another.put("view", new DataValue.IntegerValue(5));
        new ProgramControlRequest(7, nonce, 7, revision, ComputerControlSession.Action.ADD_WIRING,
            ProgramEditorWire.encodeValue(new DataValue.RecordValue(another), archive)).handle(player);
        helper.assertValueEqual(store.view(id).revision(), 1L, "Leaving wiring controls invalidates its frozen selection");
        new ProgramControlRequest(7, nonce, 8, revision, ComputerControlSession.Action.READ_WIRING, new byte[0]).handle(player);
        another.put("view", new DataValue.IntegerValue(8));
        new ProgramControlRequest(7, nonce, 9, revision + 1, ComputerControlSession.Action.ADD_WIRING,
            ProgramEditorWire.encodeValue(new DataValue.RecordValue(another), archive)).handle(player);
        new ProgramControlRequest(7, nonce, 10, revision, ComputerControlSession.Action.ADD_WIRING,
            ProgramEditorWire.encodeValue(new DataValue.RecordValue(another), archive)).handle(player);
        helper.assertValueEqual(store.view(id).revision(), 1L, "Rejected program revision consumes the old wiring publication too");
        helper.getLevel().setBlockAndUpdate(f.cluster().controller().getBlockPos().west(2), Blocks.AIR.defaultBlockState());
        var reopened = ComputerProgramMenu.provider(f.owner());
        player.containerMenu = reopened.createMenu(8, player.getInventory(), player);
        buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        try { reopened.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        new ProgramControlRequest(8, nonce, 1, revision, ComputerControlSession.Action.READ_WIRING, new byte[0]).handle(player);
        var remove = new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(1), "name", new DataValue.TextValue("player_wire")));
        new ProgramControlRequest(8, nonce, 2, revision, ComputerControlSession.Action.REMOVE_WIRING, ProgramEditorWire.encodeValue(remove, archive)).handle(player);
        helper.assertTrue(store.view(id).entries().isEmpty(), "New physical menu can refresh and remove the disconnected declaration");
        helper.assertValueEqual(store.view(id).revision(), 2L, "Exactly one add and one delete");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void wiringSelectionAndSavedDeclarationCannotFollowARemappedPassivePublicPin(GameTestHelper helper) {
        var f = fixture(helper); var player = f.cluster().player(); var controller = f.cluster().controller();
        var pos = f.owner().getBlockPos(); player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        var session = XBusWiringService.open(f.owner(), player, () -> true);
        var offered = (DataValue.RecordValue)session.read(1).field("wiring");
        var peers = ((DataValue.ListValue)offered.field("peer_pins")).values(); int selected = -1;
        for (int i = 0; i < peers.size(); i++) {
            var peer = (DataValue.RecordValue)peers.get(i);
            if (peer.field("endpoint").equals(new DataValue.TextValue("io")) && peer.field("pin").equals(new DataValue.TextValue("x1"))) selected = i;
        }
        helper.assertTrue(selected >= 0, "Real public member B.x1 offered");
        session.add(2, 1, "original", "x0", selected);
        var store = XBusWiringSavedData.get(helper.getLevel()); var id = f.caller().communicationIdentity().orElseThrow();
        var original = store.view(id); var config = controller.routingState().orElseThrow().configuration();
        var mappedToA = new ClusterConfiguration(config.members(), config.ports(), config.connections(), config.wires(), config.signals(),
            Map.of("io", new XBusPublicPin(new XBusWire.Pin("A", "left", "x1"), true)));
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, player, mappedToA);
        var a = f.caller().flowSnapshot(); var b = f.cluster().b().flowSnapshot();
        boolean rejected = false;
        try { session.add(3, 2, "stale_choice", "x0", selected); }
        catch (PeerDiscovery.DiscoveryException expected) { rejected = true; }
        helper.assertTrue(rejected, "Same gateway/name/pin now resolving to another Computer rejects the old choice");
        var read = (DataValue.RecordValue)session.read(4).field("wiring");
        var entry = (DataValue.RecordValue)((DataValue.ListValue)read.field("entries")).values().getFirst();
        helper.assertValueEqual(entry.field("availability"), new DataValue.TextValue("identity_changed"), "Saved B is diagnosed, never rebound to A");
        helper.assertValueEqual(store.view(id), original, "Rejected save and refresh preserve the old immutable declaration");
        helper.assertValueEqual(f.caller().flowSnapshot(), a, "No external sender progress");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), b, "No original member progress");
        session.remove(5, 4, "original");
        helper.assertTrue(store.view(id).entries().isEmpty(), "Owner can remove a stale identity declaration explicitly");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void authorizedWiringServiceUsesFrozenPublicPinChoicesAndCanDeleteAnUnreachableDeclaration(GameTestHelper helper) {
        var f = fixture(helper); var player = f.cluster().player(); var pos = f.owner().getBlockPos();
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        var active = new boolean[] {true};
        var session = XBusWiringService.open(f.owner(), player, () -> active[0]);
        var beforeA = f.caller().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot();
        var read = (DataValue.RecordValue)session.read(1).field("wiring");
        var peers = ((DataValue.ListValue)read.field("peer_pins")).values(); int option = -1;
        for (int index = 0; index < peers.size(); index++) {
            var peer = (DataValue.RecordValue)peers.get(index);
            if (peer.field("endpoint").equals(new DataValue.TextValue("io")) && peer.field("pin").equals(new DataValue.TextValue("x1"))) option = index;
        }
        helper.assertTrue(option >= 0, "Actual cable discovery offers the explicitly exposed passive pin");
        active[0] = false; boolean rejected = false;
        try { session.add(2, 1, "wire", "x0", option); }
        catch (PeerDiscovery.DiscoveryException expected) { rejected = true; }
        helper.assertTrue(rejected, "A stale physical menu grant cannot save the observed selection");
        var store = XBusWiringSavedData.get(helper.getLevel()); var id = f.caller().communicationIdentity().orElseThrow();
        helper.assertTrue(store.view(id).entries().isEmpty(), "Rejected configuration does not publish");
        active[0] = true; session.read(3); session.add(4, 3, "wire", "x0", option);
        var saved = store.view(id).entries().getFirst();
        helper.assertValueEqual(saved.peer().device(), f.cluster().controller().controllerId(), "Bind the actual gateway identity");
        helper.assertValueEqual(saved.peer().computer(), f.cluster().b().communicationIdentity().orElseThrow(), "Also bind the final member identity");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeA, "Discovery and save never advance the caller");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "Passive gateway never accepts or buffers the data");
        helper.getLevel().setBlockAndUpdate(f.cluster().controller().getBlockPos().west(2), Blocks.AIR.defaultBlockState());
        var broken = (DataValue.RecordValue)session.read(5).field("wiring");
        var entry = (DataValue.RecordValue)((DataValue.ListValue)broken.field("entries")).values().getFirst();
        helper.assertValueEqual(entry.field("availability"), new DataValue.TextValue("unreachable"), "Stored metadata diagnoses the broken actual cable");
        session.remove(6, 5, "wire");
        helper.assertTrue(store.view(id).entries().isEmpty(), "Unavailable peer does not prevent explicit metadata deletion");
        helper.assertTrue(f.caller().flowSnapshot().orElseThrow().receipts().isEmpty(), "No implicit XBus exchange");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void declarationStoreBelongsToTheDimensionAndSavesFixedCableEndpointsWithoutExchanging(GameTestHelper helper) {
        var f = fixture(helper); var level = helper.getLevel(); var controller = f.cluster().controller();
        var left = XBusCableRoute.bind(controller, Set.of(Direction.WEST), "caller", "x0").pin().orElseThrow().target();
        var right = XBusCableRoute.bind(f.owner(), EAST, "io", "x1").pin().orElseThrow().target();
        var beforeA = f.caller().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot();
        var store = XBusWiringSavedData.get(level);
        helper.assertTrue(store == XBusWiringSavedData.get(level), "One authoritative store in this level");
        var initial = store.view(left.device());
        var added = store.add(initial, "test_wire", left, right, 8, ignored -> {});
        var nether = Objects.requireNonNull(level.getServer().getLevel(net.minecraft.world.level.Level.NETHER));
        helper.assertTrue(XBusWiringSavedData.get(nether) != store, "Do not put every dimension in the overworld store");
        helper.assertTrue(XBusWiringSavedData.get(nether).view(left.device()).entries().isEmpty(), "No cross-dimension namespace leakage");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeA, "Saving declarations does not execute the source");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "No receiver or controller buffering");
        helper.assertValueEqual(level.dimension(), net.minecraft.world.level.Level.OVERWORLD, "Test reads the actual overworld data directory");
        level.getDataStorage().save();
        // NeoForge saves asynchronously; read the completed disk image, never an earlier file or a timing guess.
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var file = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("data/create_biotech_xbus_wiring.dat");
        XBusWiringSavedData restored;
        try (var input = java.nio.file.Files.newInputStream(file)) {
            var saved = net.minecraft.nbt.NbtIo.readCompressed(input, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            restored = XBusWiringSavedData.load(saved.getCompound("data"), level.registryAccess());
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        helper.assertValueEqual(restored.view(left.device()), added, "Actual SavedData disk image keeps both fixed identities and version");
        helper.getLevel().setBlockAndUpdate(controller.getBlockPos().west(2), Blocks.AIR.defaultBlockState());
        helper.assertValueEqual(XBusCableRoute.resolve(f.owner(), EAST, restored.view(left.device()).entries().getFirst().peer()).code(),
            "unreachable", "Saved declarations do not override a missing actual cable");
        helper.assertValueEqual(store.view(left.device()), added, "Disconnection does not erase or retarget metadata");
        var deleted = store.remove(added, "test_wire", ignored -> {});
        helper.assertTrue(deleted.entries().isEmpty() && deleted.revision() == 2, "Explicit deletion retains the used configuration version");
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(f.caller().flowSnapshot().orElseThrow().receipts().isEmpty(), "There is no automatic send scheduler in this slice");
            helper.assertTrue(f.cluster().b().flowSnapshot().orElseThrow().receipts().isEmpty(), "No read is accepted by storing a declaration");
            helper.assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty(), "The passive controller still has no buffered payload");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void completeCableCaptureRetainsMissingParticipantAndRepairsOldReadThroughPassiveGateway(GameTestHelper helper) {
        var f = fixture(helper); var level = helper.getLevel(); var controller = f.cluster().controller();
        var config = controller.routingState().orElseThrow().configuration();
        var aliases = new LinkedHashMap<>(config.publicPins()); aliases.put("io_alias", aliases.get("io"));
        var aliased = new ClusterConfiguration(config.members(), config.ports(), config.connections(), config.wires(), config.signals(), aliases);
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, f.cluster().player(), aliased);
        var junction = f.owner().getBlockPos().east();
        level.setBlockAndUpdate(junction.north(), CBBlocks.DATA_CABLE.get().defaultBlockState());
        var thirdBlock = BiocomputingWorldGameTests.computer(helper, junction.north(2));
        var third = BiocomputingWorldGameTests.host(thirdBlock);
        third.installFlow(new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))), BiocomputingFlowFixture.inputs(99));
        third.configureEndpoint(new EndpointConfiguration("third", true)); third.setPaused(false);
        helper.runAfterDelay(4, () -> {
            var a = new XBusCableNetwork.End(XBusCableRoute.bind(controller, Set.of(Direction.WEST), "caller", "x0").pin().orElseThrow().target(), EAST);
            var b = new XBusCableNetwork.End(XBusCableRoute.bind(f.owner(), EAST, "io", "x1").pin().orElseThrow().target(), Set.of(Direction.WEST));
            var alias = new XBusCableNetwork.End(XBusCableRoute.bind(f.owner(), EAST, "io_alias", "x1").pin().orElseThrow().target(), Set.of(Direction.WEST));
            var c = new XBusCableNetwork.End(XBusCableRoute.bind(f.owner(), EAST, "third", "x0").pin().orElseThrow().target(), Set.of(Direction.SOUTH));
            var links = List.of(new XBusCableNetwork.Link(a, b), new XBusCableNetwork.Link(alias, c));
            var ap = new XBusNetwork.Pin(a.target().computer(), a.target().pin()); var bp = new XBusNetwork.Pin(b.target().computer(), b.target().pin());
            var cp = new XBusNetwork.Pin(c.target().computer(), c.target().pin());
            var readBlock = f.cluster().b().blockEntity; var oldRead = readBlock.saveWithFullMetadata(level.registryAccess());
            var before = f.caller().flowSnapshot(); var first = XBusCableNetwork.capture(level, links);
            helper.assertValueEqual(first.code(), "ready", "All explicit edges resolve through actual cable and gateway");
            helper.assertValueEqual(f.caller().flowSnapshot(), before, "Capture alone does not execute or send");
            var closed = new LinkedHashMap<>(aliases); closed.put("io_alias", new XBusPublicPin(aliases.get("io_alias").target(), false));
            BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, f.cluster().player(),
                new ClusterConfiguration(config.members(), config.ports(), config.connections(), config.wires(), config.signals(), closed));
            var revoked = XBusCableNetwork.capture(level, links);
            helper.assertValueEqual(revoked.code(), "closed", "Closing one public alias cannot be hidden by the other alias of the same physical pin");
            helper.assertTrue(revoked.network().isEmpty(), "No partial group through the still-open alias");
            helper.assertValueEqual(f.caller().flowSnapshot(), before, "Alias management never consumes the pending value");
            BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, f.cluster().player(), aliased);
            var connected = XBusCableNetwork.capture(level, links);
            helper.assertValueEqual(connected.network().orElseThrow().exchange(ap, bp), XBusExchange.Status.EXCHANGED, "Original writer transfers 12");
            var savedWriter = f.owner().saveWithFullMetadata(level.registryAccess()); var writerState = f.owner().getBlockState();
            level.removeBlockEntity(readBlock.getBlockPos());
            var restoredReaderBlock = (SmartBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(readBlock.getBlockPos(), readBlock.getBlockState(), oldRead, level.registryAccess()));
            level.setBlockEntity(restoredReaderBlock);
            level.removeBlockEntity(f.owner().getBlockPos());
            var unavailable = XBusCableNetwork.capture(level, links);
            helper.assertTrue(unavailable.network().isEmpty(), "Missing A must not silently turn the declaration into B-C");
            helper.assertTrue(unavailable.diagnostics().stream().anyMatch(d -> !d.code().equals("ready")), "Physical missing route is diagnosed");
            helper.assertValueEqual(connected.network().orElseThrow().exchange(cp, bp), XBusExchange.Status.UNAVAILABLE, "Detached old authority is unusable");
            var restoredWriterBlock = (SmartBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(f.owner().getBlockPos(), writerState, savedWriter, level.registryAccess()));
            level.setBlockEntity(restoredWriterBlock);
            var writer = restoredWriterBlock.getBehaviour(ComputerBehaviour.TYPE); var reader = restoredReaderBlock.getBehaviour(ComputerBehaviour.TYPE);
            var recovered = XBusCableNetwork.capture(level, links);
            helper.assertValueEqual(recovered.code(), "recovery_required", "Fixed same-identity reload finds original history through passive mapping");
            var beforeWriter = writer.flowSnapshot(); var beforeThird = third.flowSnapshot();
            helper.assertValueEqual(recovered.network().orElseThrow().exchange(cp, bp), XBusExchange.Status.RECOVERY_REQUIRED, "Competing 99 cannot consume the old read");
            helper.assertValueEqual(recovered.network().orElseThrow().exchange(ap, bp), XBusExchange.Status.EXCHANGED, "Original 12 repairs the member read");
            helper.assertValueEqual(reader.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Recovered named record, not 99");
            helper.assertValueEqual(writer.flowSnapshot(), beforeWriter, "No replay on advanced writer");
            helper.assertValueEqual(third.flowSnapshot(), beforeThird, "Competing writer still waits independently");
            helper.assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty(), "No controller buffering");
            helper.assertTrue(controller.routingState().orElseThrow().configuration().wires().isEmpty(), "Capture does not invent internal wiring");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void boundPhysicalRouteRequiresLiveCableAndReturnsThroughTheSamePassivePin(GameTestHelper helper) {
        var f = fixture(helper); var controller = f.cluster().controller(); var receiver = f.cluster().b();
        var bound = XBusCableRoute.bind(f.owner(), EAST, "io", "x1");
        helper.assertValueEqual(bound.code(), "ready", "Actual loaded cable resolves the passive public pin");
        var route = bound.pin().orElseThrow();
        helper.assertValueEqual(route.target().device(), controller.controllerId(), "Bind the physical gateway, not just its coordinates");
        helper.assertValueEqual(route.target().computer(), receiver.communicationIdentity().orElseThrow(), "Also bind the final Computer, not an alias owner");
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Binding alone does not send");
            helper.assertValueEqual(receiver.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_READ, "Controller never auto-accepts");
            var local = access(f.caller());
            helper.getLevel().setBlockAndUpdate(controller.getBlockPos().west(2), Blocks.AIR.defaultBlockState());
            helper.assertTrue(helper.getLevel().getBlockState(controller.getBlockPos().west(2)).isAir(), "Fixture removed the actual middle cable");
            helper.assertValueEqual(local.exchange(route.access()), XBusExchange.Status.UNAVAILABLE, "Even captured access rejects a broken cable");
            helper.assertValueEqual(XBusCableRoute.resolve(f.owner(), EAST, route.target()).code(), "unreachable", "Physical disconnection is not an empty payload");
            helper.assertTrue(f.caller().flowSnapshot().orElseThrow().receipts().isEmpty(), "Broken cable consumes nothing");
            helper.getLevel().setBlockAndUpdate(controller.getBlockPos().west(2), CBBlocks.DATA_CABLE.get().defaultBlockState());
            helper.assertValueEqual(local.exchange(XBusCableRoute.resolve(f.owner(), EAST, route.target()).pin().orElseThrow().access()),
                XBusExchange.Status.EXCHANGED, "Explicit physical coordination now transfers the record");
            helper.runAfterDelay(3, () -> {
                helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_READ, "Caller reaches actual return read");
                helper.assertValueEqual(receiver.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Return stays pending without explicit coordination");
                var reverse = XBusCableRoute.resolve(f.owner(), EAST, route.target());
                helper.assertValueEqual(reverse.pin().orElseThrow().access().exchange(access(f.caller())), XBusExchange.Status.EXCHANGED, "Same passive pin sends in reverse");
                helper.assertValueEqual(f.caller().flowSnapshot().orElseThrow().state().locals().field("answer"), BiocomputingFlowFixture.inputs(12).field("packet"), "Named record arrives intact");
                helper.assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty(), "No gateway inbox was involved");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void controllerReloadRequiresFreshAccessAndANewControllerCannotInheritTheFixedTarget(GameTestHelper helper) {
        var f = fixture(helper); var level = helper.getLevel(); var controller = f.cluster().controller(); var pos = controller.getBlockPos();
        var original = XBusCableRoute.bind(f.owner(), EAST, "io", "x1").pin().orElseThrow(); var local = access(f.caller());
        var blockState = level.getBlockState(pos); var saved = controller.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        var restored = (ClusterControllerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, blockState, saved, level.registryAccess()));
        level.setBlockEntity(restored);
        helper.assertValueEqual(local.exchange(original.access()), XBusExchange.Status.UNAVAILABLE, "Detached gateway authority does not revive");
        helper.assertValueEqual(XBusCableRoute.resolve(f.owner(), EAST, original.target()).code(), "ready", "Reload keeps the fixed physical and final member identities");
        helper.assertValueEqual(XBusCableRoute.resolve(f.owner(), Set.of(Direction.WEST), original.target()).code(), "unreachable", "Unconnected source faces cannot borrow another path");
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); level.setBlockAndUpdate(pos, blockState);
        helper.assertValueEqual(XBusCableRoute.resolve(f.owner(), EAST, original.target()).code(), "identity_changed", "New controller at the same position cannot inherit the binding even before configuration");
        helper.assertTrue(f.caller().flowSnapshot().orElseThrow().receipts().isEmpty(), "No waiting data was consumed during reload or replacement");
        level.removeBlockEntity(f.owner().getBlockPos());
        helper.assertValueEqual(XBusCableRoute.resolve(f.owner(), EAST, original.target()).code(), "unavailable", "Removed source instances cannot inspect routes");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void lateHardwareLossCannotCommitThroughAnOtherwiseValidPhysicalRoute(GameTestHelper helper) {
        var f = fixture(helper);
        var route = XBusCableRoute.bind(f.owner(), EAST, "io", "x1").pin().orElseThrow();
        var beforeA = f.caller().flowSnapshot(); var beforeB = f.cluster().b().flowSnapshot();
        var guarded = route.access().guarded(() -> {
            // Deliberately suppress neighbour repair: the loaded lower instance and its flow remain unchanged.
            helper.getLevel().setBlock(f.owner().getBlockPos().above(), Blocks.AIR.defaultBlockState(), 2);
            return Optional.empty();
        });
        helper.assertValueEqual(access(f.caller()).exchange(guarded), XBusExchange.Status.UNAVAILABLE,
            "Final actual hardware proof notices the missing upper half after the local guard already ran");
        helper.assertValueEqual(f.caller().flowSnapshot(), beforeA, "Do not commit the writer");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), beforeB, "Do not commit the member reader");
        helper.succeed();
    }

    private static XBusAccess access(ComputerBehaviour caller) {
        return caller.xbusAccess("caller", caller.xbusPort("caller", "x0").orElseThrow()).orElseThrow();
    }
    static Fixture fixture(GameTestHelper helper) {
        return fixture(helper, net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
    }
    private static Fixture fixture(GameTestHelper helper, net.minecraft.world.entity.npc.VillagerProfession member, int memberLevel) {
        var f = BiocomputingClusterXBusGameTests.fixture(helper, false, true,
            net.minecraft.world.entity.npc.VillagerProfession.NONE, 1, member, memberLevel);
        var pos = f.controller().getBlockPos();
        var packet = BiocomputingFlowFixture.PACKET; var empty = new DataValue.RecordValue(Map.of());
        f.b().installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", packet), new FlowProgram.Write("reply", "x1", "packet"))), empty);
        var owner = BiocomputingWorldGameTests.computer(helper, pos.west(3)); var caller = BiocomputingWorldGameTests.host(owner);
        caller.installFlow(new FlowProgram(Map.of("packet", packet), List.of(new FlowProgram.Write("send", "x0", "packet"), new FlowProgram.Read("reply", "x0", "answer", packet))), BiocomputingFlowFixture.inputs(12));
        caller.configureEndpoint(new EndpointConfiguration("caller", true));
        var config = new ClusterConfiguration(f.config().members(), Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of("io", new XBusPublicPin(new XBusWire.Pin("B", "right", "x1"), true)));
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, f.controller(), f.player(), config);
        caller.step(); f.b().step(); caller.setPaused(false); f.b().setPaused(false);
        helper.getLevel().setBlockAndUpdate(pos.west(), CBBlocks.DATA_CABLE.get().defaultBlockState());
        helper.getLevel().setBlockAndUpdate(pos.west(2), CBBlocks.DATA_CABLE.get().defaultBlockState());
        return new Fixture(f, owner, caller);
    }
}
