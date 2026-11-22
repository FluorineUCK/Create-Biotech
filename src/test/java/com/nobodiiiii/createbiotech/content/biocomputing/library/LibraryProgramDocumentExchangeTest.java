package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import java.io.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.*;

/** Physical-book session and byte persistence with a live, independently owned Computer runtime. */
class LibraryProgramDocumentExchangeTest {
    private static final ResourceLimits HARDWARE = new ResourceLimits(1000, 100000, 1000);
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }

    @Test void programAndCompleteSubgraphTravelInTheBookWithoutCapturingRamOrInstallingOnRead() throws Exception {
        var source = document(7);
        var runtime = new ComputerRuntime(UUID.randomUUID(), source.archive(), HARDWARE);
        run(runtime, 7); assertEquals(new DataValue.IntegerValue(7), runtime.memory().field("counter"));
        var before = runtime.snapshot();
        var workspace = new ProgramWorkspace(source);
        var subgraph = workspace.exportSubgraph(workspace.keys().getFirst(), LibraryBookMediaTest.LIMITS);
        var view = new LibraryBookAccessTest.View(); var original = view.book.copy();
        var session = LibraryBookSessionTest.session(view); session.refresh(); session.select(0);
        assertEquals("ok", session.mutate(session.revision(), false, "application", new LibraryEntry(LibraryEntry.Kind.PROGRAM,
            ProgramDocumentCodec.encode(ProgramDocumentWire.decode(ProgramDocumentWire.encode(source, LibraryBookMediaTest.LIMITS), LibraryBookMediaTest.LIMITS)))));
        assertEquals("ok", session.mutate(session.revision(), false, "module", new LibraryEntry(LibraryEntry.Kind.SUBGRAPH, SubgraphDocumentCodec.encode(subgraph))));

        var bytes = new ByteArrayOutputStream();
        NbtIo.write(view.book.get(LibraryBookMediaTest.COMPONENT).copyTag(), new DataOutputStream(bytes));
        var loaded = original.copy();
        loaded.set(LibraryBookMediaTest.COMPONENT, CustomData.of(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))));
        view.book = loaded; session = LibraryBookSessionTest.session(view); session.refresh(); session.select(0);
        var retrieved = ProgramDocumentCodec.decode(session.read("application").value());
        var retrievedSubgraph = SubgraphDocumentCodec.decode(session.read("module").value());
        assertEquals(source, retrieved); assertEquals(subgraph, retrievedSubgraph);
        assertEquals(original.get(DataComponents.WRITABLE_BOOK_CONTENT), loaded.get(DataComponents.WRITABLE_BOOK_CONTENT));
        assertEquals(original.get(DataComponents.CUSTOM_DATA), loaded.get(DataComponents.CUSTOM_DATA));
        assertEquals(new DataValue.IntegerValue(3), retrieved.archive().program().cells().get("counter").initial());
        assertEquals(before, runtime.snapshot());

        var target = new ComputerRuntime(UUID.randomUUID(), document(1).archive(), HARDWARE); run(target, 1);
        var targetBefore = target.snapshot();
        var draft = new ProgramWorkspace(retrieved);
        var moduleDraft = new ProgramWorkspace(); moduleDraft.importSubgraph(retrievedSubgraph, LibraryBookMediaTest.LIMITS);
        assertEquals(targetBefore, target.snapshot());
        assertEquals(source, draft.snapshot()); assertEquals(subgraph.modules().keySet(), new HashSet<>(moduleDraft.keys()));
        assertEquals(ComputerRuntime.InstallResult.APPLIED, target.requestInstall(draft.snapshot().archive(), ComputerRuntime.MemoryPolicy.RESET));
        assertEquals(new DataValue.IntegerValue(3), target.memory().field("counter")); run(target, 7);
    }

    @Test void replacementDeletionAndMediumRemovalNeverRetargetAnInstalledProgram() {
        var view = new LibraryBookAccessTest.View(); var session = LibraryBookSessionTest.session(view); session.refresh(); session.select(0);
        assertEquals("ok", session.mutate(session.revision(), false, "application", new LibraryEntry(LibraryEntry.Kind.PROGRAM, ProgramDocumentCodec.encode(document(7)))));
        var retrieved = ProgramDocumentCodec.decode(session.read("application").value());
        var runtime = new ComputerRuntime(UUID.randomUUID(), retrieved.archive(), HARDWARE); run(runtime, 7);
        var before = runtime.snapshot();
        assertEquals("ok", session.mutate(session.revision(), false, "application", new LibraryEntry(LibraryEntry.Kind.PROGRAM, ProgramDocumentCodec.encode(document(9)))));
        assertEquals(document(9), ProgramDocumentCodec.decode(session.read("application").value()));
        assertEquals(before, runtime.snapshot());
        assertEquals("ok", session.mutate(session.revision(), true, "application", null));
        view.present = false;
        assertEquals("unloaded", assertThrows(LibraryBookAccess.Failure.class, () -> session.read("application")).code());
        assertEquals(before, runtime.snapshot()); run(runtime, 7);
    }

    private static ProgramDocument document(long answer) {
        var workspace = new ProgramWorkspace(); var leaf = workspace.createModule("value");
        var graph = workspace.graph(leaf); String value = graph.add(new ComputationGraph.Constant(new DataValue.IntegerValue(answer)), 12, 34); graph.output("answer", value);
        var root = workspace.root(); var call = root.add(new ComputationGraph.Call(leaf, Map.of()), 56, 78);
        var field = root.add(new ComputationGraph.Field(call, "answer"), 90, 12); root.connect(field, "source", call); root.output("result", field);
        root.putCell("counter", DataType.Scalar.INTEGER, new DataValue.IntegerValue(3)); root.setWrite("counter", "result");
        var event = workspace.createEntry("note"); var text = event.add(new ComputationGraph.Constant(new DataValue.TextValue("book")), 22, 44); event.output("text", text);
        var document = workspace.snapshot(); return new ProgramDocument(document.archive(), document.positions(), Optional.of(TerminalDefinition.empty()));
    }
    private static void run(ComputerRuntime runtime, long expected) {
        runtime.begin(new DataValue.RecordValue(Map.of()));
        for (int i = 0; i < 20 && runtime.report().status() == ComputationMachine.Status.RUNNING; i++) runtime.tick();
        assertEquals(ComputationMachine.Status.SUCCEEDED, runtime.report().status());
        assertEquals(new DataValue.IntegerValue(expected), runtime.report().output().orElseThrow().field("result"));
    }
}
