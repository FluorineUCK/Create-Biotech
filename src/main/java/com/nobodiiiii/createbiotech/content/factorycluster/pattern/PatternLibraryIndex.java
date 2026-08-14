package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Incremental, generation-stable index over the pages in a pattern library. */
public final class PatternLibraryIndex {
	private static final String PAGE_ORDER = "PageOrder";
	private static final String CACHE = "Cache";
	private static final String FINGERPRINT_CURSOR = "FingerprintCursor";
	private static final String GENERATION = "Generation";
	private static final String COMPLETE = "Complete";
	private static final String QUERIES = "Queries";
	private static final String REPLIES = "Replies";
	private static final String REPLY_REQUESTER_CURSOR = "ReplyRequesterCursor";
	private static final Comparator<PatternPageKey> PAGE_COMPARATOR = Comparator
		.comparing((PatternPageKey key) -> key.shelf().dimension().location().toString())
		.thenComparing(key -> key.shelf().subLevelId(), Comparator.nullsFirst(Comparator.comparing(UUID::toString)))
		.thenComparingInt(key -> key.shelf().localPos().getX())
		.thenComparingInt(key -> key.shelf().localPos().getY())
		.thenComparingInt(key -> key.shelf().localPos().getZ())
		.thenComparingInt(PatternPageKey::slot)
		.thenComparingInt(PatternPageKey::page);

	private List<PatternPageKey> pageOrder = List.of();
	private final Map<PatternPageKey, CachedPage> cache = new HashMap<>();
	private int fingerprintCursor;
	private long generation;
	private boolean indexPassComplete = true;
	private List<PatternRecord> records = List.of();
	private List<PatternRecord> recordBuilder = new ArrayList<>();
	private final NavigableMap<PatternPageKey, PatternPageError> errors =
		new TreeMap<>(PAGE_COMPARATOR);
	private final RestartTraversalProbe restartTraversalProbe = new RestartTraversalProbe();
	private final RestartTrackedDeque<PatternQuery> activeQueries =
		new RestartTrackedDeque<>(restartTraversalProbe, RestartQueue.QUERY);
	private RestartTrackedDeque<PatternReply> readyReplies =
		new RestartTrackedDeque<>(restartTraversalProbe, RestartQueue.REPLY);
	@Nullable private UUID nextReplyRequester;
	private WorkLane nextLane = WorkLane.FINGERPRINT;
	private TickStats lastTickStats = new TickStats(0, 0, List.of());
	private final LinkedHashSet<PatternPageKey> invalidatedKeys = new LinkedHashSet<>();
	private int reparsedPageCount;
	private int lastRecordMaintenanceUnits;
	private int lastInvalidationMaintenanceUnits;
	private int lastGenerationTransitionUnits;
	private RestartTraversalStats lastRestartTraversalStats = RestartTraversalStats.ZERO;
	private int lastLoadMembershipChecks;
	private int lastLoadRecordVisits;

	public PatternLibraryIndex() {}

	public void rebuildPageOrder(Collection<PatternPageKey> keys) {
		Objects.requireNonNull(keys, "keys");
		List<PatternPageKey> canonical = canonicalPageOrder(keys);
		pageOrder = List.copyOf(canonical);
		Set<PatternPageKey> retained = new HashSet<>(pageOrder);
		cache.keySet().retainAll(retained);
		errors.keySet().retainAll(retained);
		restartGeneration();
	}

	static List<PatternPageKey> canonicalPageOrder(Collection<PatternPageKey> keys) {
		Objects.requireNonNull(keys, "keys");
		return keys.stream().map(key -> Objects.requireNonNull(key, "page key"))
			.distinct().sorted(PAGE_COMPARATOR).toList();
	}

	static boolean isCanonicalPageOrder(List<PatternPageKey> keys) {
		Objects.requireNonNull(keys, "keys");
		for (int index = 0; index < keys.size(); index++) {
			PatternPageKey key = Objects.requireNonNull(keys.get(index), "page key");
			if (index > 0 && PAGE_COMPARATOR.compare(keys.get(index - 1), key) >= 0)
				return false;
		}
		return true;
	}

	public void enqueue(PatternQuery query) {
		activeQueries.addLast(Objects.requireNonNull(query, "query").restart(generation));
	}

	List<PatternReply> pollReplies(int queueCount) {
		int count = Math.min(Math.max(0, queueCount), readyReplies.size());
		List<PatternReply> replies = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
			replies.add(readyReplies.removeFirst());
		normalizeReplyRequesterCursor();
		return List.copyOf(replies);
	}

	List<PatternReply> drainReplies(UUID requesterComputerId, int maxReplies) {
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

	public void tick(PageReader pages, PatternJsonParser parser, int totalBudget) {
		Objects.requireNonNull(pages, "pages");
		Objects.requireNonNull(parser, "parser");
		beginDiagnostics();
		if (totalBudget <= 0) {
			finishDiagnostics(List.of());
			return;
		}
		if (!pageOrder.isEmpty() && fingerprintCursor == pageOrder.size())
			beginNextFingerprintSweep();

		List<WorkLane> lanes = new ArrayList<>(totalBudget);
		boolean restartRequired = false;
		while (lanes.size() < totalBudget) {
			boolean fingerprintEligible = fingerprintEligible();
			boolean queryEligible = !restartRequired && queryEligible();
			if (!fingerprintEligible && !queryEligible)
				break;

			WorkLane lane;
			if (fingerprintEligible && queryEligible) {
				lane = nextLane;
				nextLane = opposite(nextLane);
			} else {
				lane = fingerprintEligible ? WorkLane.FINGERPRINT : WorkLane.QUERY;
			}
			if (lane == WorkLane.FINGERPRINT)
				restartRequired |= fingerprintUnit(pages, parser);
			else
				queryUnit();
			lanes.add(lane);
		}
		if (restartRequired)
			restartGeneration();
		finishDiagnostics(lanes);
	}

	void tickQueries(int units) {
		beginDiagnostics();
		List<WorkLane> lanes = new ArrayList<>(Math.max(0, units));
		while (lanes.size() < Math.max(0, units) && queryEligible()) {
			queryUnit();
			lanes.add(WorkLane.QUERY);
		}
		finishDiagnostics(lanes);
	}

	void tickFingerprintChecks(PageReader pages, PatternJsonParser parser, int units) {
		Objects.requireNonNull(pages, "pages");
		Objects.requireNonNull(parser, "parser");
		beginDiagnostics();
		List<WorkLane> lanes = new ArrayList<>(Math.max(0, units));
		boolean restartRequired = false;
		while (lanes.size() < Math.max(0, units) && fingerprintEligible()) {
			restartRequired |= fingerprintUnit(pages, parser);
			lanes.add(WorkLane.FINGERPRINT);
		}
		if (restartRequired)
			restartGeneration();
		finishDiagnostics(lanes);
	}

	void beginNextFingerprintSweep() {
		if (indexPassComplete && !pageOrder.isEmpty())
			fingerprintCursor = 0;
	}

	public CompoundTag save(HolderLookup.Provider registries) {
		Objects.requireNonNull(registries, "registries");
		CompoundTag root = new CompoundTag();
		ListTag orderTag = new ListTag();
		pageOrder.forEach(key -> orderTag.add(PatternValueCodecs.savePageKey(key)));
		root.put(PAGE_ORDER, orderTag);

		ListTag cacheTag = new ListTag();
		for (PatternPageKey key : pageOrder) {
			CachedPage page = cache.get(key);
			if (page != null)
				cacheTag.add(saveCachedPage(key, page, registries));
		}
		root.put(CACHE, cacheTag);
		root.putInt(FINGERPRINT_CURSOR, fingerprintCursor);
		root.putLong(GENERATION, generation);
		root.putBoolean(COMPLETE, indexPassComplete);

		ListTag queryTag = new ListTag();
		activeQueries.forEach(query -> queryTag.add(PatternValueCodecs.saveQuery(
			currentGeneration(query), registries)));
		root.put(QUERIES, queryTag);
		ListTag replyTag = new ListTag();
		readyReplies.forEach(reply -> replyTag.add(PatternValueCodecs.saveReply(reply, registries)));
		root.put(REPLIES, replyTag);
		if (nextReplyRequester != null)
			root.putUUID(REPLY_REQUESTER_CURSOR, nextReplyRequester);
		return root;
	}

	public static LoadResult load(CompoundTag root, HolderLookup.Provider registries) {
		Objects.requireNonNull(root, "root");
		Objects.requireNonNull(registries, "registries");
		try {
			if (!validRoot(root))
				return new LoadResult(new PatternLibraryIndex(), true);
			PatternLibraryIndex index = new PatternLibraryIndex();
			Corruption corruption = new Corruption();
			boolean restart = false;
			UUID loadedReplyRequester = root.hasUUID(REPLY_REQUESTER_CURSOR)
				? root.getUUID(REPLY_REQUESTER_CURSOR) : null;

			List<PatternPageKey> loadedOrder = new ArrayList<>();
			for (Tag value : (ListTag) root.get(PAGE_ORDER)) {
				if (!(value instanceof CompoundTag child)) {
					corruption.found = true;
					restart = true;
					continue;
				}
				var key = PatternValueCodecs.loadPageKey(child);
				if (key.isEmpty()) {
					corruption.found = true;
					restart = true;
				} else {
					loadedOrder.add(key.orElseThrow());
				}
			}
			List<PatternPageKey> canonicalOrder = loadedOrder.stream().distinct().sorted(PAGE_COMPARATOR).toList();
			if (!canonicalOrder.equals(loadedOrder)) {
				corruption.found = true;
				restart = true;
			}
			index.pageOrder = List.copyOf(canonicalOrder);
			index.generation = root.getLong(GENERATION);
			index.fingerprintCursor = root.getInt(FINGERPRINT_CURSOR);
			index.indexPassComplete = root.getBoolean(COMPLETE);
			if (index.fingerprintCursor < 0 || (!restart && (index.fingerprintCursor > index.pageOrder.size()
				|| (index.pageOrder.isEmpty() && (!index.indexPassComplete || index.fingerprintCursor != 0))
				|| (!index.indexPassComplete && index.fingerprintCursor == index.pageOrder.size()))))
				return new LoadResult(new PatternLibraryIndex(), true);

			Set<PatternPageKey> orderMembership = new HashSet<>(index.pageOrder);
			Set<PatternPageKey> seenCacheKeys = new LinkedHashSet<>();
			for (Tag value : (ListTag) root.get(CACHE)) {
				if (!(value instanceof CompoundTag child)) {
					corruption.found = true;
					restart = true;
					continue;
				}
				LoadedCachedPage loaded = loadCachedPage(child, registries);
				index.lastLoadMembershipChecks++;
				if (loaded == null || !orderMembership.contains(loaded.key())
					|| !seenCacheKeys.add(loaded.key())) {
					corruption.found = true;
					restart = true;
					continue;
				}
				index.putCachedPage(loaded.key(), loaded.page());
			}
			if (index.indexPassComplete && index.cache.size() != index.pageOrder.size()) {
				corruption.found = true;
				restart = true;
			}
			index.initializeLoadedRecordView();

			for (Tag value : (ListTag) root.get(QUERIES)) {
				if (!(value instanceof CompoundTag child)) {
					corruption.found = true;
					continue;
				}
				var query = PatternValueCodecs.loadQuery(child, registries);
				if (query.isEmpty()) {
					corruption.found = true;
					continue;
				}
				PatternQuery loaded = query.orElseThrow();
				if (loaded.passGeneration() != index.generation || (!restart
					&& (loaded.cursor() > index.records.size() || (!index.indexPassComplete && loaded.cursor() != 0)))) {
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
				var reply = PatternValueCodecs.loadReply(child, registries);
				if (reply.isEmpty() || reply.orElseThrow().generation() != index.generation) {
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

			if (restart)
				index.restartGeneration();
			return new LoadResult(index, corruption.found);
		} catch (RuntimeException exception) {
			return new LoadResult(new PatternLibraryIndex(), true);
		}
	}

	public record LoadResult(PatternLibraryIndex index, boolean hadCorruption) {
		public LoadResult {
			index = Objects.requireNonNull(index, "index");
		}
	}

	List<PatternPageKey> pageOrder() { return pageOrder; }
	long generation() { return generation; }
	boolean indexPassComplete() { return indexPassComplete; }
	int fingerprintCursor() { return fingerprintCursor; }
	boolean cached(PatternPageKey key) { return cache.containsKey(key); }
	int cachedPageCount() { return cache.size(); }
	List<PatternQuery> activeQueries() {
		return activeQueries.stream().map(this::currentGeneration).toList();
	}
	int activeQueryCount() { return activeQueries.size(); }
	int readyReplyCount() { return readyReplies.size(); }
	List<PatternPageError> pageErrors() { return errors.values().stream().limit(PatternLibrarySummary.MAX_ERRORS).toList(); }
	int indexedPatternCount() { return indexPassComplete ? records.size() : recordBuilder.size(); }
	int lastRecordMaintenanceUnits() { return lastRecordMaintenanceUnits; }
	int lastInvalidationMaintenanceUnits() { return lastInvalidationMaintenanceUnits; }
	int lastGenerationTransitionUnits() { return lastGenerationTransitionUnits; }
	int lastRestartQueryVisits() { return lastRestartTraversalStats.queryVisits(); }
	int lastRestartReplyVisits() { return lastRestartTraversalStats.replyVisits(); }
	RestartTraversalStats lastRestartTraversalStats() { return lastRestartTraversalStats; }
	int lastLoadMembershipChecks() { return lastLoadMembershipChecks; }
	int lastLoadRecordVisits() { return lastLoadRecordVisits; }
	List<WorkLane> lastLaneTrace() { return lastTickStats.lanes(); }
	int lastFingerprintUnits() { return lastTickStats.fingerprintUnits(); }
	int lastQueryUnits() { return lastTickStats.queryUnits(); }
	int lastTotalUnits() { return lastTickStats.totalUnits(); }
	Set<PatternPageKey> lastInvalidatedKeys() { return Set.copyOf(invalidatedKeys); }
	int reparsedPageCount() { return reparsedPageCount; }

	PatternLibrarySummary summary(PatternLibraryScanner.StructureState reason, int capacity) {
		int indexedPages = indexPassComplete ? pageOrder.size() : fingerprintCursor;
		return new PatternLibrarySummary(reason, Math.max(0, capacity), pageOrder.size(),
			indexedPages, indexedPatternCount(), errors.size(),
			indexPassComplete ? 0 : pageOrder.size() - fingerprintCursor,
			!indexPassComplete, pageErrors());
	}

	private boolean fingerprintEligible() {
		return fingerprintCursor < pageOrder.size();
	}

	private boolean queryEligible() {
		return indexPassComplete && !activeQueries.isEmpty();
	}

	private boolean fingerprintUnit(PageReader pages, PatternJsonParser parser) {
		PatternPageKey key = pageOrder.get(fingerprintCursor);
		CachedPage previous = cache.get(key);
		String raw = Objects.requireNonNullElse(pages.read(key), "");
		PageInspection inspection = parser.inspect(key, raw, previous == null ? null : previous.fingerprint());
		if (inspection.changedResult().isEmpty()) {
			completeFingerprintUnit(key);
			return false;
		}

		reparsedPageCount++;
		putCachedPage(key, cachedPage(inspection.changedResult().orElseThrow()));
		boolean restartRequired = previous != null;
		if (restartRequired) {
			invalidatedKeys.add(key);
			lastInvalidationMaintenanceUnits++;
		}
		completeFingerprintUnit(key);
		return restartRequired;
	}

	private void completeFingerprintUnit(PatternPageKey key) {
		if (!indexPassComplete) {
			lastRecordMaintenanceUnits++;
			CachedPage page = cache.get(key);
			if (page != null && page.kind() == CacheKind.VALID)
				recordBuilder.add(snapshotRecord(page.pattern()));
		}
		fingerprintCursor++;
		if (fingerprintCursor == pageOrder.size() && !indexPassComplete) {
			records = Collections.unmodifiableList(recordBuilder);
			recordBuilder = new ArrayList<>();
			indexPassComplete = true;
		}
	}

	private void queryUnit() {
		PatternQuery query = activeQueries.removeFirst();
		if (query.passGeneration() != generation)
			query = query.restart(generation);
		if (query.cursor() == records.size()) {
			addReadyReply(new PatternReply(query.queryId(), query.requesterComputerId(),
				query.logisticsId(), generation,
				PatternReplyStatus.NOT_FOUND, null));
			return;
		}

		PatternRecord candidate = records.get(query.cursor());
		int nextCursor = query.cursor() + 1;
		if (candidate.mainOutput().stack().equals(query.requestedOutput())) {
			addReadyReply(new PatternReply(query.queryId(), query.requesterComputerId(),
				query.logisticsId(), generation,
				PatternReplyStatus.MATCH, snapshotRecord(candidate)));
		} else if (nextCursor == records.size()) {
			addReadyReply(new PatternReply(query.queryId(), query.requesterComputerId(),
				query.logisticsId(), generation,
				PatternReplyStatus.NOT_FOUND, null));
		} else {
			activeQueries.addLast(new PatternQuery(query.queryId(), query.requesterComputerId(),
				query.logisticsId(), query.requestedOutput(), generation, nextCursor));
		}
	}

	private void restartGeneration() {
		restartTraversalProbe.begin();
		try {
			lastGenerationTransitionUnits++;
			generation++;
			fingerprintCursor = 0;
			indexPassComplete = pageOrder.isEmpty();
			records = List.of();
			recordBuilder = new ArrayList<>();
			readyReplies = new RestartTrackedDeque<>(restartTraversalProbe, RestartQueue.REPLY);
			nextReplyRequester = null;
		} finally {
			lastRestartTraversalStats = restartTraversalProbe.finish();
		}
	}

	private PatternQuery currentGeneration(PatternQuery query) {
		return query.passGeneration() == generation ? query : query.restart(generation);
	}

	private void addReadyReply(PatternReply reply) {
		readyReplies.addLast(reply);
		if (nextReplyRequester == null)
			nextReplyRequester = reply.requesterComputerId();
	}

	private UUID scheduledReplyRequester() {
		normalizeReplyRequesterCursor();
		return Objects.requireNonNull(nextReplyRequester, "ready reply requester");
	}

	private void normalizeReplyRequesterCursor() {
		if (readyReplies.isEmpty()) {
			nextReplyRequester = null;
			return;
		}
		if (nextReplyRequester == null || !hasPendingReply(nextReplyRequester))
			nextReplyRequester = readyReplies.getFirst().requesterComputerId();
	}

	private boolean hasPendingReply(UUID requester) {
		return readyReplies.stream().anyMatch(reply ->
			reply.requesterComputerId().equals(requester));
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

	private void putCachedPage(PatternPageKey key, CachedPage page) {
		cache.put(key, page);
		errors.remove(key);
		if (page.kind() == CacheKind.INVALID)
			errors.put(key, page.error());
	}

	private void initializeLoadedRecordView() {
		List<PatternRecord> loadedRecords = new ArrayList<>();
		int end = indexPassComplete ? pageOrder.size() : fingerprintCursor;
		for (int i = 0; i < end; i++) {
			PatternPageKey key = pageOrder.get(i);
			lastLoadRecordVisits++;
			CachedPage page = cache.get(key);
			if (page != null && page.kind() == CacheKind.VALID)
				loadedRecords.add(snapshotRecord(page.pattern()));
		}
		if (indexPassComplete) {
			records = Collections.unmodifiableList(loadedRecords);
			recordBuilder = new ArrayList<>();
		} else {
			records = List.of();
			recordBuilder = loadedRecords;
		}
	}

	private void beginDiagnostics() {
		lastTickStats = new TickStats(0, 0, List.of());
		invalidatedKeys.clear();
		reparsedPageCount = 0;
		lastRecordMaintenanceUnits = 0;
		lastInvalidationMaintenanceUnits = 0;
		lastGenerationTransitionUnits = 0;
		lastRestartTraversalStats = RestartTraversalStats.ZERO;
	}

	private void finishDiagnostics(List<WorkLane> lanes) {
		int fingerprints = (int) lanes.stream().filter(lane -> lane == WorkLane.FINGERPRINT).count();
		lastTickStats = new TickStats(fingerprints, lanes.size() - fingerprints, lanes);
	}

	private static WorkLane opposite(WorkLane lane) {
		return lane == WorkLane.FINGERPRINT ? WorkLane.QUERY : WorkLane.FINGERPRINT;
	}

	private static CachedPage cachedPage(ParseResult result) {
		if (result instanceof ParseResult.Blank blank)
			return new CachedPage(blank.fingerprint(), CacheKind.BLANK, null, null);
		if (result instanceof ParseResult.Valid valid)
			return new CachedPage(valid.fingerprint(), CacheKind.VALID, valid.pattern(), null);
		ParseResult.Invalid invalid = (ParseResult.Invalid) result;
		return new CachedPage(invalid.fingerprint(), CacheKind.INVALID, null, invalid.error());
	}

	private static CompoundTag saveCachedPage(PatternPageKey key, CachedPage page,
		HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.put("Page", PatternValueCodecs.savePageKey(key));
		tag.putString("Fingerprint", page.fingerprint());
		tag.putString("State", page.kind().name());
		if (page.kind() == CacheKind.VALID)
			tag.put("Record", PatternValueCodecs.saveRecord(page.pattern(), registries));
		else if (page.kind() == CacheKind.INVALID)
			tag.put("Error", PatternValueCodecs.savePageError(page.error()));
		return tag;
	}

	@Nullable
	private static LoadedCachedPage loadCachedPage(CompoundTag tag, HolderLookup.Provider registries) {
		if (!hasType(tag, "Page", Tag.TAG_COMPOUND) || !hasType(tag, "Fingerprint", Tag.TAG_STRING)
			|| !hasType(tag, "State", Tag.TAG_STRING))
			return null;
		var key = PatternValueCodecs.loadPageKey(tag.getCompound("Page"));
		if (key.isEmpty() || !isFingerprint(tag.getString("Fingerprint")))
			return null;
		CacheKind kind;
		try {
			kind = CacheKind.valueOf(tag.getString("State"));
		} catch (IllegalArgumentException exception) {
			return null;
		}
		PatternPageKey pageKey = key.orElseThrow();
		return switch (kind) {
			case BLANK -> hasExactKeys(tag, "Page", "Fingerprint", "State")
				? new LoadedCachedPage(pageKey, new CachedPage(tag.getString("Fingerprint"), kind, null, null)) : null;
			case VALID -> {
				if (!hasExactKeys(tag, "Page", "Fingerprint", "State", "Record")
					|| !hasType(tag, "Record", Tag.TAG_COMPOUND)) yield null;
				var record = PatternValueCodecs.loadRecord(tag.getCompound("Record"), registries);
				yield record.isPresent() && record.orElseThrow().source().equals(pageKey)
					? new LoadedCachedPage(pageKey, new CachedPage(tag.getString("Fingerprint"), kind,
						record.orElseThrow(), null)) : null;
			}
			case INVALID -> {
				if (!hasExactKeys(tag, "Page", "Fingerprint", "State", "Error")
					|| !hasType(tag, "Error", Tag.TAG_COMPOUND)) yield null;
				var error = PatternValueCodecs.loadPageError(tag.getCompound("Error"));
				yield error.isPresent() && error.orElseThrow().source().equals(pageKey)
					? new LoadedCachedPage(pageKey, new CachedPage(tag.getString("Fingerprint"), kind,
						null, error.orElseThrow())) : null;
			}
		};
	}

	private static PatternRecord snapshotRecord(PatternRecord record) {
		List<PatternOutput> outputs = record.outputs().stream()
			.map(output -> new PatternOutput(new StackKey(output.stack().stack()), output.count())).toList();
		return new PatternRecord(record.patternId(), record.source(), record.inputs(), outputs,
			record.recipeAddress());
	}

	private static PatternReply snapshotReply(PatternReply reply) {
		return new PatternReply(reply.queryId(), reply.requesterComputerId(), reply.logisticsId(),
			reply.generation(), reply.status(),
			reply.pattern() == null ? null : snapshotRecord(reply.pattern()));
	}

	private static boolean validRoot(CompoundTag root) {
		Set<String> expected = new HashSet<>(Set.of(PAGE_ORDER, CACHE, FINGERPRINT_CURSOR,
			GENERATION, COMPLETE, QUERIES, REPLIES));
		if (root.contains(REPLY_REQUESTER_CURSOR))
			expected.add(REPLY_REQUESTER_CURSOR);
		return root.getAllKeys().equals(expected)
			&& hasType(root, PAGE_ORDER, Tag.TAG_LIST) && hasType(root, CACHE, Tag.TAG_LIST)
			&& hasType(root, FINGERPRINT_CURSOR, Tag.TAG_INT) && hasType(root, GENERATION, Tag.TAG_LONG)
			&& hasType(root, COMPLETE, Tag.TAG_BYTE) && hasType(root, QUERIES, Tag.TAG_LIST)
			&& hasType(root, REPLIES, Tag.TAG_LIST)
			&& (!root.contains(REPLY_REQUESTER_CURSOR) || root.hasUUID(REPLY_REQUESTER_CURSOR))
			&& (root.getByte(COMPLETE) == 0 || root.getByte(COMPLETE) == 1)
			&& isCompoundList((ListTag) root.get(PAGE_ORDER)) && isCompoundList((ListTag) root.get(CACHE))
			&& isCompoundList((ListTag) root.get(QUERIES)) && isCompoundList((ListTag) root.get(REPLIES));
	}

	private static boolean isCompoundList(ListTag list) {
		return list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND;
	}

	private static boolean hasType(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}

	private static boolean hasExactKeys(CompoundTag tag, String... keys) {
		return tag.getAllKeys().size() == keys.length && Set.of(keys).equals(tag.getAllKeys());
	}

	private static boolean isFingerprint(String value) {
		if (value.length() != 64)
			return false;
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')))
				return false;
		}
		return true;
	}

	private enum CacheKind { VALID, BLANK, INVALID }

	private record CachedPage(String fingerprint, CacheKind kind, @Nullable PatternRecord pattern,
		@Nullable PatternPageError error) {
		private CachedPage {
			fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
			kind = Objects.requireNonNull(kind, "kind");
			if (!isFingerprint(fingerprint)
				|| (kind == CacheKind.VALID) != (pattern != null)
				|| (kind == CacheKind.INVALID) != (error != null))
				throw new IllegalArgumentException("Invalid cached page");
			if (pattern != null)
				pattern = snapshotRecord(pattern);
		}
	}

	private record LoadedCachedPage(PatternPageKey key, CachedPage page) {}
	private static final class Corruption { private boolean found; }
}

@FunctionalInterface
interface PageReader {
	String read(PatternPageKey key);
}

enum WorkLane { FINGERPRINT, QUERY }

record TickStats(int fingerprintUnits, int queryUnits, List<WorkLane> lanes) {
	TickStats {
		if (fingerprintUnits < 0 || queryUnits < 0)
			throw new IllegalArgumentException("Work counts cannot be negative");
		lanes = List.copyOf(lanes);
		if (lanes.size() != fingerprintUnits + queryUnits)
			throw new IllegalArgumentException("Lane trace does not match work counts");
	}

	int totalUnits() { return fingerprintUnits + queryUnits; }
}

enum RestartQueue { QUERY, REPLY }

record RestartTraversalStats(int queryVisits, int replyVisits) {
	static final RestartTraversalStats ZERO = new RestartTraversalStats(0, 0);

	RestartTraversalStats {
		if (queryVisits < 0 || replyVisits < 0)
			throw new IllegalArgumentException("Restart traversal counts cannot be negative");
	}

	int totalVisits() { return queryVisits + replyVisits; }
}

/** Measures element visits only while a generation transition is in progress. */
final class RestartTraversalProbe {
	private boolean active;
	private int queryVisits;
	private int replyVisits;

	void begin() {
		if (active)
			throw new IllegalStateException("Restart traversal measurement is already active");
		queryVisits = 0;
		replyVisits = 0;
		active = true;
	}

	RestartTraversalStats finish() {
		if (!active)
			throw new IllegalStateException("Restart traversal measurement is not active");
		active = false;
		return new RestartTraversalStats(queryVisits, replyVisits);
	}

	boolean active() { return active; }

	void visit(RestartQueue queue, int units) {
		if (!active || units <= 0)
			return;
		if (queue == RestartQueue.QUERY)
			queryVisits += units;
		else
			replyVisits += units;
	}
}

/**
 * Queue wrapper used by production restart logic so eager stream/remove/copy/clear
 * regressions become observable operation counts rather than dead test counters.
 */
final class RestartTrackedDeque<E> extends ArrayDeque<E> {
	private static final long serialVersionUID = 1L;
	private final RestartTraversalProbe probe;
	private final RestartQueue queue;

	RestartTrackedDeque(RestartTraversalProbe probe, RestartQueue queue) {
		this.probe = Objects.requireNonNull(probe, "probe");
		this.queue = Objects.requireNonNull(queue, "queue");
	}

	@Override
	public E removeFirst() {
		E value = super.pollFirst();
		if (value == null)
			throw new java.util.NoSuchElementException();
		probe.visit(queue, 1);
		return value;
	}

	@Override
	public E removeLast() {
		E value = super.pollLast();
		if (value == null)
			throw new java.util.NoSuchElementException();
		probe.visit(queue, 1);
		return value;
	}

	@Override
	public E pollFirst() {
		E value = super.pollFirst();
		if (value != null)
			probe.visit(queue, 1);
		return value;
	}

	@Override
	public E pollLast() {
		E value = super.pollLast();
		if (value != null)
			probe.visit(queue, 1);
		return value;
	}

	@Override
	public boolean removeFirstOccurrence(Object candidate) {
		if (!probe.active())
			return super.removeFirstOccurrence(candidate);
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (!Objects.equals(candidate, iterator.next()))
				continue;
			iterator.remove();
			return true;
		}
		return false;
	}

	@Override
	public boolean removeLastOccurrence(Object candidate) {
		if (!probe.active())
			return super.removeLastOccurrence(candidate);
		Iterator<E> iterator = descendingIterator();
		while (iterator.hasNext()) {
			if (!Objects.equals(candidate, iterator.next()))
				continue;
			iterator.remove();
			return true;
		}
		return false;
	}

	@Override
	public boolean contains(Object candidate) {
		if (!probe.active())
			return super.contains(candidate);
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (Objects.equals(candidate, iterator.next()))
				return true;
		}
		return false;
	}

	@Override
	public boolean removeAll(Collection<?> candidates) {
		Objects.requireNonNull(candidates, "candidates");
		if (!probe.active())
			return super.removeAll(candidates);
		boolean removed = false;
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (!candidates.contains(iterator.next()))
				continue;
			iterator.remove();
			removed = true;
		}
		return removed;
	}

	@Override
	public boolean retainAll(Collection<?> retained) {
		Objects.requireNonNull(retained, "retained");
		if (!probe.active())
			return super.retainAll(retained);
		boolean removed = false;
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (retained.contains(iterator.next()))
				continue;
			iterator.remove();
			removed = true;
		}
		return removed;
	}

	@Override
	@SuppressWarnings("unchecked")
	public RestartTrackedDeque<E> clone() {
		probe.visit(queue, size());
		return (RestartTrackedDeque<E>) super.clone();
	}

	@Override
	public Iterator<E> iterator() {
		return counting(super.iterator());
	}

	@Override
	public Iterator<E> descendingIterator() {
		return counting(super.descendingIterator());
	}

	@Override
	public Spliterator<E> spliterator() {
		return counting(super.spliterator());
	}

	@Override
	public void forEach(Consumer<? super E> action) {
		Objects.requireNonNull(action, "action");
		if (!probe.active()) {
			super.forEach(action);
			return;
		}
		Iterator<E> iterator = iterator();
		while (iterator.hasNext())
			action.accept(iterator.next());
	}

	@Override
	public boolean removeIf(Predicate<? super E> filter) {
		Objects.requireNonNull(filter, "filter");
		if (!probe.active())
			return super.removeIf(filter);
		boolean removed = false;
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (!filter.test(iterator.next()))
				continue;
			iterator.remove();
			removed = true;
		}
		return removed;
	}

	@Override
	public Object[] toArray() {
		probe.visit(queue, size());
		return super.toArray();
	}

	@Override
	public <T> T[] toArray(T[] destination) {
		probe.visit(queue, size());
		return super.toArray(destination);
	}

	@Override
	public void clear() {
		probe.visit(queue, size());
		super.clear();
	}

	private Iterator<E> counting(Iterator<E> delegate) {
		return new Iterator<>() {
			@Override public boolean hasNext() { return delegate.hasNext(); }
			@Override public E next() {
				E value = delegate.next();
				probe.visit(queue, 1);
				return value;
			}
			@Override public void remove() { delegate.remove(); }
			@Override public void forEachRemaining(Consumer<? super E> action) {
				Objects.requireNonNull(action, "action");
				delegate.forEachRemaining(value -> {
					probe.visit(queue, 1);
					action.accept(value);
				});
			}
		};
	}

	private Spliterator<E> counting(Spliterator<E> delegate) {
		return new Spliterator<>() {
			@Override public boolean tryAdvance(Consumer<? super E> action) {
				Objects.requireNonNull(action, "action");
				return delegate.tryAdvance(value -> {
					probe.visit(queue, 1);
					action.accept(value);
				});
			}
			@Override public void forEachRemaining(Consumer<? super E> action) {
				Objects.requireNonNull(action, "action");
				delegate.forEachRemaining(value -> {
					probe.visit(queue, 1);
					action.accept(value);
				});
			}
			@Override public Spliterator<E> trySplit() {
				Spliterator<E> split = delegate.trySplit();
				return split == null ? null : counting(split);
			}
			@Override public long estimateSize() { return delegate.estimateSize(); }
			@Override public int characteristics() { return delegate.characteristics(); }
			@Override public java.util.Comparator<? super E> getComparator() {
				return delegate.getComparator();
			}
		};
	}
}
