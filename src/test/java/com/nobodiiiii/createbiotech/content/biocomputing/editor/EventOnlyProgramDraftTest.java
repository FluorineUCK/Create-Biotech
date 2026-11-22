package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.gametest.ClusterLibraryPrograms;
import org.junit.jupiter.api.Test;

class EventOnlyProgramDraftTest {
    @Test void emptyManualEntryRoundTripsWhenProgramHasCompleteEventEntries() {
        var archive = ClusterLibraryPrograms.worker();
        assertEquals(archive, new ProgramDraft(archive).snapshot());
        var workspace = new ProgramWorkspace(ProgramDocument.of(archive));
        assertEquals(archive, workspace.snapshot().archive());
    }
    @Test void emptyApplicationAndUnfinishedRootAreStillRejected() {
        assertThrows(IllegalStateException.class, () -> new ProgramWorkspace().snapshot());
        assertThrows(IllegalStateException.class, () -> new ProgramDraft().snapshot());
        var workspace = new ProgramWorkspace(ProgramDocument.of(ClusterLibraryPrograms.worker()));
        workspace.root().add(new ComputationGraph.Constant(DataValue.NullValue.INSTANCE), 0, 0);
        assertThrows(IllegalStateException.class, workspace::snapshot);
    }
    @Test void emptyEventIsNotMistakenForAnIntentionallyEmptyManualEntry() {
        var workspace = new ProgramWorkspace(ProgramDocument.of(ClusterLibraryPrograms.worker()));
        workspace.createEntry("unfinished");
        assertThrows(IllegalStateException.class, workspace::snapshot);
    }
}
