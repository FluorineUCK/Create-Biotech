package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;

/** Maintainer authoring only: one ordinary editable public document, no runtime installation hook. */
public final class NativeLibraryTerminalDocuments {
    private NativeLibraryTerminalDocuments() {}
    public static ProgramDocument build() {
        var workspace = new ProgramWorkspace(NativeLibraryWriteDocuments.build("a"));
        var body = workspace.flow().orElseThrow();
        // Main only exposes the configured argument record on completion. The exact writer remains pinned and skipped.
        workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),
            body.steps().stream().filter(s -> !(s instanceof FlowProgram.Invoke)).toList(), body.initial(), body.canvas(), body.inline(), body.execution())));
        var nodes = new LinkedHashMap<String, TerminalDefinition.Node>();
        nodes.put("root", new TerminalDefinition.Column(List.of("hint", "input", "tasks")));
        nodes.put("hint", new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue(
            "One operation at a time. Submit literal text; refresh Tasks, inspect result/error, then explicitly claim. Unknown reply: inspect/recover, never implicit retry."))));
        nodes.put("input", new TerminalDefinition.Input(new TerminalBinding.Literal(new DataValue.TextValue("")),
            new TerminalBinding.Literal(new DataValue.BooleanValue(true)),
            new TerminalTarget.Task(new ModuleDefinition.Key("library_write_a", 1)), "replacement", path("output")));
        nodes.put("tasks", new TerminalDefinition.Repeat(path("tasks"), "task"));
        nodes.put("task", new TerminalDefinition.Column(List.of("phase", "result", "failure")));
        for (String field : List.of("phase", "result", "failure"))
            nodes.put(field, new TerminalDefinition.Text(path("row", field)));
        workspace.setTerminal(Optional.of(new TerminalDefinition("root", nodes)));
        return workspace.snapshot();
    }
    private static TerminalBinding.Path path(String root, String... fields) {
        var path = new ValuePath(root, List.of());
        for (String field : fields) path = path.key(field);
        return new TerminalBinding.Path(path);
    }
}
