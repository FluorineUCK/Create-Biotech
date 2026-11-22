package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeLibraryTerminalDocumentTest {
    static ProgramDocument published() throws Exception {
        try (var input = NativeLibraryTerminalDocumentTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/library_terminal_a.bcp")) {
            assertNotNull(input, "Public interactive Library A must be packaged, not just a test-only helper");
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).trim()), NativeLibraryResultTerminalTest.LIMITS);
        }
    }
    @Test void publicInteractiveSourceUsesTheUnchangedPinnedWriterWithoutStartupInvocation() throws Exception {
        if ("1".equals(System.getenv("GENERATE_LIBRARY_TERMINAL"))) {
            var bytes = ProgramDocumentWire.encode(NativeLibraryTerminalDocuments.build(), NativeLibraryResultTerminalTest.LIMITS);
            for (String directory : List.of("src/main/resources/assets/create_biotech/biocomputing/examples/", "examples/biocomputing/"))
                java.nio.file.Files.writeString(java.nio.file.Path.of(directory + "library_terminal_a.bcp"), Base64.getEncoder().encodeToString(bytes) + "\n");
        }
        var doc = published();
        assertEquals(NativeLibraryTerminalDocuments.build(), doc);
        var original = NativeLibraryWriteDocuments.build("a").flow().orElseThrow();
        var body = doc.flow().orElseThrow();
        assertEquals(original.initial(), body.initial());
        doc.validateTerminalTargets();
        assertEquals(1, FlowProcedureTools.definitions(body).size());
        assertEquals(FlowProgram.Execution.REGISTER_V1, body.execution());
        assertEquals(FlowProcedureTools.definitions(original), FlowProcedureTools.definitions(body));
        assertTrue(body.steps().isEmpty(),"Main publishes configuration only: no startup task or device I/O");
        var terminal = doc.terminal().orElseThrow();
        var input = assertInstanceOf(TerminalDefinition.Input.class, terminal.nodes().get("input"));
        assertEquals(new TerminalTarget.Task(new ModuleDefinition.Key("library_write_a", 1)), input.target());
        assertEquals("replacement", input.argument());
        assertEquals(new TerminalBinding.Path(new ValuePath("output", List.of())), input.inputs());
        var repeat=assertInstanceOf(TerminalDefinition.Repeat.class, terminal.nodes().get("tasks"));
        assertEquals(new TerminalBinding.Path(new ValuePath("tasks",List.of())),repeat.items());
        for(String field:List.of("phase","result","failure"))
            assertEquals(new TerminalBinding.Path(new ValuePath("row",List.of()).key(field)),((TerminalDefinition.Text)terminal.nodes().get(field)).value());
        assertEquals(doc, ProgramDocumentWire.decode(ProgramDocumentWire.encode(doc, NativeLibraryResultTerminalTest.LIMITS), NativeLibraryResultTerminalTest.LIMITS));
    }
    @Test void sourceConfigurationAndTerminalRemainOrdinaryEditableRoundtrippableData() throws Exception {
        var doc=published();var workspace=new ProgramWorkspace(doc);var body=doc.flow().orElseThrow();
        var initial=new LinkedHashMap<>(body.initial().fields());initial.put("replacement",new DataValue.TextValue("editable initial configuration"));
        workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(initial),body.canvas(),body.inline(),body.execution())));
        var nodes=new LinkedHashMap<>(doc.terminal().orElseThrow().nodes());nodes.put("hint",new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue("Player-edited label"))));
        workspace.setTerminal(Optional.of(new TerminalDefinition("root",nodes)));
        var edited=workspace.snapshot();assertNotEquals(doc,edited);
        assertEquals(FlowProcedureTools.definitions(body),FlowProcedureTools.definitions(edited.flow().orElseThrow()));
        assertEquals(edited,ProgramDocumentWire.decode(ProgramDocumentWire.encode(edited,NativeLibraryResultTerminalTest.LIMITS),NativeLibraryResultTerminalTest.LIMITS));
    }
}
