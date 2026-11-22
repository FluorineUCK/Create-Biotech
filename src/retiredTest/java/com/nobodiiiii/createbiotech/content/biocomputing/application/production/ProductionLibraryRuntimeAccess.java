package com.nobodiiiii.createbiotech.content.biocomputing.application.production;


import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibrarySession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer;

/** Application runtime access, independent of the physical Library implementation.
 * The remaining FactoryOrchestrationServer is a migration dependency, not the storage backend.
 * No Library policy, index, generic entries or reply budget lives here. */
public final class ProductionLibraryRuntimeAccess implements ProductionLibrarySession.Backend {
    private final Supplier<MinecraftServer> server;

    public ProductionLibraryRuntimeAccess(Supplier<MinecraftServer> server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override public boolean publish(ProductionBinding binding, ProductionRecipeCatalog catalog,
        CommunicationFrequency frequency, int priority, RecipeSourceLocation address) {
        var server = this.server.get();
        return server != null && FactoryOrchestrationServer.publishProductionLibrary(server, catalog.libraryId(),
            binding.groupId(), Set.copyOf(binding.logisticsIds()), binding.revision(), frequency, priority,
            new SpaceAddress(address.dimension(), address.subLevelId(), address.localPos()), catalog.loaded(), catalog);
    }

    @Override public boolean enqueue(UUID group, PatternQuery query) {
        var server = this.server.get();
        return server != null && FactoryOrchestrationServer.enqueueLegacyLibraryQuery(server, group, query);
    }

    @Override public List<PatternReply> drain(UUID group, UUID requester, int maximum) {
        var server = this.server.get();
        return server == null ? List.of() : FactoryOrchestrationServer.drainLegacyLibraryReplies(server, group, requester, maximum);
    }

    @Override public void topologyChanged(UUID group) {
        var server = this.server.get();
        if (server != null) FactoryOrchestrationServer.productionLibraryTopologyChanged(server, group);
    }

    @Override public ProductionLibrarySession.Workload workload(UUID group) {
        var server = this.server.get();
        if (server == null) return ProductionLibrarySession.Workload.EMPTY;
        var value = FactoryOrchestrationServer.patternLibraryWorkload(server, group);
        return new ProductionLibrarySession.Workload(value.activeQueries(), value.readyReplies());
    }

    @Override public PatternPriorityAuthority coordination(UUID group) {
        var server = this.server.get();
        return server == null ? PatternPriorityAuthority.empty() : FactoryOrchestrationServer.patternBrowserCoordination(server, group);
    }
}
