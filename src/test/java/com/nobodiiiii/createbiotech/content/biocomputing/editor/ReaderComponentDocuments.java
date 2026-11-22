package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Offline resource authoring; runs neither under Minecraft nor as a driver service. */
class ReaderComponentDocuments {
    static final String FILE = "reader_sample_component_a";
    private static final Path DOCS = Path.of("examples/biocomputing");
    private static final Path PACKAGED = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples");

    static ProgramDocument build(String assembly) {
        var key = new ModuleDefinition.Key("read_component", 1);
        var target = record("x", 0, "y", 0, "z", 0);
        var targetType = DataType.typeOf(target);
        var inputs = Map.<String, DataType>of("target", targetType, "component", TEXT);
        var outputs = Map.<String, DataType>of("ok", BOOLEAN, "reply", ANY);
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.initial(record("target", target));
        var definition = new FlowProgram.Procedure("sample_definition", key, inputs, outputs, Map.of("ok", "flag", "reply", "reply"),
            FlowBlockRules.NONE, Optional.of(new AssemblySource(assembly)));
        FlowProcedureTools.define(draft, definition, false);
        var graph = new ComputationGraph(Map.of("target", targetType),
            Map.of("source", new ComputationGraph.Input("target"),
                "choice", new ComputationGraph.Constant(new DataValue.TextValue("create_biotech:redstone"))),
            Map.of("target", "source", "component", "choice"));
        var call = new FlowProgram.Invoke("sample_once", key, new FlowCallBindings(Map.of(), Map.of("ok", "ok", "reply", "reply")),
            Optional.of(new FlowProgram.InvokeOperands("sample_once", Map.of("target", "target"))));
        draft.place(List.of(call), new FlowDraft.Socket(null, 0), new ProgramDocument.Position(16, 16),
            Map.of(call.sourceId(), graph));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("ok", "reply")));
        return workspace.snapshot();
    }

    public static void main(String[] args) throws Exception {
        var source = Files.readString(DOCS.resolve(FILE + ".asm")).replace("\r\n", "\n");
        var document = build(source);
        document.validate(ReaderDriverDocumentTest.HARDWARE);
        var encoded = ProgramDocumentWire.encode(document, ReaderDriverDocumentTest.ARCHIVE);
        var text = Base64.getEncoder().encodeToString(encoded) + "\n";
        for (var location : List.of(DOCS, PACKAGED)) Files.writeString(location.resolve(FILE + ".bcp"), text);
    }
}
