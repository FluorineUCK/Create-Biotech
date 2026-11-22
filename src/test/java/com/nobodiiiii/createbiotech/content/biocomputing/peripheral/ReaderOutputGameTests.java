package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Real shared XBus topology and hardware, never Java-issued sample/set commands or client UI evidence. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class ReaderOutputGameTests {
    @GameTest(template = "empty", timeoutTicks = 14000)
    public static void publicApplicationSamplesInvertsAndOutputsWithoutHiddenPolling(GameTestHelper h) {
        var level = h.getLevel(); var min = h.absolutePos(new BlockPos(1, 1, 1)); var max = min.offset(6, 3, 4);
        var controlPos = min.offset(6, 1, 2); var readerPos = controlPos.east(4); var outputPos = controlPos.east(2).south(2);
        var computers = new ArrayList<ComputerBehaviour>(); var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
        for (int i = 0; i < 5; i++) {
            String role = String.valueOf((char)('a' + i));
            var computer = ReaderDriverGameTests.computer(h, min.offset(1 + 2 * (i % 3), 1, i < 3 ? 1 : 3), VillagerProfession.CLERIC);
            var document = ReaderDriverGameTests.loadEntry("reader_output_" + role);
            if (i == 0) {
                var draft = new FlowDraft(document.flow().orElseThrow()); draft.replaceInitial("target", address(readerPos));
                var workspace = new ProgramWorkspace(document); workspace.setFlow(Optional.of(draft.snapshot())); document = workspace.snapshot();
            } else if (i == 4) document = outputTarget(document, outputPos);
            computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET); computer.configureEndpoint(new EndpointConfiguration(role, true));
            members.put(role, new ClusterConfiguration.Member(computer.communicationIdentity().orElseThrow(), computer.blockEntity.getBlockPos())); computers.add(computer);
        }
        for (var p : BlockPos.betweenClosed(min, max))
            if (p.getX() == min.getX() || p.getX() == max.getX() || p.getY() == min.getY() || p.getY() == max.getY() || p.getZ() == min.getZ() || p.getZ() == max.getZ())
                level.setBlock(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
        level.setBlock(controlPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST), 3);
        var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controlPos);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "reader-output")); actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controlPos.east()));
        var wires = Map.of("ab", wire("a", "x0", "b", "x0"), "eb", wire("e", "x0", "b", "x0"),
            "bc", wire("b", "x1", "c", "x0"), "ad", wire("a", "x1", "d", "x0"), "de", wire("d", "x1", "e", "x1"));
        h.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), wires), actor), "configured", "Shared A/B/E XBus is explicitly wired");
        var b = computers.get(1); var grant = SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, b.blockEntity), actor, "s0", "control");
        b.configureSerialGateway("s0", Optional.of(grant)); b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
        for (int i = 1; i < 4; i++) level.setBlock(controlPos.east(i), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(outputPos.north(), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
        level.setBlock(readerPos, CBBlocks.BLOCK_READER.get().defaultBlockState().setValue(BlockReaderBlock.FACING, Direction.EAST), 3);
        level.setBlock(readerPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        level.setBlock(outputPos, CBBlocks.REDSTONE_OUTPUT.get().defaultBlockState().setValue(RedstoneOutputBlock.FACING, Direction.SOUTH), 3);
        var reader = ((BlockReaderBlockEntity)level.getBlockEntity(readerPos)).getBehaviour(ReaderSerialBehaviour.TYPE);
        var output = (RedstoneOutputBlockEntity)level.getBlockEntity(outputPos); var control = output.getBehaviour(RedstoneOutputSerialBehaviour.TYPE);
        var installed = computers.stream().map(c -> c.document().orElseThrow()).toList(); var hardware = computers.stream().map(ComputerBehaviour::hardware).toList();
        h.runAfterDelay(4, () -> {
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(readerPos)); var sources = reader.discover(actor);
            h.assertValueEqual(sources.size(), 1, "Only B is externally exposed");
            h.assertValueEqual(reader.configure(actor, 0, Optional.of(sources.getFirst()), Set.of(RedstoneReadComponent.ID)), "configured", "Reader independent grant");
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(outputPos)); var outputSources = control.discover(actor);
            h.assertValueEqual(outputSources.size(), 1, "Output uses the same explicitly exposed source");
            h.assertValueEqual(control.configure(actor, 0, Optional.of(outputSources.getFirst())), "configured", "Output independent grant");
            h.runAfterDelay(4, () -> {
                startAndAwait(h, computers, List.of(2,1,3,4,0), false, () -> {
                    assertResult(h, computers, output, 0);
                    var first = computers.get(3).terminalStatus().field("output");
                    level.setBlock(readerPos.east(), Blocks.STONE.defaultBlockState(), 3);
                    h.runAfterDelay(80, () -> {
                        h.assertValueEqual(output.appliedLevel(), 0, "World changes do not silently resample");
                        h.assertValueEqual(computers.get(3).terminalStatus().field("output"), first, "Original data copy is stable");
                        startAndAwait(h, computers, List.of(3,4,0), true, () -> {
                            assertResult(h, computers, output, 15);
                            h.assertValueEqual(level.getSignal(outputPos, Direction.NORTH), 15, "Actual weak redstone, not only a reply field");
                            h.assertTrue(controller.enclosure().enclosure().isPresent(), "Outer enclosure with interior air remains valid");
                            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(readerPos));
                            h.assertValueEqual(reader.configure(actor, reader.revision(), reader.binding(), Set.of(BuiltinReadComponents.BLOCK)), "configured", "Revoke redstone component only");
                            h.runAfterDelay(4, () -> {
                                startAndAwait(h, computers, List.of(3,4,0), true, () -> {
                                    var calculation = (DataValue.RecordValue)computers.get(3).terminalStatus().field("output");
                                    h.assertValueEqual(calculation.field("ok"), new DataValue.BooleanValue(false), "Failure branches in player code");
                                    h.assertValueEqual(calculation.field("level"), DataValue.NullValue.INSTANCE, "No invented zero");
                                    h.assertValueEqual(((DataValue.RecordValue)calculation.field("sample")).field("code"), new DataValue.TextValue("component_denied"), "Original failure preserved");
                                    var response = (DataValue.RecordValue)computers.get(4).terminalStatus().field("output");
                                    h.assertValueEqual(response.field("reply"), new DataValue.TextValue("invalid_sample"), "Output stage explicitly finishes without setting");
                                    h.assertValueEqual(output.appliedLevel(), 15, "Failed input leaves the applied latch intact");
                                    for (int i = 0; i < 5; i++) {
                                        h.assertValueEqual(computers.get(i).hardware(), hardware.get(i), "No capacity pooling or upgrade");
                                        h.assertValueEqual(computers.get(i).document().orElseThrow(), installed.get(i), "No hidden program replacement");
                                    }
                                    h.assertTrue(level.getBlockState(readerPos.east()).is(Blocks.STONE), "No write-back to the read source"); h.succeed();
                                });
                            });
                        });
                    });
                });
            });
        });
    }
    private static void assertResult(GameTestHelper h, List<ComputerBehaviour> computers, RedstoneOutputBlockEntity output, int signal) {
        var computation = (DataValue.RecordValue)computers.get(3).terminalStatus().field("output");
        h.assertValueEqual(computation.field("ok"), new DataValue.BooleanValue(true), "Typed computation succeeded");
        h.assertValueEqual(computation.field("level"), new DataValue.IntegerValue(signal), "Public arithmetic inverted the fresh signal");
        var terminal = (DataValue.RecordValue)computers.get(4).terminalStatus().field("output");
        h.assertValueEqual(((DataValue.RecordValue)terminal.field("reply")).field("code"), new DataValue.TextValue("applied"), "Business completion, not send completion");
        h.assertValueEqual(output.appliedLevel(), signal, "Actual output latch");
    }
    private static void startAndAwait(GameTestHelper h, List<ComputerBehaviour> computers, List<Integer> order,
            boolean reset, Runnable finish) {
        if (order.isEmpty()) { await(h, computers, finish); return; }
        var computer = computers.get(order.getFirst());
        if (reset) computer.reset();
        computer.setPaused(false);
        // Normal player-paced startup, not unpausing every owner before the first world tick.
        h.runAfterDelay(80, () -> {
            for (var owner : computers) {
                h.assertTrue(owner.diagnostic().isEmpty(), "Staggered startup keeps owner available");
                h.assertTrue(owner.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.FAILED,
                    "Waiting for a later start must not touch a paused XBus peer");
            }
            startAndAwait(h, computers, order.subList(1, order.size()), reset, finish);
        });
    }
    private static void await(GameTestHelper h, List<ComputerBehaviour> computers, Runnable finish) {
        h.runAfterDelay(5, () -> {
            for (int i = 0; i < computers.size(); i++) {
                var computer = computers.get(i); h.assertTrue(computer.diagnostic().isEmpty(), "Owner " + i + ": " + computer.diagnostic());
                var state = computer.flowSnapshot().orElseThrow().state(); h.assertTrue(state.status() != FlowRuntime.Status.FAILED, "Owner " + i + ": " + state.diagnostic());
            }
            if (List.of(0,3,4).stream().allMatch(i -> computers.get(i).flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE)) finish.run();
            else await(h, computers, finish);
        });
    }
    private static XBusWire wire(String a, String ap, String b, String bp) { return new XBusWire(new XBusWire.Pin(a,a,ap), new XBusWire.Pin(b,b,bp), true); }
    private static DataValue.RecordValue address(BlockPos pos) { return new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(pos.getX()), "y", new DataValue.IntegerValue(pos.getY()), "z", new DataValue.IntegerValue(pos.getZ()))); }
    private static ProgramDocument outputTarget(ProgramDocument original, BlockPos pos) {
        var body = original.flow().orElseThrow(); var old = body.program(original.archive()); var steps = new ArrayList<FlowProgram.Step>();
        for (var step : old.steps()) {
            if (step instanceof FlowProgram.Procedure p) {
                var source = p.assembly().orElseThrow();
                var text = source.text().replace("CONST target {\"x\":0,\"y\":0,\"z\":0}", "CONST target " + EditorValueFormat.format(address(pos)));
                if (source.text().equals(text)) throw new IllegalStateException("Review changed public target configuration");
                steps.add(new FlowProgram.Procedure(p.sourceId(), p.key(), p.inputs(), p.outputs(), p.returns(), p.rules(), Optional.of(new AssemblySource(source.version(), text))));
            } else steps.add(step);
        }
        var workspace = new ProgramWorkspace(original); workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(old.inputs(), steps).withAssembly(), body.initial()))); return workspace.snapshot();
    }
}
