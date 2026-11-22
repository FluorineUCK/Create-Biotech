package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TerminalDocumentTest {
    private static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 1000000);
    @Test void flowPresentationUsesItsOwnDocumentVersionAndCannotBeSmuggledIntoOlderSchemas() {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        workspace.setTerminal(Optional.of(new TerminalDefinition("text", Map.of("text",
            new TerminalDefinition.Text(new TerminalBinding.Path(new ValuePath("output", List.of())))))));
        var document = workspace.snapshot();
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(13), encoded.field("version"));
        assertEquals(document, new ProgramWorkspace(ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, LIMITS), LIMITS)).snapshot());
        for (int oldVersion = 3; oldVersion <= 12; oldVersion++) {
            var old = new LinkedHashMap<>(encoded.fields()); old.put("version", new DataValue.IntegerValue(oldVersion));
            assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
                () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(old))).code());
        }
        workspace.setTerminal(Optional.empty());
        assertEquals(new DataValue.IntegerValue(3), ProgramDocumentCodec.encode(workspace.snapshot()).field("version"),
            "Do not bulk-upgrade old Flow documents without a presentation");
    }
    @Test void independentVersionOneDocumentRemainsValidWithoutTerminal() {
        var archive = workspace().snapshot().archive();
        var old = new DataValue.RecordValue(Map.of("version", new DataValue.IntegerValue(1),
            "program", ProgramArchiveCodec.encode(archive), "layouts", new DataValue.ListValue(List.of())));
        assertTrue(ProgramDocumentCodec.decode(old).terminal().isEmpty());
        var wrong = new LinkedHashMap<>(old.fields()); wrong.put("terminal", DataValue.NullValue.INSTANCE);
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(wrong))).code());
    }
    @Test void allNodesSurviveDocumentWireAndWorkspaceModuleEntryRoundTrips() {
        var workspace = workspace();
        var module = workspace.createModule("pure");
        var moduleDraft = workspace.graph(module); String moduleValue = moduleDraft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(2)), 4, 5); moduleDraft.output("out", moduleValue);
        var entry = workspace.createEntry("message"); String entryValue = entry.add(new ComputationGraph.Constant(new DataValue.IntegerValue(3)), 6, 7); entry.output("out", entryValue);
        var draft = new TerminalDraft(TerminalDefinition.empty());
        for (var kind : TerminalDraft.Kind.values()) draft.add("root", kind);
        workspace.setTerminal(Optional.of(draft.snapshot()));
        var document = workspace.snapshot();
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(2), encoded.field("version"));
        var reopened = new ProgramWorkspace(ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, LIMITS), LIMITS));
        assertEquals(document, reopened.snapshot());
        assertEquals(List.of(module), reopened.keys()); assertEquals(List.of("message"), reopened.entryNames());
        reopened.setTerminal(Optional.empty()); assertTrue(reopened.snapshot().terminal().isEmpty());
        assertTrue(document.terminal().isPresent());
        assertEquals(DataValue.NullValue.INSTANCE, ProgramDocumentCodec.encode(reopened.snapshot()).field("terminal"));
    }
    @Test void currentSchemaIsClosedAndFutureVersionIsDistinguished() {
        var encoded = ProgramDocumentCodec.encode(workspace().snapshot());
        var extra = new LinkedHashMap<>(encoded.fields()); extra.put("unknown", DataValue.NullValue.INSTANCE);
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(extra))).code());
        var future = new LinkedHashMap<>(encoded.fields()); future.put("version", new DataValue.IntegerValue(3));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Flow schema requires its control and initial body");
        future.put("version", new DataValue.IntegerValue(4));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Branch schema also requires its flow body");
        future.put("version", new DataValue.IntegerValue(5));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Sleep schema also requires its flow body");
        future.put("version", new DataValue.IntegerValue(6));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Signal schema also requires its flow body");
        future.put("version", new DataValue.IntegerValue(7));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Loop schema also requires its flow body");
        future.put("version", new DataValue.IntegerValue(8));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Counted schema also requires its flow body");
        future.put("version", new DataValue.IntegerValue(9));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Input slot schema also requires its flow body");
        future.put("version", new DataValue.IntegerValue(13));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Flow presentation also requires its flow body");
        future.put("version", new DataValue.IntegerValue(14));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Native receive schema also requires its flow body");
        future.put("version", new DataValue.IntegerValue(15));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Fault receipt schema also requires its flow body");
        future.put("version", new DataValue.IntegerValue(24));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(), "Public variable schema requires an ISA program body");
        for (int version : new int[]{25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,45,46,47,48,49,51,52,53,54,55,56}) {
            future.put("version", new DataValue.IntegerValue(version));
            assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
                () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(),
                "Supported schema " + version + " still requires its flow body");
        }
        for (int version : new int[]{40,41,42,43,44,50,57}) {
            future.put("version", new DataValue.IntegerValue(version));
            assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION, assertThrows(ArchiveException.class,
                () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future))).code(),
                "Unmerged/future schema " + version + " must not be guessed");
        }
    }
    private static ProgramWorkspace workspace() {
        var workspace = new ProgramWorkspace();
        String value = workspace.root().add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 1, 2);
        workspace.root().output("out", value); return workspace;
    }
}
