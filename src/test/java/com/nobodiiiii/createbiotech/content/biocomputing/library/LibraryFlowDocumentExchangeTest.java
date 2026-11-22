package com.nobodiiiii.createbiotech.content.biocomputing.library;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.io.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.*;

class LibraryFlowDocumentExchangeTest {
    private static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(
        new ResourceLimits(1000, 100_000, 100), LibraryBookMediaTest.LIMITS));
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }

    @Test void completeFlowTravelsInTheBookButInstalledCopiesNeverFollowBookChanges() throws Exception {
        var source = document(2); var running = install(source); finish(running);
        assertEquals(new DataValue.IntegerValue(2), running.flowSnapshot().orElseThrow().state().locals().field("count"));
        var sourceSave = running.write();
        var view = new LibraryBookAccessTest.View(); var original = view.book.copy();
        var session = LibraryBookSessionTest.session(view); session.refresh(); session.select(0);
        var exported = ProgramDocumentWire.decode(ProgramDocumentWire.encode(source, LIMITS.archive()), LIMITS.archive());
        assertEquals("ok", session.mutate(session.revision(), false, "flow", new LibraryEntry(
            LibraryEntry.Kind.PROGRAM, ProgramDocumentCodec.encode(exported))));
        LibraryBookIdentityPageTest.assertOriginalPagesPreserved(original, view.book);
        assertEquals(original.get(DataComponents.CUSTOM_DATA), view.book.get(DataComponents.CUSTOM_DATA));
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(view.book.get(LibraryBookMediaTest.COMPONENT).copyTag(), new DataOutputStream(bytes));
        view.book = original.copy();
        view.book.set(LibraryBookMediaTest.COMPONENT, CustomData.of(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))));
        session = LibraryBookSessionTest.session(view); session.refresh(); session.select(0);
        var retrieved = ProgramDocumentCodec.decode(session.read("flow").value());
        assertEquals(source, retrieved, "Flow, fixed-version modules and every graph position travel together");
        assertEquals(new DataValue.IntegerValue(0), retrieved.flow().orElseThrow().initial().field("count"));
        assertEquals(original.get(DataComponents.WRITABLE_BOOK_CONTENT), view.book.get(DataComponents.WRITABLE_BOOK_CONTENT));
        assertEquals(original.get(DataComponents.CUSTOM_DATA), view.book.get(DataComponents.CUSTOM_DATA));
        assertEquals(sourceSave, running.write());

        var target = install(document(1)); finish(target); var targetSave = target.write();
        var draft = new ProgramWorkspace(retrieved);
        assertEquals(source, draft.snapshot()); assertEquals(targetSave, target.write(), "Reading only creates a draft");
        target.reset(LIMITS, true);
        var flow = draft.snapshot().flow().orElseThrow();
        target.installFlow(flow.program(draft.snapshot().archive()), flow.initial(), LIMITS, true);
        assertTrue(target.flowSnapshot().orElseThrow().state().paused());
        assertEquals(0, target.flowSnapshot().orElseThrow().state().work());
        var installed = target.write();
        assertEquals("ok", session.mutate(session.revision(), false, "flow", new LibraryEntry(
            LibraryEntry.Kind.PROGRAM, ProgramDocumentCodec.encode(document(3)))));
        assertEquals("ok", session.mutate(session.revision(), true, "flow", null)); view.present = false;
        assertEquals("unloaded", assertThrows(LibraryBookAccess.Failure.class, session::refresh).code());
        assertEquals(installed, target.write()); finish(target);
        assertEquals(new DataValue.IntegerValue(2), target.flowSnapshot().orElseThrow().state().locals().field("count"));
    }

    private static ProgramDocument document(long limit) {
        var key = new ModuleDefinition.Key("at_limit", 3);
        Map<String, DataType> types = Map.of("count", DataType.Scalar.INTEGER, "limit", DataType.Scalar.INTEGER);
        var condition = new ComputationGraph(types, Map.of("count", new ComputationGraph.Input("count"),
            "limit", new ComputationGraph.Input("limit"), "equal", new ComputationGraph.Binary(
                ComputationGraph.BinaryOp.EQUAL_INTEGER, "count", "limit")), Map.of("done", "equal"));
        var predicate = new ComputationGraph(types, Map.of("count", new ComputationGraph.Input("count"),
            "limit", new ComputationGraph.Input("limit"), "call", new ComputationGraph.Call(key, Map.of("count", "count", "limit", "limit")),
            "done", new ComputationGraph.Field("call", "done")), Map.of("done", "done"));
        var increment = new ComputationGraph(Map.of("count", DataType.Scalar.INTEGER), Map.of("count", new ComputationGraph.Input("count"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)), "sum", new ComputationGraph.Binary(
                ComputationGraph.BinaryOp.ADD_INTEGER, "count", "one")), Map.of("count", "sum"));
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var archive = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(), Map.of(
            "predicate", new ProgramDefinition.Entry(predicate, List.of(), List.of()),
            "increment", new ProgramDefinition.Entry(increment, List.of(), List.of()))), Map.of(key, new ModuleDefinition(key, condition)));
        var body = new FlowDocumentBody(Map.of("count", DataType.Scalar.INTEGER, "limit", DataType.Scalar.INTEGER, "ticks", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Until("until", new FlowInput.Expression("predicate", "done")),
                new FlowProgram.Evaluate("add", "increment"), new FlowProgram.EndUntil("end_until"),
                new FlowProgram.Repeat("repeat", new FlowInput.Literal(new DataValue.IntegerValue(2))),
                new FlowProgram.Sleep("sleep", "ticks"), new FlowProgram.EndRepeat("end_repeat")),
            new DataValue.RecordValue(Map.of("count", new DataValue.IntegerValue(0), "limit", new DataValue.IntegerValue(limit), "ticks", new DataValue.IntegerValue(1))));
        return new ProgramWorkspace(new ProgramDocument(archive, Map.of(ProgramDocument.graphId(key), Map.of("equal", new ProgramDocument.Position(14, 28)),
            ProgramDocument.entryGraphId("predicate"), Map.of("call", new ProgramDocument.Position(56, 84))), Optional.empty(), Optional.of(body))).snapshot();
    }
    private static ComputerBlockHost install(ProgramDocument document) {
        var host = new ComputerBlockHost(() -> {}); var body = document.flow().orElseThrow();
        host.installFlow(body.program(document.archive()), body.initial(), LIMITS, true); return host;
    }
    private static void finish(ComputerBlockHost host) {
        host.setPaused(false, LIMITS, true);
        for (int i = 0; i < 20 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) host.tick(LIMITS, true);
        assertEquals(FlowRuntime.Status.COMPLETE, host.flowSnapshot().orElseThrow().state().status());
    }
}
