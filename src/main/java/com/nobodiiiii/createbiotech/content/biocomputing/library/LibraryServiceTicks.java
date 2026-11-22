package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteBatch;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-thread only. Weak ownership plus explicit unload removal; no offline/chunk catch-up. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class LibraryServiceTicks {
    private LibraryServiceTicks() {}
    private static final Map<MinecraftServer, UUID> EPOCHS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Set<LibraryServiceBehaviour>> SERVICES = new WeakHashMap<>();
    static LibraryWriteBatch register(MinecraftServer server, LibraryServiceBehaviour service) {
        SERVICES.computeIfAbsent(server, ignored -> Collections.newSetFromMap(new WeakHashMap<>())).add(service);
        return stamp(server);
    }
    static void forget(LibraryServiceBehaviour service) { SERVICES.values().forEach(services -> services.remove(service)); }
    public static LibraryWriteBatch stamp(MinecraftServer server) {
        return new LibraryWriteBatch(EPOCHS.computeIfAbsent(server, ignored -> UUID.randomUUID()), server.overworld().getGameTime());
    }
    @SubscribeEvent public static void endTick(ServerTickEvent.Post event) {
        var server = event.getServer(); var services = SERVICES.get(server);
        if (services == null) return;
        var now = stamp(server);
        // One valid connected Library has one core (CORE_CONFLICT otherwise). All contenders share its FIFO.
        for (var service : List.copyOf(services)) service.endTick(server, now);
    }
}
