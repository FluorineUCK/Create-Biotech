package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.signal.*;
import java.util.*;
import net.minecraft.core.*;
import org.junit.jupiter.api.*;

class SignalGatewayAccessTest {
    @BeforeAll static void initialize() throws Exception { bootstrap(); }
    @Test void controllerMustActuallyTickAndUnloadedResolutionDoesNotFetchAnEntity() throws Exception {
        var f = fixture(); var saved = f.computer().snapshot();
        var binding = new SignalConnections.Binding(Direction.WEST, true, Optional.of(new ClusterPortBinding(f.controller().controllerId(), CONTROLLER, Direction.WEST, "status")));
        assertEquals("gateway_unavailable", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> SignalGatewayAccess.resolve(f.world(), MEMBER, "p0", binding)).code());
        f.controller().tick();
        var route = SignalGatewayAccess.resolve(f.world(), MEMBER, "p0", binding);
        assertEquals(new CableDiscovery.Connection(CONTROLLER, Direction.WEST), route.connector());
        assertTrue(route.dependencies().containsAll(f.controller().enclosure().enclosure().orElseThrow().shell()));
        f.world().clock++;
        assertEquals("gateway_unavailable", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> SignalGatewayAccess.resolve(f.world(), MEMBER, "p0", binding)).code());
        f.world().chunks.unavailable = true; int before = f.world().requestingEntityReads;
        assertEquals("gateway_unloaded", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> SignalGatewayAccess.resolve(f.world(), MEMBER, "p0", binding)).code());
        assertEquals(before, f.world().requestingEntityReads, "An unloaded gateway is never force-requested");
        assertEquals(saved, f.computer().snapshot());
    }
    @Test void changedControllerDirectionAndNonMemberDoNotFallBackToLocalFace() throws Exception {
        var f = fixture(); f.controller().tick();
        var wrongIdentity = new SignalConnections.Binding(Direction.WEST, true, Optional.of(new ClusterPortBinding(UUID.randomUUID(), CONTROLLER, Direction.WEST, "status")));
        assertEquals("gateway_changed", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> SignalGatewayAccess.resolve(f.world(), MEMBER, "p0", wrongIdentity)).code());
        var wrongFace = new SignalConnections.Binding(Direction.EAST, true, Optional.of(new ClusterPortBinding(f.controller().controllerId(), CONTROLLER, Direction.EAST, "status")));
        assertEquals("gateway_changed", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> SignalGatewayAccess.resolve(f.world(), MEMBER, "p0", wrongFace)).code());
        var correct = new SignalConnections.Binding(Direction.WEST, true, Optional.of(new ClusterPortBinding(f.controller().controllerId(), CONTROLLER, Direction.WEST, "status")));
        assertEquals("gateway_not_member", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> SignalGatewayAccess.resolve(f.world(), CONTROLLER.west(2), "p0", correct)).code());
    }
}
