package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import net.minecraft.nbt.CompoundTag;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ComputerAssemblyConstructionTest {
    static FlowProgram program(boolean record) {return program(record, 3);}
    static FlowProgram program(boolean record, int arity) {
        var n = new LinkedHashMap<String, ComputationGraph.Node>();
        n.put("x", new ComputationGraph.Input("x"));
        if (record) {
            var f = new LinkedHashMap<String, String>();
            f.put("first", "x");
            f.put("second", "x");
            f.put("third", "x");
            n.put("value", new ComputationGraph.MakeRecord(f));
        }
        else n.put("value", new ComputationGraph.MakeList(Collections.nCopies(arity, "x")));
        var g = new ComputationGraph(Map.of("x", DataType.Scalar.INTEGER), n, Map.of("value", "value"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(), Map.of("build", new ProgramDefinition.Entry(g, List.of(), List.of()))), Map.of());
        return new FlowProgram(Map.of("x", DataType.Scalar.INTEGER), List.of(new FlowProgram.Evaluate("build", "build", new FlowCallBindings(Map.of("x", "x"), Map.of("value", "value")))), archive).withAssembly();
    }
    static FlowProgram authored(boolean task) {
        var key = new ModuleDefinition.Key("builder", 1);
        var steps = new ArrayList<FlowProgram.Step>();
        if (task) {
            steps.add(new FlowProgram.TaskStart("launch", key, Map.of("x", "x"), "h"));
            steps.add(new FlowProgram.TaskJoin("join", key, "h", "joined"));
        }
        else steps.add(new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("x", "x"), Map.of("value", "value"))));
        steps.add(new FlowProgram.Procedure("builder", key, Map.of("x", DataType.Scalar.INTEGER), Map.of("value", new DataType.ListType(DataType.Scalar.INTEGER)), Map.of("value", "out"), FlowBlockRules.NONE,
        Optional.of(new AssemblySource("REG out OUTPUT value\nLIST.MAKE out x x x\nRET"))));
        steps.add(new FlowProgram.EndProcedure("end"));
        return new FlowProgram(Map.of("x", DataType.Scalar.INTEGER), steps).withAssembly();
    }
    static FlowProgram constructionWithTransactions(boolean atomic) {
        var base = program(false);
        var definition = base.expressions().program();
        var archive = new ProgramArchive(new ProgramDefinition(definition.graph(),
            Map.of("payload", new ProgramDefinition.Cell(new DataType.ListType(DataType.Scalar.INTEGER),
                new DataValue.ListValue(List.of()))), definition.writes(), definition.sends(), definition.entries()), Map.of());
        var steps = new ArrayList<FlowProgram.Step>();
        steps.add(new FlowProgram.Evaluate("outside", "build", new FlowCallBindings(Map.of("x", "x"), Map.of("value", "outside"))));
        steps.add(atomic ? new FlowProgram.Atomic("begin", "result")
            : new FlowProgram.Memory("begin", FlowProgram.MemoryKind.BEGIN, "", ""));
        steps.add(new FlowProgram.Evaluate("inside", "build", new FlowCallBindings(Map.of("x", "x"), Map.of("value", "inside"))));
        steps.add(new FlowProgram.Memory("stage", FlowProgram.MemoryKind.WRITE, "payload", "inside"));
        steps.add(atomic ? new FlowProgram.EndAtomic("commit")
            : new FlowProgram.Memory("commit", FlowProgram.MemoryKind.COMMIT, "", "result"));
        steps.add(new FlowProgram.Evaluate("after", "build", new FlowCallBindings(Map.of("x", "x"), Map.of("value", "after"))));
        return new FlowProgram(base.inputs(), steps, archive).withAssembly();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void constructionV4RestoresEveryActiveAndInactiveTransactionPhase(boolean atomic) {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(constructionWithTransactions(atomic),
            new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))), LIMITS, true);
        var seen = new HashMap<Boolean, Set<Integer>>();
        var expected = new DataValue.ListValue(Collections.nCopies(3, new DataValue.IntegerValue(7)));
        for (int step = 0; ; step++) {
            assertTrue(step < 60);
            var before = host.flowSnapshot().orElseThrow();
            var assembly = before.state().assembly().orElseThrow();
            assertEquals(new DataValue.IntegerValue(4), assembly.value().field("data_version"));
            boolean active = assembly.transaction().orElseThrow();
            assembly.build().ifPresent(build -> seen.computeIfAbsent(active, ignored -> new HashSet<>()).add(build.cursor()));
            var saved = host.write();
            var owner = (DataValue.RecordValue) ValueNbtCodec.decode(((CompoundTag) saved).getCompound("Data"), LIMITS.archive());
            assertEquals(new DataValue.IntegerValue(34), record(owner, "flow").field("version"));
            var restored = new ComputerBlockHost(() -> {});
            restored.read(saved);
            restored.tick(LIMITS, true);
            assertTrue(restored.loaded());
            assertEquals(before, restored.flowSnapshot().orElseThrow());
            assertEquals(saved, restored.write());
            host = restored;
            if (before.state().status() == FlowRuntime.Status.COMPLETE) break;
            assertEquals(FlowRuntime.Status.READY, before.state().status());
            host.step(LIMITS, true);
        }
        assertEquals(Map.of(false, Set.of(0, 1, 2, 3), true, Set.of(0, 1, 2, 3)), seen);
        var done = host.flowSnapshot().orElseThrow();
        assertFalse(done.state().assembly().orElseThrow().transaction().orElseThrow());
        assertEquals(expected, done.state().locals().field("outside"));
        assertEquals(expected, done.state().locals().field("inside"));
        assertEquals(expected, done.state().locals().field("after"));
        assertEquals(expected, done.memory().cells().field("payload"));
        assertTrue(done.memory().owners().isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "wrong_type", "wrong_bool", "data_downgrade", "flow_downgrade"})
    void constructionV4RejectsCorruptActiveAndInactiveTransactionOwners(String mutation) {
        for (boolean atomic : List.of(false, true)) for (boolean active : List.of(false, true)) {
            var host = new ComputerBlockHost(() -> {});
            host.installFlow(constructionWithTransactions(atomic),
                new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))), LIMITS, true);
            for (int step = 0; ; step++) {
                assertTrue(step < 40);
                var state = host.flowSnapshot().orElseThrow().state().assembly().orElseThrow();
                if (state.build().filter(build -> build.cursor() == 1).isPresent()
                        && state.transaction().orElseThrow() == active) break;
                host.step(LIMITS, true);
            }
            var raw = (CompoundTag) host.write();
            var owner = (DataValue.RecordValue) ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
            var flow = record(owner, "flow");
            var state = record(flow, "state");
            var assembly = record(state, "assembly");
            switch (mutation) {
                case "missing" -> {
                    var fields = new LinkedHashMap<>(assembly.fields());
                    fields.remove("transaction");
                    assembly = new DataValue.RecordValue(fields);
                }
                case "wrong_type" -> assembly = patch(assembly, "transaction", new DataValue.TextValue("true"));
                case "wrong_bool" -> assembly = patch(assembly, "transaction", new DataValue.BooleanValue(!active));
                case "data_downgrade" -> assembly = patch(assembly, "data_version", new DataValue.IntegerValue(3));
                case "flow_downgrade" -> flow = patch(flow, "version", new DataValue.IntegerValue(33));
                default -> throw new AssertionError(mutation);
            }
            raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", patch(state, "assembly", assembly))), LIMITS.archive()));
            var restored = new ComputerBlockHost(() -> {});
            restored.read(raw);
            restored.tick(LIMITS, true);
            assertFalse(restored.loaded());
            assertEquals(raw, restored.write(), "Rejected transaction state must retain the complete raw owner");
            assertTrue(restored.diagnostic().isPresent());
        }
    }

    static java.util.stream.Stream<String> authoredLineSeparators() {
        return java.util.stream.Stream.of("\n", "\r", "\r\n", Character.toString(11),
            Character.toString(12), Character.toString(133), Character.toString(8232), Character.toString(8233));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("authoredLineSeparators")
    void authoredLineSeparatorsPreserveConstructorCapabilityAndEveryOwnerCheckpoint(String separator) {
        for (boolean record : List.of(false, true)) {
            var key = new ModuleDefinition.Key("builder", 1);
            DataType output = record
                ? new DataType.RecordType(Map.of("a", DataType.Scalar.INTEGER, "b", DataType.Scalar.INTEGER))
                : new DataType.ListType(DataType.Scalar.INTEGER);
            var source = new AssemblySource(String.join(separator, "REG out OUTPUT value",
                record ? "RECORD.MAKE out a x b x" : "LIST.MAKE out x x", "RET"));
            var flow = new FlowProgram(Map.of("x", DataType.Scalar.INTEGER), List.of(
                new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("x", "x"), Map.of("value", "value"))),
                new FlowProgram.Procedure("builder", key, Map.of("x", DataType.Scalar.INTEGER),
                    Map.of("value", output), Map.of("value", "out"), FlowBlockRules.NONE, Optional.of(source)),
                new FlowProgram.EndProcedure("end"))).withAssembly();
            var host = new ComputerBlockHost(() -> {});
            host.installFlow(flow, new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))), LIMITS, true);
            for (int step = 0; ; step++) {
                assertTrue(step < 30);
                var before = host.flowSnapshot().orElseThrow();
                var saved = host.write();
                var restored = new ComputerBlockHost(() -> {});
                restored.read(saved);
                restored.tick(LIMITS, true);
                assertTrue(restored.loaded(), "Accepted authored line separator must load its owner");
                assertEquals(before, restored.flowSnapshot().orElseThrow());
                assertEquals(saved, restored.write());
                assertTrue(flow.usesConstructionAssembly());
                host = restored;
                if (before.state().status() == FlowRuntime.Status.COMPLETE) break;
                host.step(LIMITS, true);
            }
            DataValue expected = record
                ? new DataValue.RecordValue(Map.of("a", new DataValue.IntegerValue(7), "b", new DataValue.IntegerValue(7)))
                : new DataValue.ListValue(List.of(new DataValue.IntegerValue(7), new DataValue.IntegerValue(7)));
            assertEquals(expected, host.flowSnapshot().orElseThrow().state().locals().field("value"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("authoredLineSeparators")
    void oldAuthoredLineSeparatorsAndCommentedConstructorsKeepOldImageAndSchema(String separator) {
        var key = new ModuleDefinition.Key("old", 1);
        var source = new AssemblySource(String.join(separator, "REG out INTEGER",
            "; LIST.MAKE out x", "MOV out x ; RECORD.MAKE out a x", "RET"));
        var flow = new FlowProgram(Map.of("x", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("x", "x"), Map.of("value", "value"))),
            new FlowProgram.Procedure("old", key, Map.of("x", DataType.Scalar.INTEGER),
                Map.of("value", DataType.Scalar.INTEGER), Map.of("value", "out"), FlowBlockRules.NONE, Optional.of(source)),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var referenceSource = new AssemblySource(source.text().replace(separator, "\n"));
        var referenceImage = com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler.assemble(
            referenceSource, Map.of("x", DataType.Scalar.INTEGER), Map.of("value", DataType.Scalar.INTEGER),
            Map.of("value", "out"), 10000, 10000).program();
        var image = com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler.assemble(
            source, Map.of("x", DataType.Scalar.INTEGER), Map.of("value", DataType.Scalar.INTEGER),
            Map.of("value", "out"), 10000, 10000).program();
        var procedureInputs = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7)));
        assertEquals(referenceImage.forTask("local", 1).begin(procedureInputs).image(),
            image.forTask("local", 1).begin(procedureInputs).image());
        assertFalse(flow.usesConstructionAssembly());
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(flow, new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))), LIMITS, true);
        var oldState = host.flowSnapshot().orElseThrow().state().assembly().orElseThrow();
        assertFalse(oldState.constructionCapable());
        assertFalse(oldState.value().fields().containsKey("build"));
        assertNotEquals(new DataValue.IntegerValue(4), oldState.value().fields().get("data_version"));
        for (int step = 0; ; step++) {
            assertTrue(step < 20);
            var before = host.flowSnapshot().orElseThrow();
            var restored = new ComputerBlockHost(() -> {});
            restored.read(host.write());
            restored.tick(LIMITS, true);
            assertTrue(restored.loaded());
            assertEquals(before, restored.flowSnapshot().orElseThrow());
            host = restored;
            if (before.state().status() == FlowRuntime.Status.COMPLETE) break;
            host.step(LIMITS, true);
        }
        assertEquals(new DataValue.IntegerValue(7), host.flowSnapshot().orElseThrow().state().locals().field("value"));
    }

    @Test void authoredConstructorUsesTheSameNativeCalledOwnerPath() {
        var host = new ComputerBlockHost(()-> {
        });
        host.installFlow(authored(false), new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(9))), LIMITS, true);
        for (int i = 0;i<30 && host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY;i++) {
            var before = host.flowSnapshot().orElseThrow();
            var r = new ComputerBlockHost(()-> {
            });
            r.read(host.write());
            r.tick(LIMITS, true);
            assertTrue(r.loaded());
            assertEquals(before, r.flowSnapshot().orElseThrow());
            host = r;
            host.step(LIMITS, true);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, host.flowSnapshot().orElseThrow().state().status());
        assertEquals(new DataValue.ListValue(Collections.nCopies(3, new DataValue.IntegerValue(9))), host.flowSnapshot().orElseThrow().state().locals().field("value"));
    }
    @Test void nestedCallAndAuthoredRecordConstructorUseSameRecoverableOwner() {
        var key = new ModuleDefinition.Key("record", 1);var wrapper = new ModuleDefinition.Key("wrapper", 1);
        var type = new DataType.RecordType(Map.of("a", DataType.Scalar.INTEGER, "b", DataType.Scalar.INTEGER));
        var p = new FlowProgram(Map.of("x", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Invoke("main", wrapper, new FlowCallBindings(Map.of("x", "x"), Map.of("value", "value"))),
            new FlowProgram.Procedure("wrapper", wrapper, Map.of("x", DataType.Scalar.INTEGER), Map.of("value", type), Map.of("value", "inner")),
            new FlowProgram.Invoke("nested", key, new FlowCallBindings(Map.of("x", "x"), Map.of("value", "inner"))), new FlowProgram.EndProcedure("wrapper_end"),
            new FlowProgram.Procedure("record", key, Map.of("x", DataType.Scalar.INTEGER), Map.of("value", type), Map.of("value", "out"), FlowBlockRules.NONE, Optional.of(new AssemblySource("REG out OUTPUT value\nRECORD.MAKE out a x b x\nRET"))),new FlowProgram.EndProcedure("record_end"))).withAssembly();
        var host = new ComputerBlockHost(() -> {});host.installFlow(p, new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(9))), LIMITS, true);
        boolean nested = false;
        for (int i = 0; host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY; i++) {
            assertTrue(i < 40);var before = host.flowSnapshot().orElseThrow();nested |= before.state().assembly().orElseThrow().calls().orElseThrow().frames().size() == 2;
            var restored = new ComputerBlockHost(() -> {});restored.read(host.write());restored.tick(LIMITS, true);assertTrue(restored.loaded());assertEquals(before, restored.flowSnapshot().orElseThrow());host = restored;host.step(LIMITS, true);
        }
        assertTrue(nested);assertEquals(FlowRuntime.Status.COMPLETE, host.flowSnapshot().orElseThrow().state().status());assertEquals(new DataValue.RecordValue(Map.of("a", new DataValue.IntegerValue(9), "b", new DataValue.IntegerValue(9))), host.flowSnapshot().orElseThrow().state().locals().field("value"));
    }
    @Test void ordinaryHardwarePlayerDemoIs28WordsAndPausesAfterFirstLiveOccurrence() {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("items", new ComputationGraph.Input("items"));
        nodes.put("position", new ComputationGraph.Constant(new DataValue.IntegerValue(2)));
        nodes.put("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
        nodes.put("index", new ComputationGraph.Scalar(ScalarOperation.SUBTRACT_INTEGER, Map.of("left", "position", "right", "one")));
        nodes.put("selected", new ComputationGraph.DynamicIndex("items", "index"));
        nodes.put("seed", new ComputationGraph.Input("seed"));
        nodes.put("value", new ComputationGraph.MakeList(List.of("selected", "seed")));
        var inputs = Map.<String, DataType>of("items", new DataType.ListType(DataType.Scalar.INTEGER), "seed", DataType.Scalar.INTEGER);
        var graph = new ComputationGraph(inputs, nodes, Map.of("value", "value"));
        var archive = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(), Map.of("build", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var p = new FlowProgram(inputs, List.of(new FlowProgram.Evaluate("build", "build", new FlowCallBindings(Map.of("items", "items", "seed", "seed"), Map.of("value", "payload")))), archive).withAssembly();
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(32, 100000, 1), LIMITS.archive()));
        assertEquals(28, p.usage(limits.hardware()).programUnits());
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(p, new DataValue.RecordValue(Map.of("items", new DataValue.ListValue(List.of(new DataValue.IntegerValue(0), new DataValue.IntegerValue(42))), "seed", new DataValue.IntegerValue(7))), limits, true);
        for (int i = 0; i < 8; i++) host.step(limits, true);
        var paused = host.flowSnapshot().orElseThrow();
        assertEquals(6, paused.state().assembly().orElseThrow().pc());
        assertEquals(1, paused.state().assembly().orElseThrow().build().orElseThrow().cursor());
        assertFalse(paused.state().locals().fields().containsKey("payload"));
        var restored = new ComputerBlockHost(() -> {});
        restored.read(host.write()); restored.tick(limits, true); assertEquals(paused, restored.flowSnapshot().orElseThrow());
        for (int i = 0; i < 3; i++) restored.step(limits, true);
        assertEquals(11, restored.flowSnapshot().orElseThrow().state().work());
        assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(42), new DataValue.IntegerValue(7))), restored.flowSnapshot().orElseThrow().state().locals().field("payload"));
    }
    @Test void realOwnerLiveChangesKeepTheSamePrivatePrefixWitness() throws Exception {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program(true), new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))), LIMITS, true);
        for (int i = 0; i < 4; i++) host.step(LIMITS, true);
        var before = host.flowSnapshot().orElseThrow().state().assembly().orElseThrow();
        var build = before.build().orElseThrow();
        var prefix = build.getClass().getDeclaredField("prefix"); prefix.setAccessible(true);
        var handle = prefix.get(build);
        for (int i = 0; i < 5; i++) {
            host.setPaused(false, LIMITS, true); host.setPaused(true, LIMITS, true);
            var after = host.flowSnapshot().orElseThrow().state().assembly().orElseThrow();
            assertSame(before.registers(), after.registers()); assertSame(build, after.build().orElseThrow()); assertSame(handle, prefix.get(build));
        }
        host.step(LIMITS, true); assertSame(handle, prefix.get(build)); assertEquals(16, build.ownedUnits());
        var external = new ComputerBlockHost(() -> {}); external.read(host.write()); external.tick(LIMITS, true);
        assertEquals(host.flowSnapshot(), external.flowSnapshot());
    }
    @Test void prefixRamAdmissionIsAtomicAndFailureRetainsPaidPrefixAcrossReload() {
        var host = new ComputerBlockHost(()-> {
        });
        var p = program(false);
        host.installFlow(p, new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))), LIMITS, true);
        for (int i = 0;i<3;i++)host.step(LIMITS, true);
        var before = host.flowSnapshot().orElseThrow();
        assertEquals(1, before.state().assembly().orElseThrow().build().orElseThrow().cursor());
        long memory = before.state().controlUnits() + before.initialInputs().storageUnits() + before.control().storageUnits() + p.usage(LIMITS.hardware()).constantUnits();
        var small = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, memory, 1), LIMITS.archive()));
        host.step(small, true);
        var failed = host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, failed.state().diagnostic().orElseThrow().code());
        assertEquals(before.state().assembly(), failed.state().assembly());
        assertEquals(before.state().work() + 1, failed.state().work());
        assertFalse(failed.state().locals().fields().containsKey("value"));
        assertEquals(2, before.state().assembly().orElseThrow().constructionUnits());
        var r = new ComputerBlockHost(()-> {
        });
        r.read(host.write());
        r.tick(LIMITS, true);
        assertTrue(r.loaded());
        assertEquals(failed, r.flowSnapshot().orElseThrow());
    }
    @Test void finishArchiveRejectionCannotLeakACandidateOrMutatePriorPrefix() {
        var host = new ComputerBlockHost(()-> {
        });
        host.installFlow(program(false, 20), new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))), LIMITS, true);
        for (int i = 0;i<22;i++)host.step(LIMITS, true);
        var before = host.flowSnapshot().orElseThrow();
        var raw = (CompoundTag)host.write();
        var value = ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var exact = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100000, 1000000, value.storageUnits())));
        assertThrows(RuntimeException.class, ()->host.step(exact, true));
        assertEquals(raw, host.write());
        assertEquals(before, host.flowSnapshot().orElseThrow());
        assertEquals(21, before.state().assembly().orElseThrow().constructionUnits());
        host.tick(LIMITS, true);
        host.step(LIMITS, true);
        assertEquals(23, host.flowSnapshot().orElseThrow().state().work());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "cursor", "work", "pc", "source", "data", "flow", "scan"
    })
    void corruptConstructionSavesRetainRawOwnerWithoutRestart(String mutation) {
        var host = new ComputerBlockHost(()-> {
        });
        host.installFlow(program(false), new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))), LIMITS, true);
        for (int i = 0;i<3;i++)host.step(LIMITS, true);
        var raw = (CompoundTag)host.write();
        var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = record(owner, "flow");
        var state = record(flow, "state");
        var assembly = record(state, "assembly");
        switch (mutation) {
            case "cursor"->assembly = patch(assembly, "build", new DataValue.RecordValue(Map.of("cursor", new DataValue.IntegerValue(Integer.MAX_VALUE))));
            case "work"-> {
                state = patch(state, "work", new DataValue.IntegerValue(2));
                state = patch(state, "operation", new DataValue.IntegerValue(2));
            }
            case "pc"->assembly = patch(assembly, "pc", new DataValue.IntegerValue(2));
            case "source"-> {
                var registers = record(assembly, "registers");
                var key = registers.fields().keySet().iterator().next();
                assembly = patch(assembly, "registers", patch(registers, key, new DataValue.TextValue("not integer")));
            }
            case "data"->assembly = patch(assembly, "data_version", new DataValue.IntegerValue(1));
            case "flow"->flow = patch(flow, "version", new DataValue.IntegerValue(23));
            case "scan"->assembly = patch(assembly, "scan", new DataValue.RecordValue(Map.of("offset", new DataValue.IntegerValue(1), "points", new DataValue.IntegerValue(1))));
        }
        raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", patch(state, "assembly", assembly))), LIMITS.archive()));
        var restored = new ComputerBlockHost(()-> {
        });
        restored.read(raw);
        restored.tick(LIMITS, true);
        assertFalse(restored.loaded());
        assertEquals(raw, restored.write());
    }
    static DataValue.RecordValue record(DataValue.RecordValue p, String key) {
        return (DataValue.RecordValue)p.field(key);
    }
    static DataValue.RecordValue patch(DataValue.RecordValue p, String key, DataValue v) {
        var f = new LinkedHashMap<>(p.fields());
        f.put(key, v);
        return new DataValue.RecordValue(f);
    }
    @Test void disconnectedConstructorInAUsedEntryDoesNotUpgradeTheLiveImage() {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("value", new ComputationGraph.Constant(new DataValue.IntegerValue(3)));
        nodes.put("draft", new ComputationGraph.MakeList(List.of("value")));
        var graph = new ComputationGraph(Map.of(), nodes, Map.of("value", "value"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(), Map.of("build", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var p = new FlowProgram(Map.of(), List.of(new FlowProgram.Evaluate("build", "build", new FlowCallBindings(Map.of(), Map.of("value", "value")))), archive).withAssembly();
        var host = new ComputerBlockHost(() -> {});host.installFlow(p, new DataValue.RecordValue(Map.of()), LIMITS, true);
        var restored = new ComputerBlockHost(() -> {});restored.read(host.write());restored.tick(LIMITS, true);
        assertTrue(restored.loaded());assertEquals(host.flowSnapshot(), restored.flowSnapshot());
    }
    @Test void unusedConstructorEntryDoesNotUpgradeAnUnchangedOldImageOrStateFormat() {
        var base = new FlowProgram(Map.of(), List.of()).withAssembly();
        var unused = program(false).expressions();
        var p = new FlowProgram(Map.of(), List.of(), unused).withAssembly();
        var a = new ComputerBlockHost(()-> {
        });
        var b = new ComputerBlockHost(()-> {
        });
        var empty = new DataValue.RecordValue(Map.of());
        a.installFlow(base, empty, LIMITS, true);
        b.installFlow(p, empty, LIMITS, true);
        assertEquals(a.flowSnapshot().orElseThrow().state().assembly(), b.flowSnapshot().orElseThrow().state().assembly());
        var r = new ComputerBlockHost(()-> {
        });
        r.read(b.write());
        r.tick(LIMITS, true);
        assertTrue(r.loaded());
        assertEquals(b.flowSnapshot(), r.flowSnapshot());
    }
    @Test void authoredConstructionCannotBypassEvaluateBodyRules() {
        var p = authored(false);
        var steps = new ArrayList<>(p.steps());
        var d = (FlowProgram.Procedure)steps.get(1);
        steps.set(1, new FlowProgram.Procedure(d.sourceId(), d.key(), d.inputs(), d.outputs(), d.returns(), new FlowBlockRules(Set.of("evaluate"), Set.of()), d.assembly()));
        assertThrows(ComputationException.class, ()->new FlowProgram(p.inputs(), steps, p.expressions()).withAssembly().validate(LIMITS.hardware()));
    }
    @Test void managedTaskRestoresEveryConstructionPhaseWithoutChangingSourceOrHalfPublishing() {
        var taskLimits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3));
        var p = authored(true);
        var host = new ComputerBlockHost(()-> {
        });
        host.installFlow(p, new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(9))), taskLimits, true);
        host.setPaused(false, taskLimits, true);
        var active = new HashSet<Integer>();
        for (int i = 0;i<40 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE;i++) {
            host.tick(taskLimits, true);
            var before = host.flowSnapshot().orElseThrow();
            before.tasks().contexts().forEach(s->s.assembly().orElseThrow().build().ifPresent(b->active.add(b.cursor())));
            host.setPaused(true, taskLimits, true);
            var paused = host.flowSnapshot().orElseThrow();
            var r = new ComputerBlockHost(()-> {
            });
            r.read(host.write());
            r.tick(taskLimits, true);
            assertTrue(r.loaded());
            assertEquals(paused, r.flowSnapshot().orElseThrow());
            host = r;
            host.setPaused(false, taskLimits, true);
        }
        assertEquals(Set.of(0, 1, 2, 3), active);
        var done = host.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, done.status());
        assertEquals(new DataValue.ListValue(Collections.nCopies(3, new DataValue.IntegerValue(9))), ((DataValue.RecordValue)((DataValue.RecordValue)done.locals().field("joined")).field("value")).field("value"));
    }
    @Test void liveListPublishesOnlyAfterFiveNativeConstructionCyclesAndSurvivesEveryOwnerCheckpoint() {
        var host = new ComputerBlockHost(()-> {
        });
        var input = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7)));
        host.installFlow(program(false), input, LIMITS, true);
        int unchanged = 0;
        for (int i = 0;i<20 && host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY;i++) {
            var before = host.flowSnapshot().orElseThrow();
            var raw = host.write();
            var restored = new ComputerBlockHost(()-> {
            });
            restored.read(raw);
            restored.tick(LIMITS, true);
            assertTrue(restored.loaded());
            assertEquals(before, restored.flowSnapshot().orElseThrow());
            host = restored;
            host.step(LIMITS, true);
            var after = host.flowSnapshot().orElseThrow();
            assertEquals(before.state().work() + 1, after.state().work());
            assertTrue(after.state().expression().isEmpty());
            if (before.state().assembly().orElseThrow().pc() == after.state().assembly().orElseThrow().pc()) {
                unchanged++;
                assertFalse(after.state().locals().fields().containsKey("value"));
            }
            assertEquals(input, after.initialInputs());
        }
        assertEquals(4, unchanged);
        assertEquals(7, host.flowSnapshot().orElseThrow().state().work());
        assertEquals(new DataValue.ListValue(List.of(input.field("x"), input.field("x"), input.field("x"))), host.flowSnapshot().orElseThrow().state().locals().field("value"));
    }
}
