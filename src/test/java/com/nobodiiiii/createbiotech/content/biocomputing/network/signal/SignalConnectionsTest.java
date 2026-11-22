package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class SignalConnectionsTest {
    @Test void defaultsClosedExplicitChoiceSurvivesSaveAndReturnedTagsAreCopies() {
        var source = new SignalConnections(); assertTrue(source.bindings().isEmpty()); assertNull(source.write());
        var choice = new SignalConnections.Configuration("p1", Direction.WEST, true);
        assertEquals(choice, SignalConnections.Configuration.read(choice.value()));
        source.configure(choice); var stored = source.write();
        var restored = new SignalConnections(); restored.read(stored);
        ((CompoundTag)stored).getCompound("ports").remove("p1");
        assertEquals(source.bindings(), restored.bindings());
        assertEquals(new SignalConnections.Binding(Direction.WEST, true), restored.bindings().get("p1"));
        assertThrows(UnsupportedOperationException.class, () -> restored.bindings().clear());
    }
    @Test void unknownOrMalformedSaveIsRetainedAndNeverActivatedOrSilentlyReplaced() {
        for (int version : new int[] {1, 2}) {
            var raw = new CompoundTag(); raw.putInt("version", version); raw.putString("ports", "not a map");
            var state = new SignalConnections(); state.read(raw);
            assertTrue(state.bindings().isEmpty()); assertEquals("invalid_configuration", state.diagnostic().orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> state.configure(new SignalConnections.Configuration("p0", Direction.EAST, true)));
            assertEquals(raw, state.write()); state.clear();
            assertNull(state.write()); assertTrue(state.diagnostic().isEmpty());
        }
    }
}
