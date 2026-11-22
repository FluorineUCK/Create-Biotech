package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderSamplingProtocol;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Public documents on real ISA owners; finite serial and rendezvous, with pure protocol device fixtures. */
class ReaderOutputExecutionTest {
    @ParameterizedTest @ValueSource(strings = {"normal", "restore", "denied", "wrong_type", "range", "component_error", "error_with_value", "output_denied", "stale_output"})
    void freshSampleIsCheckedComputedAndAppliedOnlyThroughTheAuthorizedProtocol(String mode) throws Exception {
        var config = NativeLibraryOwnersTest.CONFIG;
        var limits = new ArrayList<ComputerHostLimits>(); var owners = new ArrayList<ComputerBlockHost>();
        var wireB = NativeLibraryOwnersTest.device(NativeLibraryOwnersTest.limits("cleric", 5));
        var wireR = new SerialDevice(() -> config.readerSerial(false), () -> true, () -> config.serialCodec(false), () -> {});
        var wireO = NativeLibraryOwnersTest.device(NativeLibraryOwnersTest.limits("librarian", 5));
        wireB.configure("s0", Direction.EAST, true); wireR.configure("s0", Direction.WEST, true); wireO.configure("s0", Direction.NORTH, true);
        var readerAddress = record("x", 2, "y", 0, "z", 0); var outputAddress = record("x", 1, "y", 0, "z", 1);
        for (int i = 0; i < 5; i++) {
            var l = NativeLibraryOwnersTest.limits("cleric", 5); limits.add(l);
            var document = ReaderOutputDocumentTest.load(String.valueOf((char)('a' + i)));
            if (i == 0) {
                var draft = new FlowDraft(document.flow().orElseThrow()); draft.replaceInitial("target", readerAddress);
                var workspace = new ProgramWorkspace(document); workspace.setFlow(Optional.of(draft.snapshot())); document = workspace.snapshot();
            } else if (i == 4) document = outputTarget(document, outputAddress);
            owners.add(NativeLibraryOwnersTest.install(document, l, i == 1 ? wireB : null));
            owners.get(i).setPaused(false, l, true);
        }
        var devices = Map.of(BlockPos.ZERO, wireB, new BlockPos(2, 0, 0), wireR, new BlockPos(1, 0, 1), wireO);
        var world = new CableDiscovery.World() {
            public boolean loaded(BlockPos p) { return true; }
            public CableDiscovery.Cell cell(BlockPos p) { return new CableDiscovery.Cell(p.equals(new BlockPos(1, 0, 0)) ? CableDiscovery.Kind.CABLE : devices.containsKey(p) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null); }
        };
        UUID source = UUID.randomUUID();
        var reader = new ReaderSamplingProtocol(UUID.randomUUID(), UUID.randomUUID(), readerAddress, Set.of("create_biotech:redstone"));
        var output = new RedstoneOutputState.State(UUID.randomUUID(), UUID.randomUUID(), 4,
            Optional.of(mode.equals("output_denied") ? UUID.randomUUID() : source), 6, 6);
        var before = output;
        var network = new SerialNetwork(); var history = new XBusHistorySavedData();
        var commands = new ArrayList<String>(); var correlations = new HashSet<DataValue>();
        int[] captures = {0}; boolean restored = false;
        var replyQueues = List.of(new ArrayDeque<DataValue.RecordValue>(), new ArrayDeque<DataValue.RecordValue>());
        for (int tick = 0; tick < 12000; tick++) {
            int aStatus = NativeLibraryStartupTest.status(owners.get(1), "x0", limits.get(1), owners.get(0), "x0", limits.get(0));
            int eStatus = NativeLibraryStartupTest.status(owners.get(1), "x0", limits.get(1), owners.get(4), "x0", limits.get(4));
            int cStatus = NativeLibraryStartupTest.status(owners.get(2), "x0", limits.get(2), owners.get(1), "x1", limits.get(1));
            int dStatus = NativeLibraryStartupTest.status(owners.get(3), "x0", limits.get(3), owners.get(0), "x1", limits.get(0));
            for (int i = 0; i < 5; i++) owners.get(i).tick(limits.get(i), true,
                i == 1 ? Map.of("x0", Math.max(aStatus, eStatus)) : i == 2 ? Map.of("x0", cStatus) : i == 3 ? Map.of("x0", dStatus) : Map.of());
            for (int[] edge : List.of(new int[]{0,0,1,0}, new int[]{4,0,1,0}, new int[]{1,1,2,0}, new int[]{0,1,3,0}, new int[]{3,1,4,1}))
                NativeLibraryServiceSerialTest.exchange(owners.get(edge[0]), "x" + edge[1], limits.get(edge[0]), owners.get(edge[2]), "x" + edge[3], limits.get(edge[2]), history);
            network.tick(world, devices, config.communication(false), config.serialMedium(false));
            for (int device = 0; device < 2; device++) {
                var wire = device == 0 ? wireR : wireO;
                if (wire.status("s0").receive().frames() > 0) {
                    var received = wire.prepareReceive("s0"); var packet = (DataValue.RecordValue)received.result().field("value");
                    if ((device == 0 ? readerAddress : outputAddress).equals(packet.field("target"))) {
                        assertTrue(correlations.add(packet.field("request")), "No replay or automatic retry");
                        String op = ((DataValue.TextValue)packet.field("operation")).value(); commands.add((device == 0 ? "reader/" : "output/") + op);
                        DataValue.RecordValue reply;
                        if (device == 0) reply = reader.respond(!mode.equals("denied"), packet, ids -> {
                            captures[0]++;
                            DataValue component = mode.equals("component_error") ? new DataValue.ErrorValue("target_unloaded", "")
                                : record("face", "west", "direct", 4, "comparator", new DataValue.ErrorValue("unsupported", ""), "signal", mode.equals("wrong_type") ? new DataValue.TextValue("4") : new DataValue.IntegerValue(mode.equals("range") ? 16 : 4));
                            return record("game_time", 91, "target", record("x", 3, "y", 0, "z", 0), "components", record("create_biotech:redstone", component));
                        }).orElseThrow();
                        else {
                            var plan = RedstoneOutputProtocol.plan(output, source, outputAddress, packet).orElseThrow(); output = plan.next(); reply = plan.reply();
                            if (mode.equals("stale_output") && op.equals("status")) output = RedstoneOutputState.restart(output, UUID.randomUUID());
                        }
                        if (mode.equals("restore")) {
                            var stale = new LinkedHashMap<>(reply.fields()); stale.put("request", new DataValue.TextValue("old-request")); replyQueues.get(device).add(new DataValue.RecordValue(stale));
                            stale = new LinkedHashMap<>(reply.fields()); stale.put("kind", new DataValue.TextValue(device == 0 ? "redstone_reply" : "reader_reply")); replyQueues.get(device).add(new DataValue.RecordValue(stale));
                        }
                        if (mode.equals("error_with_value") && device == 0 && op.equals("sample")) {
                            var failed = new LinkedHashMap<>(reply.fields()); failed.put("code", new DataValue.TextValue("failed")); reply = new DataValue.RecordValue(failed);
                        }
                        replyQueues.get(device).add(reply);
                    }
                    assertTrue(received.publish());
                }
                if (!replyQueues.get(device).isEmpty() && wireB.status("s0").receive().frames() == 0 && !Set.of("queued", "transmitting").contains(wire.status("s0").transmit()))
                    assertEquals("queued", wire.send("s0", replyQueues.get(device).removeFirst()));
            }
            for (int i = 0; i < 5; i++) {
                var owner = owners.get(i); assertTrue(owner.loaded(), owner.diagnostic().toString());
                assertNotEquals(FlowRuntime.Status.FAILED, owner.flowSnapshot().orElseThrow().state().status(), "Owner " + i + ": " + owner.flowSnapshot().orElseThrow().state().diagnostic());
                assertTrue(NativeLibraryServiceSerialTest.units(owner.flowSnapshot().orElseThrow(), limits.get(i)) <= limits.get(i).hardware().memoryUnits());
            }
            if (mode.equals("restore") && !restored && owners.get(1).flowSnapshot().orElseThrow().tasks().contexts().stream()
                    .anyMatch(s -> s.assembly().flatMap(r -> r.serial()).filter(p -> p.receiving()).isPresent())) {
                for (int i = 0; i < 5; i++) owners.set(i, NativeLibraryOwnersTest.reload(owners.get(i), limits.get(i), i == 1 ? wireB : null));
                history = XBusHistorySavedData.load(history.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY); restored = true;
            }
            if (List.of(0,3,4).stream().allMatch(i -> owners.get(i).flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE)) break;
            if (tick == 11999) fail("Application stalled: " + owners.stream().map(NativeLibraryOwnersTest::summary).toList() + " commands=" + commands);
        }
        boolean invalid = Set.of("denied", "wrong_type", "range", "component_error", "error_with_value").contains(mode);
        var computed = owners.get(3).flowSnapshot().orElseThrow().state().locals();
        var finalReply = owners.get(4).flowSnapshot().orElseThrow().state().locals().field("reply");
        assertEquals(new DataValue.BooleanValue(!invalid), computed.field("ok"));
        assertEquals(invalid ? DataValue.NullValue.INSTANCE : new DataValue.IntegerValue(11), computed.field("level"));
        assertEquals(mode.equals("denied") ? 0 : 1, captures[0]);
        if (invalid) {
            assertEquals(new DataValue.TextValue("invalid_sample"), finalReply); assertEquals(before, output, "No output query or mutation after failed sampling/computation");
            assertEquals(mode.equals("denied") ? List.of("reader/status") : List.of("reader/status", "reader/sample"), commands);
        } else {
            String expected = mode.equals("output_denied") ? "denied" : mode.equals("stale_output") ? "stale_session" : "applied";
            assertEquals(new DataValue.TextValue(expected), ((DataValue.RecordValue)finalReply).field("code"));
            assertEquals(mode.equals("output_denied") ? 6 : mode.equals("stale_output") ? 0 : 11, output.applied());
            assertEquals(mode.equals("output_denied") ? List.of("reader/status","reader/sample","output/status") : List.of("reader/status","reader/sample","output/status","output/set"), commands);
        }
        if (mode.equals("restore")) assertTrue(restored);
    }
    public static ProgramDocument outputTarget(ProgramDocument original, DataValue.RecordValue target) {
        var body = original.flow().orElseThrow(); var old = body.program(original.archive()); var steps = new ArrayList<FlowProgram.Step>();
        for (var step : old.steps()) {
            if (step instanceof FlowProgram.Procedure p) {
                var source = p.assembly().orElseThrow(); var text = source.text().replace("CONST target {\"x\":0,\"y\":0,\"z\":0}", "CONST target " + EditorValueFormat.format(target));
                assertNotEquals(source.text(), text);
                steps.add(new FlowProgram.Procedure(p.sourceId(), p.key(), p.inputs(), p.outputs(), p.returns(), p.rules(), Optional.of(new AssemblySource(source.version(), text))));
            } else steps.add(step);
        }
        var workspace = new ProgramWorkspace(original); workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(old.inputs(), steps).withAssembly(), body.initial())));
        return workspace.snapshot();
    }
}
