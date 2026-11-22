package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScratchVariableRefactorTest {
    @Test void renamingEachScopeUpdatesOnlyItsOwnMonitorsAndUsedNamesCannotBeDeleted() {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        workspace.root().putCell("counter", DataType.Scalar.INTEGER, new DataValue.IntegerValue(0));
        var draft = new FlowDraft(workspace.flow().orElseThrow());
        draft.initial(new DataValue.RecordValue(Map.of("counter", new DataValue.IntegerValue(5))));
        draft.append(new FlowProgram.Monitor("public_watch", "counter", true));
        draft.append(new FlowProgram.Monitor("private_watch", "counter", true, true));
        workspace.setFlow(Optional.of(draft.snapshot()));
        var source = workspace.snapshot();
        var pub = ScratchPublicVariables.edit(source, "counter", "total", new DataValue.IntegerValue(0));
        assertEquals(List.of("total", "counter"), names(pub));
        var local = ScratchPrivateVariables.edit(pub, "counter", "current", new DataValue.IntegerValue(5));
        assertEquals(List.of("total", "current"), names(local));
        assertThrows(IllegalArgumentException.class, () -> ScratchPublicVariables.remove(local, "total"));
        assertThrows(IllegalArgumentException.class, () -> ScratchPrivateVariables.remove(local, "current"));
        assertEquals(local, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(local)));
        assertEquals(List.of("counter", "counter"), names(source), "The installed/original document is not mutated");
    }
    private static List<String> names(ProgramDocument document) {
        return document.flow().orElseThrow().steps().stream().map(s -> ((FlowProgram.Monitor)s).cell()).toList();
    }
}
