package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Fast reproduction of the actual published sender's lifetime. Peers are explicit fixtures,
 * not Library/serial/player acceptance evidence; the sender, ISA and XBus are real. */
class LibrarySegmentedPumpLifecycleTest {
    @Test void commandScopedSenderFitsDefaultHardwareWithAnExplicitCompilerVersion() throws Exception {
        var original = LibrarySegmentedWriteDocuments.build("step_pump");
        var body = original.flow().orElseThrow();
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), body.initial(), body.canvas(),
            body.inline(), body.execution(), FlowProgram.Compilation.parse("compact_v1"))));
        var document = workspace.snapshot();
        var limits = StockApplicationDriverDocuments.hardware();
        assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, limits.archive()), limits.archive()));
        PreparedProgram.prepare(document, limits);
        for (var role : List.of("step_pump", "step_slice")) {
            var candidate = LibrarySegmentedWriteDocuments.build(role);
            System.out.println(role + " " + candidate.flow().orElseThrow().program(candidate.archive()).usage(new ResourceLimits(4096,65536,8)));
            PreparedProgram.prepare(candidate, limits);
        }
    }
    private static final DataType.RecordType CONTEXT = new DataType.RecordType(Map.of(
        "r", NativeLibraryReadDocuments.ROW, "s", DataType.Scalar.ANY, "d", DataType.Scalar.ANY));
    private static final DataType.RecordType JOB = new DataType.RecordType(Map.of(
        "c", CONTEXT, "s", DataType.Scalar.BOOLEAN, "n", DataType.Scalar.INTEGER));

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void commandScopedSenderFinishesThe5495ByteCandidateWithoutAnUnboundedReceiptLifetime(boolean realSlice) throws Exception {
        var limits = StockApplicationDriverDocuments.hardware();
        var response = record("ok", true, "value", record("sequence", 1, "s", 1, "code", "accepted", "value", DataValue.NullValue.INSTANCE));
        var context = record("r", record("o", "write_begin", "d", DataValue.NullValue.INSTANCE, "e", "receiving", "l", false),
            "s", 0, "d", DataValue.NullValue.INSTANCE);
        var pump = install(LibrarySegmentedWriteDocuments.build("step_pump"), limits);
        var bytes = new DataValue.NbtValue(7, new DataValue.ListValue(Collections.nCopies(80, new DataValue.IntegerValue(42))));
        var tail = new DataValue.NbtValue(7, new DataValue.ListValue(Collections.nCopies(55, new DataValue.IntegerValue(42))));
        var buffer = install(peer(Map.of("job", JOB, "bytes", DataType.Scalar.NBT, "tail", DataType.Scalar.NBT),
            record("job", record("c", context, "s", true, "n", 5495), "bytes", bytes, "tail", tail), false, """
            TYPE Range RECORD o INTEGER n INTEGER
            TYPE Chunk RECORD offset INTEGER bytes NBT
            TYPE Result RECORD ok BOOLEAN value ANY
            REG result Result
            REG range Range
            REG chunk Chunk
            REG count INTEGER
            REG offset INTEGER
            REG zero INTEGER
            REG maximum INTEGER
            REG full BOOLEAN
            REG part NBT
            CONST zero 0
            CONST maximum 80
            XBUS.WRITE x0 job
            next:
            XBUS.READ range x0
            FIELD count range n
            EQUAL_INTEGER full count zero
            JFALSE full send
            XBUS.READ result x0
            RET
            send:
            FIELD offset range o
            EQUAL_INTEGER full count maximum
            JFALSE full last
            MOV part bytes
            JMP packed
            last:
            MOV part tail
            packed:
            RECORD.MAKE chunk offset offset bytes part
            XBUS.WRITE x0 chunk
            JMP next
            """), limits);
        var feeder = buffer;
        if (realSlice) {
            var allBytes = new DataValue.NbtValue(7, new DataValue.ListValue(Collections.nCopies(5495, new DataValue.IntegerValue(42))));
            var inputType = new DataType.RecordType(Map.of("c", CONTEXT, "s", DataType.Scalar.BOOLEAN,
                "n", DataType.Scalar.INTEGER, "b", DataType.Scalar.NBT));
            feeder = install(peer(Map.of("input", inputType), record("input", record("c", context, "s", true, "n", 5495, "b", allBytes)), false, """
                TYPE Result RECORD ok BOOLEAN value ANY
                REG result Result
                XBUS.WRITE x0 input
                XBUS.READ result x0
                """), limits);
            buffer = install(LibrarySegmentedWriteDocuments.build("step_slice"), limits);
        }
        var adapter = install(peer(Map.of("reply", NativeLibraryReadDocuments.OUTCOME), record("reply", response), true, """
            TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
            TYPE Context RECORD r Row s ANY d ANY
            REG context Context
            XBUS.READ context x0
            XBUS.WRITE x0 reply
            RET
            """), limits);
        long peak = 0; int resourceWait = 0;
        for (int tick = 0; tick < 20000; tick++) {
            if (realSlice) { feeder.tick(tick, limits, true, Map.of()); feeder.exchange("x0", limits, true, buffer, "x0", limits, true); }
            for (var host : List.of(buffer, pump, adapter)) host.tick(tick, limits, true, Map.of());
            var fromBuffer = pump.exchange("x0", limits, true, buffer, realSlice ? "x1" : "x0", limits, true);
            var fromAdapter = pump.exchange("x1", limits, true, adapter, "x0", limits, true);
            var saved = pump.flowSnapshot().orElseThrow();
            if (realSlice && tick % 1000 == 0) System.out.println("PUMP_SLICE tick=" + tick + " pump=" + saved.state().status()
                + " sliceReceipts=" + buffer.flowSnapshot().orElseThrow().receipts().stream().mapToLong(r -> r.value().storageUnits()).sum());
            for (var host : List.of(buffer, feeder)) {
                assertTrue(host.loaded(), host.diagnostic().toString());
                var state = host.flowSnapshot().orElseThrow().state();
                assertNotEquals(FlowRuntime.Status.FAILED, state.status(), state.diagnostic() + "; " + state.locals());
            }
            peak = Math.max(peak, saved.receipts().stream().mapToLong(r -> r.value().storageUnits()).sum());
            resourceWait = fromBuffer == XBusExchange.Status.MEMORY_LIMIT || fromAdapter == XBusExchange.Status.MEMORY_LIMIT
                ? resourceWait + 1 : 0;
            if (resourceWait == 1000) {
                var disk = new net.minecraft.nbt.CompoundTag();
                disk.put("pump", pump.write()); disk.put("buffer", buffer.write()); disk.put("adapter", adapter.write());
                disk.putInt("tick", tick);
                var path = java.nio.file.Path.of(".superpowers/cache/stock-native-driver/pump-lifetime-resource-wait.nbt");
                java.nio.file.Files.createDirectories(path.getParent());
                net.minecraft.nbt.NbtIo.writeCompressed(disk, path);
            }
            assertTrue(resourceWait < 1000, "Persistent resource wait: buffer=" + fromBuffer + ", adapter=" + fromAdapter
                + ", pump receipt units=" + peak + ", receipts=" + saved.receipts().size()
                + ", peers=" + List.of(buffer.flowSnapshot().orElseThrow().receipts().stream().mapToLong(r -> r.value().storageUnits()).sum(),
                    adapter.flowSnapshot().orElseThrow().receipts().stream().mapToLong(r -> r.value().storageUnits()).sum()));
            assertTrue(pump.loaded(), () -> "Sender suspended: " + pump.diagnostic());
            assertNotEquals(FlowRuntime.Status.FAILED, saved.state().status(),
                "Sender failed; original task outcome=" + saved.state().locals().fields().get("result") + "; peak receipt units=" + peak);
            for (var task : saved.tasks().contexts()) assertNotEquals(FlowRuntime.Status.FAILED, task.status(), task.diagnostic().toString());
            assertNotEquals(FlowRuntime.Status.FAILED, adapter.flowSnapshot().orElseThrow().state().status());
            var caller = realSlice ? feeder : buffer;
            if (caller.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE) {
                assertEquals(response, caller.flowSnapshot().orElseThrow().state().locals().field("result"));
                System.out.println("PUMP_LIFETIME realSlice=" + realSlice + " ticks=" + tick + " peakReceiptUnits=" + peak);
                return;
            }
        }
        for (var host : List.of(feeder, buffer, pump, adapter)) {
            var saved = host.flowSnapshot().orElseThrow();
            var states = new ArrayList<FlowRuntime.Checkpoint>(); states.add(saved.state()); states.addAll(saved.tasks().contexts());
            System.out.println("PUMP_PENDING loaded=" + host.loaded() + " diagnostic=" + host.diagnostic() + " tasks=" + states.stream().map(s ->
                s.status()+" pc="+s.assembly().map(a->a.pc()).orElse(-1)+" work="+s.work()+" error="+s.diagnostic()).toList());
        }
        fail("Sender did not finish; " + pump.diagnostic() + "; peak receipt units=" + peak);
    }

    static ComputerBlockHost install(ProgramDocument document, ComputerHostLimits limits) {
        var host = new ComputerBlockHost(() -> {});
        host.installProgram(PreparedProgram.prepare(document, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        host.setPaused(false, limits, true); return host;
    }

    static ProgramDocument peer(Map<String, DataType> inputs, DataValue.RecordValue initial, boolean managed, String source) throws Exception {
        var key = new ModuleDefinition.Key("i", 1);
        var args = new TreeMap<String, String>(); inputs.keySet().forEach(name -> args.put(name, name));
        var outputs = managed ? Map.<String, DataType>of() : Map.<String, DataType>of("result", NativeLibraryReadDocuments.OUTCOME);
        var returns = managed ? Map.<String, String>of() : Map.of("result", "result");
        var steps = new ArrayList<FlowProgram.Step>();
        if (managed) steps.addAll(List.of(new FlowProgram.Forever("service"),
            new FlowProgram.TaskStart("request", key, args, "task"), new FlowProgram.TaskJoin("claim", key, "task", "result"),
            new FlowProgram.Evaluate("require_result", "require_result", new FlowCallBindings(Map.of("result", "result"), Map.of())),
            new FlowProgram.EndForever("service_end")));
        else steps.add(new FlowProgram.Invoke("call", key, new FlowCallBindings(args, returns)));
        steps.add(new FlowProgram.Procedure("implementation", key, inputs, outputs, returns, FlowBlockRules.NONE, Optional.of(new AssemblySource(source))));
        steps.add(new FlowProgram.EndProcedure("end"));
        var body = FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), initial);
        if (managed) body = new FlowDocumentBody(body.inputs(), body.steps(), body.initial(), body.canvas(),
            Map.of("require_result", NativeClusterJournalDocuments.build("adapter").flow().orElseThrow().inline().get("require_result")), body.execution());
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(body)); return workspace.snapshot();
    }
}
