package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageIndex;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Historical production query state, independent of page maintenance and generic Computer execution. */
public final class ProductionLibraryQueries {
    private static final String QUERIES = "Queries", REPLIES = "Replies",
        REPLY_REQUESTER_CURSOR = "ReplyRequesterCursor";
    private final RecipePageIndex data;
    private long observedDataGeneration;
    private final RestartTraversalProbe restartTraversalProbe = new RestartTraversalProbe();
    private final RestartTrackedDeque<PatternQuery> activeQueries =
        new RestartTrackedDeque<>(restartTraversalProbe, RestartQueue.QUERY);
    private RestartTrackedDeque<PatternReply> readyReplies =
        new RestartTrackedDeque<>(restartTraversalProbe, RestartQueue.REPLY);
    private RestartTraversalStats lastRestartTraversalStats = RestartTraversalStats.ZERO;
    @Nullable private UUID nextReplyRequester;

    /** References the application's existing index; never constructs a second catalog. */
    public ProductionLibraryQueries(RecipePageIndex data) {
        this.data = Objects.requireNonNull(data, "data");
        observedDataGeneration = data.generation();
    }

    /** Package-private archive access, not an independently replaceable index. */
    RecipePageIndex pageData() { return data; }

    public void enqueue(PatternQuery query) {
        Objects.requireNonNull(query, "query");
        activeQueries.addLast(query.targetsLibrary() ? query : query.restart(data.generation()));
    }

    /** Only the explicit production worker advances queries; physical page scans do not. */
    public int advanceSearch(int units) {
        resetDiagnostics();
        int used = 0;
        while (used < Math.max(0, units) && data.indexPassComplete() && !activeQueries.isEmpty()) {
            queryUnit();
            used++;
        }
        return used;
    }

    public List<PatternReply> pollReplies(int queueCount) {
        int count = Math.clamp(queueCount, 0, readyReplies.size());
        List<PatternReply> replies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) replies.add(readyReplies.removeFirst());
        normalizeReplyRequesterCursor();
        return List.copyOf(replies);
    }

    public int activeQueryCount() { return activeQueries.size(); }
    public int readyReplyCount() { return readyReplies.size(); }

    private void queryUnit() {
        PatternQuery query = activeQueries.removeFirst();
        if (query.passGeneration() != data.generation()) {
            if (query.targetsLibrary()) {
                addReadyReply(PatternReply.restartRequired(query, data.generation()));
                return;
            }
            query = query.restart(data.generation());
        }
        if (query.cursor() == data.completeRecords().size()) {
            addReadyReply(PatternReply.groupEnd(query, query.cursor()));
            return;
        }
        PatternRecord candidate = data.completeRecords().get(query.cursor());
        int nextCursor = query.cursor() + 1;
        if (candidate.mainOutput().stack().equals(query.requestedOutput())) {
            addReadyReply(PatternReply.matchBatch(query, List.of(snapshotRecord(candidate)), nextCursor));
            if (query.targetsLibrary()) activeQueries.addLast(query.continueAt(nextCursor));
        } else if (nextCursor == data.completeRecords().size()) {
            addReadyReply(PatternReply.groupEnd(query, nextCursor));
        } else {
            activeQueries.addLast(query.continueAt(nextCursor));
        }
    }

    private void addReadyReply(PatternReply reply) {
        readyReplies.addLast(reply);
        if (nextReplyRequester == null) nextReplyRequester = reply.requesterComputerId();
    }

    private void normalizeReplyRequesterCursor() {
        if (readyReplies.isEmpty()) { nextReplyRequester = null; return; }
        if (nextReplyRequester == null || !hasPendingReply(nextReplyRequester))
            nextReplyRequester = readyReplies.getFirst().requesterComputerId();
    }

    private boolean hasPendingReply(UUID requester) {
        return readyReplies.stream().anyMatch(reply -> reply.requesterComputerId().equals(requester));
    }

    private static PatternRecord snapshotRecord(PatternRecord record) {
        List<PatternOutput> outputs = record.outputs().stream()
            .map(output -> new PatternOutput(new StackKey(output.stack().stack()), output.count())).toList();
        return new PatternRecord(record.patternId(), record.source(), record.inputs(), outputs,
            record.body(), record.priorityOverride());
    }

    public List<PatternReply> drainReplies(UUID requesterComputerId, int maxReplies) {
        Objects.requireNonNull(requesterComputerId, "requesterComputerId");
        int count = Math.max(0, maxReplies);
        if (count == 0 || readyReplies.isEmpty())
            return List.of();
        UUID scheduledRequester = scheduledReplyRequester();
        if (!requesterComputerId.equals(scheduledRequester))
            return List.of();
        UUID successor = successorRequester(scheduledRequester);
        List<PatternReply> replies = new ArrayList<>(Math.min(count, readyReplies.size()));
        Iterator<PatternReply> iterator = readyReplies.iterator();
        while (iterator.hasNext() && replies.size() < count) {
            PatternReply reply = iterator.next();
            if (!reply.requesterComputerId().equals(requesterComputerId))
                continue;
            replies.add(reply);
            iterator.remove();
        }
        if (!replies.isEmpty())
            nextReplyRequester = nextPendingRequester(successor, scheduledRequester);
        return List.copyOf(replies);
    }

    private UUID scheduledReplyRequester() {
        normalizeReplyRequesterCursor();
        return Objects.requireNonNull(nextReplyRequester, "ready reply requester");
    }

    @Nullable
    private UUID successorRequester(UUID current) {
        List<UUID> requesters = new ArrayList<>(new LinkedHashSet<>(readyReplies.stream()
            .map(PatternReply::requesterComputerId).toList()));
        if (requesters.isEmpty())
            return null;
        int currentIndex = requesters.indexOf(current);
        return currentIndex < 0 ? requesters.getFirst()
            : requesters.get((currentIndex + 1) % requesters.size());
    }

    @Nullable
    private UUID nextPendingRequester(@Nullable UUID preferred, UUID justServed) {
        if (readyReplies.isEmpty())
            return null;
        if (preferred != null && hasPendingReply(preferred))
            return preferred;
        if (hasPendingReply(justServed))
            return justServed;
        return readyReplies.getFirst().requesterComputerId();
    }

    private PatternQuery currentGeneration(PatternQuery query) {
        return query.targetsLibrary() || query.passGeneration() == data.generation()
            ? query : query.restart(data.generation());
    }

    private static PatternReply snapshotReply(PatternReply reply) {
        return new PatternReply(reply.queryId(), reply.requesterComputerId(), reply.logisticsId(),
            reply.libraryId(), reply.generation(), reply.status(),
            reply.matches().stream().map(ProductionLibraryQueries::snapshotRecord).toList(),
            reply.nextPageCursor(), reply.formationGeneration(), reply.searchGeneration(),
            reply.attemptId());
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        CompoundTag root = new CompoundTag();

        ListTag queryTag = new ListTag();
        activeQueries.forEach(query -> queryTag.add(PatternSearchCodecs.saveQuery(
            currentGeneration(query), registries)));
        root.put(QUERIES, queryTag);
        ListTag replyTag = new ListTag();
        readyReplies.forEach(reply -> replyTag.add(PatternSearchCodecs.saveReply(reply, registries)));
        root.put(REPLIES, replyTag);
        if (nextReplyRequester != null)
            root.putUUID(REPLY_REQUESTER_CURSOR, nextReplyRequester);
        return root;
    }

    /** Reads queue fields against the exact once-decoded index and its original saved generation. */
    public static LoadResult load(CompoundTag root, HolderLookup.Provider registries,
        RecipePageIndex data, long savedGeneration, boolean restart) {
        Objects.requireNonNull(root, "root"); Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(data, "data");
        try {
            var expected = new HashSet<>(Set.of(QUERIES, REPLIES));
            if (root.contains(REPLY_REQUESTER_CURSOR)) expected.add(REPLY_REQUESTER_CURSOR);
            if (!root.getAllKeys().equals(expected) || !compoundList(root, QUERIES)
                || !compoundList(root, REPLIES)
                || root.contains(REPLY_REQUESTER_CURSOR) && !root.hasUUID(REPLY_REQUESTER_CURSOR))
                return new LoadResult(new ProductionLibraryQueries(data), true);
            var index = new ProductionLibraryQueries(data);
            var corruption = new Corruption();
            UUID loadedReplyRequester = root.hasUUID(REPLY_REQUESTER_CURSOR) ? root.getUUID(REPLY_REQUESTER_CURSOR) : null;
            for (Tag value : (ListTag) root.get(QUERIES)) {
                if (!(value instanceof CompoundTag child)) {
                    corruption.found = true;
                    continue;
                }
                var query = PatternSearchCodecs.loadQuery(child, registries);
                if (query.isEmpty()) {
                    corruption.found = true;
                    continue;
                }
                PatternQuery loaded = query.orElseThrow();
                boolean staleAddressedQuery = loaded.targetsLibrary()
                    && loaded.passGeneration() != savedGeneration;
                if (!staleAddressedQuery && (loaded.passGeneration() != savedGeneration || (!restart
                    && (loaded.cursor() > index.data.completeRecords().size() || (!index.data.indexPassComplete() && loaded.cursor() != 0))))) {
                    corruption.found = true;
                    continue;
                }
                index.activeQueries.addLast(loaded);
            }

            for (Tag value : (ListTag) root.get(REPLIES)) {
                if (!(value instanceof CompoundTag child)) {
                    corruption.found = true;
                    continue;
                }
                var reply = PatternSearchCodecs.loadReply(child, registries);
                if (reply.isEmpty() || reply.orElseThrow().generation() != savedGeneration) {
                    corruption.found = true;
                    continue;
                }
                index.readyReplies.addLast(snapshotReply(reply.orElseThrow()));
            }
            if (index.readyReplies.isEmpty()) {
                if (loadedReplyRequester != null)
                    corruption.found = true;
            } else if (loadedReplyRequester == null) {
                index.nextReplyRequester = index.readyReplies.getFirst().requesterComputerId();
            } else if (index.hasPendingReply(loadedReplyRequester)) {
                index.nextReplyRequester = loadedReplyRequester;
            } else {
                index.nextReplyRequester = index.readyReplies.getFirst().requesterComputerId();
                corruption.found = true;
            }

            if (restart) index.invalidateReadyReplies();
            return new LoadResult(index, corruption.found);
        } catch (RuntimeException exception) {
            return new LoadResult(new ProductionLibraryQueries(data), true);
        }
    }

    private static boolean compoundList(CompoundTag root, String key) {
        return root.get(key) instanceof ListTag list && (list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND);
    }

    public record LoadResult(ProductionLibraryQueries queries, boolean hadCorruption) {
        public LoadResult { Objects.requireNonNull(queries); }
    }

    private static final class Corruption { private boolean found; }

    /** Explicitly observe maintenance; old addressed queries keep their identity for RESTART_REQUIRED. */
    public void synchronizeData() {
        if (observedDataGeneration != data.generation()) invalidateReadyReplies();
        observedDataGeneration = data.generation();
    }

    public void resetDiagnostics() { lastRestartTraversalStats = RestartTraversalStats.ZERO; }
    public RestartTraversalStats lastRestartTraversalStats() { return lastRestartTraversalStats; }
    public List<PatternQuery> activeQueries() {
        return activeQueries.stream().map(this::currentGeneration).toList();
    }

    private void invalidateReadyReplies() {
        restartTraversalProbe.begin();
        try {
            readyReplies = new RestartTrackedDeque<>(restartTraversalProbe, RestartQueue.REPLY);
            nextReplyRequester = null;
        } finally { lastRestartTraversalStats = restartTraversalProbe.finish(); }
    }

    public record RestartTraversalStats(int queryVisits, int replyVisits) {
        public static final RestartTraversalStats ZERO = new RestartTraversalStats(0, 0);

        public RestartTraversalStats {
            if (queryVisits < 0 || replyVisits < 0)
                throw new IllegalArgumentException("Restart traversal counts cannot be negative");
        }

        public int totalVisits() { return queryVisits + replyVisits; }
    }
}
