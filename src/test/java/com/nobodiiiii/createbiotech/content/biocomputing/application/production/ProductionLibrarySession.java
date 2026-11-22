package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionLibraryAttachment;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageIndex;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

/** Library's production application, not a general Computer runtime or storage authorization. */
public final class ProductionLibrarySession {
    private final ProductionLibraryAttachment attachment;
    private final Backend backend;
    private long replyTick = Long.MIN_VALUE;
    private int remainingReplies;

    public ProductionLibrarySession(ProductionLibraryAttachment attachment, Backend backend) {
        this.attachment = Objects.requireNonNull(attachment);
        this.backend = Objects.requireNonNull(backend);
    }

    /** Host must have the live Library identity claim; the sole index is sampled, never copied or cached. */
    public boolean publish(UUID libraryId, RecipePageIndex index, boolean available,
        CommunicationFrequency frequency, int priority, RecipeSourceLocation address) {
        var binding = attachment.binding();
        if (binding == null) return false;
        var catalog = catalog(libraryId, binding.revision(), index, available && attachment.valid());
        return backend.publish(binding, catalog, frequency, priority, address);
    }

    public static ProductionRecipeCatalog catalog(UUID libraryId, long formationGeneration,
        RecipePageIndex index, boolean available) {
        boolean queryable = available && index.indexPassComplete();
        return new ProductionRecipeCatalog(libraryId, formationGeneration, index.generation(),
            queryable, queryable ? index.completeRecords() : List.of());
    }

    /** Authorized is a current host proof, not a caller-provided program permission. */
    public boolean enqueue(boolean authorized, PatternQuery query) {
        var binding = attachment.binding();
        if (!authorized || !attachment.valid() || binding == null) return false;
        return binding.logisticsIds().contains(query.logisticsId()) && backend.enqueue(binding.groupId(), query);
    }

    /** Host proves current access and supplies the server game tick and physical reply capacity. */
    public List<PatternReply> drain(long tick, int capacity, UUID requester, int requested) {
        Objects.requireNonNull(requester, "requester");
        var binding = attachment.binding();
        if (binding == null || !attachment.valid()) return List.of();
        if (tick != replyTick) { replyTick = tick; remainingReplies = Math.max(0, capacity); }
        int maximum = Math.min(remainingReplies, Math.max(0, requested));
        var replies = List.copyOf(backend.drain(binding.groupId(), requester, maximum));
        if (replies.size() > maximum) throw new IllegalStateException("Production backend exceeded reply allowance");
        remainingReplies -= replies.size();
        return replies;
    }

    /** Invoked only while the host still owns its physical identity, including invalidation. */
    public void topologyChanged() {
        var binding = attachment.binding();
        if (binding != null) backend.topologyChanged(binding.groupId());
    }

    public void topologyRebound(@Nullable UUID priorGroup) {
        if (priorGroup != null) backend.topologyChanged(priorGroup);
        // Read after the old group's notification: callbacks may reconcile the live attachment.
        var binding = attachment.binding();
        if (binding != null && !binding.groupId().equals(priorGroup)) backend.topologyChanged(binding.groupId());
    }

    public Workload workload() {
        var binding = attachment.binding();
        return binding == null || !attachment.valid() ? Workload.EMPTY : backend.workload(binding.groupId());
    }

    public PatternPriorityAuthority coordination(boolean authorized) {
        var binding = attachment.binding();
        return !authorized || binding == null || !attachment.valid()
            ? PatternPriorityAuthority.empty() : backend.coordination(binding.groupId());
    }

    public record Workload(int activeQueries, int readyReplies) {
        public static final Workload EMPTY = new Workload(0, 0);
        public Workload {
            if (activeQueries < 0 || readyReplies < 0) throw new IllegalArgumentException("Negative production workload");
        }
    }

    /** Value-only boundary to the production backend. Publication must be durable before worker visibility. */
    public interface Backend {
        boolean publish(ProductionBinding binding, ProductionRecipeCatalog catalog,
            CommunicationFrequency frequency, int priority, RecipeSourceLocation address);
        boolean enqueue(UUID groupId, PatternQuery query);
        List<PatternReply> drain(UUID groupId, UUID requester, int maximum);
        void topologyChanged(UUID groupId);
        /** Observations must not create a runtime, pump results or advance a production process. */
        Workload workload(UUID groupId);
        PatternPriorityAuthority coordination(UUID groupId);
    }
}
