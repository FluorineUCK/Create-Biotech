package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionRuntimeServicesTest {
    @BeforeAll static void bootstrap() throws Exception {
        var loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }

    @Test void eachServerOwnsOneRunningServiceAndCloseCannotStopAnotherServer() throws Exception {
        var a = server(); var b = server();
        try {
            var first = ProductionRuntimeServices.get(a);
            var other = ProductionRuntimeServices.get(b);
            assertSame(first, ProductionRuntimeServices.get(a));
            assertSame(other, ProductionRuntimeServices.get(b));
            assertNotSame(first, other);
            assertNotSame(first.pendingAgentRuns(), other.pendingAgentRuns());
            ProductionRuntimeServices.close(a);
            assertThrows(IllegalStateException.class, first::pendingAgentRuns);
            assertSame(other, ProductionRuntimeServices.get(b));
            assertNotNull(other.pendingAgentRuns());
            ProductionRuntimeServices.close(a);
            var restarted = ProductionRuntimeServices.get(a);
            assertNotSame(first, restarted);
            assertSame(restarted, ProductionRuntimeServices.get(a));
            assertNotNull(restarted.pendingAgentRuns());
            assertSame(other, ProductionRuntimeServices.get(b));
        } finally {
            ProductionRuntimeServices.close(a);
            ProductionRuntimeServices.close(b);
        }
    }

    private static MinecraftServer server() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (MinecraftServer)((sun.misc.Unsafe)field.get(null)).allocateInstance(DedicatedServer.class);
    }
}
