package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.Map;
import java.util.ArrayList;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import net.minecraft.world.item.Items;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionLibraryAttachment;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageIndex;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class ProductionLibrarySessionTest {
    private static final UUID LIBRARY = new UUID(0, 1), GROUP = new UUID(0, 2), NETWORK = new UUID(0, 3);
    private static final RecipeSourceLocation SOURCE = new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO);
    private static final CommunicationFrequency FREQUENCY = CommunicationFrequency.of("production");

    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        Class<?> loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true); flag.setBoolean(null, true);
        Items.AIR.getDefaultInstance();
    }

    @Test void queriesRequireCurrentValidBindingAuthorizationAndNetworkMembership() {
        var attachment = attachment(); var backend = new RecordingBackend();
        var session = new ProductionLibrarySession(attachment, backend); var query = query(GROUP, NETWORK);
        assertFalse(session.enqueue(false, query)); assertNull(backend.query);
        assertFalse(session.enqueue(true, query(GROUP, LIBRARY))); assertNull(backend.query);
        assertTrue(session.enqueue(true, query)); assertEquals(query, backend.query); assertEquals(GROUP, backend.group);
        backend.query = null;
        attachment.restore(new ProductionLibraryAttachment.Loaded(attachment.binding(), false, false));
        assertFalse(session.enqueue(true, query)); assertNull(backend.query);
        attachment.commit(null); assertFalse(session.enqueue(true, query)); assertNull(backend.query);
    }

    @Test void replyBudgetIsSharedAcrossRequestersAndCannotBeRefilledWithinTheTick() {
        var backend = new RecordingBackend(); var session = new ProductionLibrarySession(attachment(), backend);
        var first = PatternReply.groupEnd(query(GROUP, NETWORK), 0);
        var second = PatternReply.groupEnd(query(LIBRARY, NETWORK), 0);
        backend.ready.addAll(List.of(first, first, second, second));
        assertTrue(session.drain(40, 2, NETWORK, 99).isEmpty(), "No matching reply consumes no allowance");
        assertEquals(List.of(first), session.drain(40, 2, GROUP, 1));
        assertEquals(List.of(second), session.drain(40, 200, LIBRARY, 99), "Changing capacity cannot refill this tick");
        assertTrue(session.drain(40, 200, GROUP, 99).isEmpty());
        assertEquals(List.of(first), session.drain(41, 1, GROUP, 99));
        assertTrue(session.drain(41, 1, LIBRARY, 1).isEmpty());
        assertTrue(session.drain(42, 3, LIBRARY, -1).isEmpty());
        assertEquals(List.of(second), session.drain(42, 3, LIBRARY, 3));
        assertTrue(backend.ready.isEmpty());
    }

    @Test void topologyChangesRedriveOldThenNewGroupWithoutInventingALibraryEndpoint() {
        var attachment = attachment(); var backend = new RecordingBackend();
        var session = new ProductionLibrarySession(attachment, backend);
        session.topologyChanged(); assertEquals(List.of(GROUP), backend.topology);
        backend.topology.clear(); session.topologyRebound(GROUP); assertEquals(List.of(GROUP), backend.topology);
        backend.topology.clear(); session.topologyRebound(NETWORK); assertEquals(List.of(NETWORK, GROUP), backend.topology);
        backend.topology.clear(); attachment.commit(null);
        session.topologyChanged(); assertTrue(backend.topology.isEmpty());
        session.topologyRebound(GROUP); assertEquals(List.of(GROUP), backend.topology);
        backend.topology.clear(); session.topologyRebound(null); assertTrue(backend.topology.isEmpty());
    }

    @Test void unavailableAndInvalidAttachmentsWithdrawCatalogsWhileUnboundDoesNotPublish() {
        var attachment = attachment(); var backend = new RecordingBackend();
        var session = new ProductionLibrarySession(attachment, backend); var index = new RecipePageIndex();
        assertTrue(session.publish(LIBRARY, index, false, FREQUENCY, 0, SOURCE));
        assertFalse(backend.catalog.loaded());
        attachment.restore(new ProductionLibraryAttachment.Loaded(attachment.binding(), false, false));
        assertTrue(session.publish(LIBRARY, index, true, FREQUENCY, 0, SOURCE)); assertFalse(backend.catalog.loaded());
        var previous = backend.catalog; attachment.commit(null);
        assertFalse(session.publish(LIBRARY, index, true, FREQUENCY, 0, SOURCE)); assertSame(previous, backend.catalog);
    }

    @Test void failedPublicationIsNotHiddenOrMemoizedAndCanBeRetried() {
        var backend = new RecordingBackend() {
            boolean fail = true;
            @Override public boolean publish(ProductionBinding binding, ProductionRecipeCatalog catalog,
                CommunicationFrequency frequency, int priority, RecipeSourceLocation address) {
                if (fail) { fail = false; throw new IllegalStateException("journal unavailable"); }
                return super.publish(binding, catalog, frequency, priority, address);
            }
        };
        var session = new ProductionLibrarySession(attachment(), backend); var index = new RecipePageIndex();
        assertThrows(IllegalStateException.class, () -> session.publish(LIBRARY, index, true, FREQUENCY, 0, SOURCE));
        assertNull(backend.catalog);
        assertTrue(session.publish(LIBRARY, index, true, FREQUENCY, 0, SOURCE)); assertTrue(backend.catalog.loaded());
    }

    @Test void diagnosticsUseCurrentBindingWithoutQueryingOrPublishingAndInvalidStateDeniesReads() {
        var attachment = attachment(); var backend = new RecordingBackend();
        var session = new ProductionLibrarySession(attachment, backend);
        assertEquals(new ProductionLibrarySession.Workload(3, 4), session.workload());
        session.coordination(false); assertEquals(0, backend.coordinationReads);
        session.coordination(true); assertEquals(1, backend.coordinationReads);
        assertNull(backend.catalog); assertNull(backend.query); assertTrue(backend.ready.isEmpty());
        attachment.restore(new ProductionLibraryAttachment.Loaded(attachment.binding(), false, false));
        assertEquals(ProductionLibrarySession.Workload.EMPTY, session.workload());
        session.coordination(true); assertEquals(1, backend.coordinationReads); assertEquals(1, backend.workloadReads);
        attachment.commit(null); assertEquals(ProductionLibrarySession.Workload.EMPTY, session.workload());
        session.coordination(true); assertEquals(1, backend.coordinationReads);
    }

    @Test void rebindingCallbackCannotLeaveNotificationPointingAtACapturedOldAttachment() {
        var attachment = attachment();
        var backend = new RecordingBackend() {
            @Override public void topologyChanged(UUID group) {
                super.topologyChanged(group);
                if (group.equals(NETWORK)) attachment.commit(new ProductionBinding(LIBRARY, 10, null, List.of()));
            }
        };
        new ProductionLibrarySession(attachment, backend).topologyRebound(NETWORK);
        assertEquals(List.of(NETWORK, LIBRARY), backend.topology);
    }

    private static PatternQuery query(UUID requester, UUID network) {
        return new PatternQuery(UUID.randomUUID(), requester, network, LIBRARY,
            new StackKey(Items.PAPER.getDefaultInstance()), 0, 0, 1);
    }

    @Test void publicationUsesLiveGenerationAndNeverMakesAnIncompleteIndexQueryable() {
        var attachment = attachment(); var backend = new RecordingBackend();
        var session = new ProductionLibrarySession(attachment, backend); var index = new RecipePageIndex();
        assertTrue(session.publish(LIBRARY, index, true, FREQUENCY, 7, SOURCE));
        assertTrue(backend.catalog.loaded()); assertEquals(9, backend.catalog.formationGeneration());
        var first = backend.catalog;
        index.rebuildPageOrder(List.of(new PatternPageKey(SOURCE, 0, 0)));
        assertTrue(session.publish(LIBRARY, index, true, FREQUENCY, 7, SOURCE));
        assertFalse(backend.catalog.loaded()); assertTrue(backend.catalog.patterns().isEmpty());
        assertEquals(index.generation(), backend.catalog.catalogGeneration());
        assertNotEquals(first.catalogGeneration(), backend.catalog.catalogGeneration());
        assertEquals(attachment.binding(), backend.binding); assertEquals(FREQUENCY, backend.frequency);
        assertEquals(7, backend.priority); assertEquals(SOURCE, backend.address);
        assertTrue(first.loaded(), "published values are independent of later index maintenance");
    }

    private static ProductionLibraryAttachment attachment() {
        var result = new ProductionLibraryAttachment();
        result.commit(new ProductionBinding(GROUP, 9, new ProductionBinding.Authority(
            ProductionBinding.Role.LIBRARY, LIBRARY), List.of(NETWORK)));
        return result;
    }

    private static class RecordingBackend implements ProductionLibrarySession.Backend {
        ProductionBinding binding; ProductionRecipeCatalog catalog; CommunicationFrequency frequency;
        int priority; RecipeSourceLocation address;
        UUID group; PatternQuery query;
        final List<PatternReply> ready = new ArrayList<>();
        final List<UUID> topology = new ArrayList<>();
        int workloadReads, coordinationReads;
        @Override public ProductionLibrarySession.Workload workload(UUID group) {
            workloadReads++; this.group = group; return new ProductionLibrarySession.Workload(3, 4);
        }
        @Override public PatternPriorityAuthority coordination(UUID group) {
            coordinationReads++; this.group = group; return PatternPriorityAuthority.empty();
        }
        @Override public void topologyChanged(UUID group) { topology.add(group); }
        @Override public List<PatternReply> drain(UUID group, UUID requester, int maximum) {
            this.group = group;
            var result = new ArrayList<PatternReply>();
            var iterator = ready.iterator();
            while (iterator.hasNext() && result.size() < maximum) {
                var reply = iterator.next();
                if (reply.requesterComputerId().equals(requester)) { result.add(reply); iterator.remove(); }
            }
            return result;
        }
        @Override public boolean enqueue(UUID group, PatternQuery query) {
            this.group = group; this.query = query; return true;
        }
        @Override public boolean publish(ProductionBinding binding, ProductionRecipeCatalog catalog,
            CommunicationFrequency frequency, int priority, RecipeSourceLocation address) {
            this.binding = binding; this.catalog = catalog; this.frequency = frequency;
            this.priority = priority; this.address = address; return true;
        }
    }
}
