package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

/**
 * Server lifecycle owner of the production application, never Computer RAM or pooled hardware.
 * The engine/configuration are still migration dependencies; physical Computers do not own this registry.
 * Weak keys are only retention hygiene. The actual server-stop hook must close the service explicitly.
 */
public final class ProductionRuntimeServices {
    private static final Map<MinecraftServer, FactoryRuntimeService> SERVICES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private ProductionRuntimeServices() {}

    public static FactoryRuntimeService get(MinecraftServer server) {
        synchronized (SERVICES) {
            return SERVICES.computeIfAbsent(server, ignored -> {
                var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig());
                service.start();
                return service;
            });
        }
    }

    /** Remove the exact registered service before teardown; never create a worker just to stop it. */
    public static void close(MinecraftServer server) {
        FactoryRuntimeService service;
        synchronized (SERVICES) { service = SERVICES.remove(server); }
        if (service != null) service.close();
    }
}
