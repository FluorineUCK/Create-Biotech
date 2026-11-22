package com.nobodiiiii.createbiotech.content.biocomputing.application.production.process;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductionProcessModelTest {
    @Test void cannotAcceptMoreChildrenThanThePersistedFormatCanRestore() {
        var parent = parent();
        for (int i = 0; i < 256; i++) parent = parent.acceptChildRevision(child(i, 0)).parent();
        var full = parent;
        assertThrows(IllegalArgumentException.class, () -> full.acceptChildRevision(child(256, 0)));
        assertEquals(256, full.childRevisionStates().size());
        var updated = full.acceptChildRevision(child(0, 1));
        assertEquals(256, updated.parent().childRevisionStates().size());
        assertEquals(0, full.childRevisions().get(id(0)).revision());
        assertEquals(1, updated.parent().childRevisions().get(id(0)).revision());
    }

    @Test void newerChildRevisionRevokesPriorDurableAcknowledgementWithoutMutatingParent() {
        var accepted = parent().acceptChildRevision(child(0, 0));
        assertThrows(IllegalStateException.class, () -> accepted.parent().authorizeIrreversibleChildBoundary(accepted.acknowledgement()));
        var durable = accepted.parent().markChildAcknowledgementDurable(accepted.acknowledgement());
        assertEquals(durable.processId(), durable.authorizeIrreversibleChildBoundary(accepted.acknowledgement()).parentProcessId());
        var next = durable.acceptChildRevision(child(0, 1));
        assertThrows(IllegalArgumentException.class, () -> next.parent().authorizeIrreversibleChildBoundary(accepted.acknowledgement()));
        assertThrows(IllegalStateException.class, () -> next.parent().authorizeIrreversibleChildBoundary(next.acknowledgement()));
        assertTrue(durable.isChildAcknowledgedDurably(id(0), 0, 0));
        assertFalse(next.parent().isChildAcknowledgedDurably(id(0), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> next.parent().markChildAcknowledgementDurable(accepted.acknowledgement()));
        assertThrows(UnsupportedOperationException.class, () -> next.parent().childRevisionStates().clear());
    }

    static ProcessRecord parent() {
        var profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
        return new ProcessRecord(id(1000), id(1001), id(1002), 0, profile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
    }
    static ChildExecutionRevision child(int index, long revision) {
        return new ChildExecutionRevision(id(index), 0, revision, InputMode.BULK, InputMode.PARTIAL, 8, 3, 5);
    }
    static UUID id(long number) { return new UUID(0, number); }
}
