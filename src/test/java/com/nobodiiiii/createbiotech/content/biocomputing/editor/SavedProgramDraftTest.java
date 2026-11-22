package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SavedProgramDraftTest {
    @Test void persistsEditableSourceAndRejectsConcurrentOrInvalidReplacement() {
        var workspace = new ProgramWorkspace();
        // A structurally editable but not installable program must still be saveable.
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),
            List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Sleep("wait", "not_declared_yet")),
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Execution.REGISTER_V1)));
        var document = workspace.snapshot(); var limits = SubgraphWorkspaceTest.LIMITS;
        byte[] bytes = ProgramDocumentWire.encode(document, limits);
        var store = new SavedProgramDraft();
        assertTrue(store.save(0, bytes, limits));
        var restored = new SavedProgramDraft(); restored.read(store.write());
        assertEquals(1, restored.snapshot(limits).revision());
        assertEquals(document, ProgramDocumentWire.decode(restored.snapshot(limits).document(), limits));
        assertFalse(restored.save(0, bytes, limits), "A second editor cannot overwrite a newer saved draft");
        var before = restored.write();
        assertThrows(IllegalArgumentException.class, () -> restored.save(1, new byte[]{1}, limits));
        assertEquals(before, restored.write(), "Failed saves must keep the previous draft intact");
        var corrupt = new net.minecraft.nbt.CompoundTag(); corrupt.putInt("Version", 99);
        restored.read(corrupt);
        assertThrows(IllegalArgumentException.class, () -> restored.save(0, bytes, limits));
        assertEquals(corrupt, restored.write(), "An unreadable draft must not silently become an empty writable slot");
    }
}
