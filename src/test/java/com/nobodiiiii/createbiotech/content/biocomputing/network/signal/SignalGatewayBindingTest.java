package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPortBinding;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class SignalGatewayBindingTest {
    @Test void bindingUsesControllerFaceButNeverImplicitlyEnablesOrFallsBackToLocal() {
        var state = new SignalConnections();
        state.configure(new SignalConnections.Configuration("p0", Direction.EAST, true));
        var route = new ClusterPortBinding(UUID.randomUUID(), new BlockPos(8, 2, 3), Direction.WEST, "status");
        state.configureGateway("p0", Optional.of(route));
        assertEquals(Direction.WEST, state.bindings().get("p0").side());
        assertFalse(state.bindings().get("p0").enabled());
        assertEquals(Optional.of(route), state.bindings().get("p0").gateway());
        var restored = new SignalConnections(); restored.read(state.write());
        assertEquals(state.bindings(), restored.bindings());
        assertThrows(IllegalArgumentException.class, () -> restored.configure(new SignalConnections.Configuration("p0", Direction.EAST, true)));
        restored.configure(new SignalConnections.Configuration("p0", Direction.WEST, true));
        assertEquals(Optional.of(route), restored.bindings().get("p0").gateway());
        restored.configureGateway("p0", Optional.empty());
        assertFalse(restored.bindings().get("p0").enabled());
        assertTrue(restored.bindings().get("p0").gateway().isEmpty());
    }
    @Test void originalVersionOneGrantStaysLocalAndIsNotRewrittenByRead() {
        var port = new CompoundTag(); port.putString("side", "east"); port.putBoolean("enabled", true);
        var ports = new CompoundTag(); ports.put("p0", port);
        var raw = new CompoundTag(); raw.putInt("version", 1); raw.put("ports", ports);
        var state = new SignalConnections(); state.read(raw);
        assertTrue(state.bindings().get("p0").gateway().isEmpty()); assertTrue(state.bindings().get("p0").enabled());
        assertEquals(raw, state.write());
    }
    @Test void malformedGatewayRetainsItsArchiveInsteadOfFallingBackToLocalOutput() {
        var state = new SignalConnections();
        state.configureGateway("p0", Optional.of(new ClusterPortBinding(UUID.randomUUID(), BlockPos.ZERO, Direction.WEST, "status")));
        state.configure(new SignalConnections.Configuration("p0", Direction.WEST, true));
        var raw = (CompoundTag)state.write();
        raw.getCompound("ports").getCompound("p0").getCompound("gateway").putString("Side", "east");
        var loaded = new SignalConnections(); loaded.read(raw);
        assertTrue(loaded.bindings().isEmpty()); assertEquals(raw, loaded.write());
        assertEquals("invalid_configuration", loaded.diagnostic().orElseThrow());
    }
}
