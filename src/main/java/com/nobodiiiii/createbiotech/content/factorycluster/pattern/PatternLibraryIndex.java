package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
	private final ArrayDeque<PatternQuery> activeQueries = new ArrayDeque<>();
	private final ArrayDeque<PatternReply> readyReplies = new ArrayDeque<>();
	private WorkLane nextLane = WorkLane.FINGERPRINT;
	private TickStats lastTickStats = new TickStats(0, 0, List.of());
	private Set<PatternPageKey> lastInvalidatedKeys = Set.of();
	private int reparsedPageCount;

	public PatternLibraryIndex() {}

	public void rebuildPageOrder(Collection<PatternPageKey> keys) {
		Objects.requireNonNull(keys, "keys");
		List<PatternPageKey> canonical = keys.stream().map(key -> Objects.requireNonNull(key, "page key"))
			.distinct().sorted(PAGE_COMPARATOR).toList();
		pageOrder = List.copyOf(canonical);
		cache.keySet().retainAll(pageOrder);
		atomicRestart();
	}

	public void enqueue(PatternQuery query) {
		activeQueries.addLast(Objects.requireNonNull(query, "query").restart(generation));
	}

	public List<PatternReply> pollReplies(int queueCount) {
		int count = Math.min(Math.max(0, queueCount), readyReplies.size());
		List<PatternReply> replies = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
			replies.add(readyReplies.removeFirst());
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
		while (lanes.size() < totalBudget) {
			boolean fingerprintEligible = fingerprintEligible();
			boolean queryEligible = queryEligible();
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
				fingerprintUnit(pages, parser);
			else
				queryUnit();
			lanes.add(lane);
		}
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
		while (lanes.size() < Math.max(0, units) && fingerprintEligible()) {
			fingerprintUnit(pages, parser);
			lanes.add(WorkLane.FINGERPRINT);
		}
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
		activeQueries.forEach(query -> queryTag.add(PatternValueCodecs.saveQuery(query, registries)));
		root.put(QUERIES, queryTag);
		ListTag replyTag = new ListTag();
		readyReplies.forEach(reply -> replyTag.add(PatternValueCodecs.saveReply(reply, registries)));
		root.put(REPLIES, replyTag);
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

			Set<PatternPageKey> seenCacheKeys = new LinkedHashSet<>();
			for (Tag value : (ListTag) root.get(CACHE)) {
				if (!(value instanceof CompoundTag child)) {
					corruption.found = true;
					restart = true;
					continue;
				}
				LoadedCachedPage loaded = loadCachedPage(child, registries);
				if (loaded == null || !index.pageOrder.contains(loaded.key())
					|| !seenCacheKeys.add(loaded.key())) {
					corruption.found = true;
					restart = true;
					continue;
				}
				index.cache.put(loaded.key(), loaded.page());
			}
			if (index.indexPassComplete && index.cache.size() != index.pageOrder.size()) {
				corruption.found = true;
				restart = true;
			}
			index.rebuildRecordView();

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

			if (restart)
				index.atomicRestart();
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
	List<PatternQuery> activeQueries() { return List.copyOf(activeQueries); }
	int activeQueryCount() { return activeQueries.size(); }
	List<WorkLane> lastLaneTrace() { return lastTickStats.lanes(); }
	int lastFingerprintUnits() { return lastTickStats.fingerprintUnits(); }
	int lastQueryUnits() { return lastTickStats.queryUnits(); }
	int lastTotalUnits() { return lastTickStats.totalUnits(); }
	Set<PatternPageKey> lastInvalidatedKeys() { return lastInvalidatedKeys; }
	int reparsedPageCount() { return reparsedPageCount; }

	private boolean fingerprintEligible() {
		return fingerprintCursor < pageOrder.size();
	}

	private boolean queryEligible() {
		return indexPassComplete && !activeQueries.isEmpty();
	}

	private void fingerprintUnit(PageReader pages, PatternJsonParser parser) {
		PatternPageKey key = pageOrder.get(fingerprintCursor);
		CachedPage previous = cache.get(key);
		String raw = Objects.requireNonNullElse(pages.read(key), "");
		PageInspection inspection = parser.inspect(key, raw, previous == null ? null : previous.fingerprint());
		if (inspection.changedResult().isEmpty()) {
			advanceFingerprintCursor();
			return;
		}

		reparsedPageCount++;
		cache.put(key, cachedPage(inspection.changedResult().orElseThrow()));
		rebuildRecordView();
		if (previous == null) {
			advanceFingerprintCursor();
		} else {
			lastInvalidatedKeys = addInvalidated(lastInvalidatedKeys, key);
			atomicRestart();
		}
	}

	private void advanceFingerprintCursor() {
		fingerprintCursor++;
		if (fingerprintCursor == pageOrder.size())
			indexPassComplete = true;
	}

	private void queryUnit() {
		PatternQuery query = activeQueries.removeFirst();
		if (query.passGeneration() != generation)
			query = query.restart(generation);
		if (query.cursor() == records.size()) {
			readyReplies.addLast(new PatternReply(query.queryId(), generation,
				PatternReplyStatus.NOT_FOUND, null));
			return;
		}

		PatternRecord candidate = records.get(query.cursor());
		int nextCursor = query.cursor() + 1;
		if (candidate.mainOutput().stack().equals(query.requestedOutput())) {
			readyReplies.addLast(new PatternReply(query.queryId(), generation,
				PatternReplyStatus.MATCH, snapshotRecord(candidate)));
		} else if (nextCursor == records.size()) {
			readyReplies.addLast(new PatternReply(query.queryId(), generation,
				PatternReplyStatus.NOT_FOUND, null));
		} else {
			activeQueries.addLast(new PatternQuery(query.queryId(), query.requesterComputerId(),
				query.logisticsId(), query.requestedOutput(), generation, nextCursor));
		}
	}

	private void atomicRestart() {
		generation++;
		fingerprintCursor = 0;
		indexPassComplete = pageOrder.isEmpty();
		rebuildRecordView();
		readyReplies.removeIf(reply -> reply.generation() < generation);
		if (!activeQueries.isEmpty()) {
			List<PatternQuery> restarted = activeQueries.stream().map(query -> query.restart(generation)).toList();
			activeQueries.clear();
			activeQueries.addAll(restarted);
		}
	}

	private void rebuildRecordView() {
		List<PatternRecord> rebuilt = new ArrayList<>();
		for (PatternPageKey key : pageOrder) {
			CachedPage page = cache.get(key);
			if (page != null && page.kind() == CacheKind.VALID)
				rebuilt.add(snapshotRecord(page.pattern()));
		}
		records = List.copyOf(rebuilt);
	}

	private void beginDiagnostics() {
		lastTickStats = new TickStats(0, 0, List.of());
		lastInvalidatedKeys = Set.of();
		reparsedPageCount = 0;
	}

	private void finishDiagnostics(List<WorkLane> lanes) {
		int fingerprints = (int) lanes.stream().filter(lane -> lane == WorkLane.FINGERPRINT).count();
		lastTickStats = new TickStats(fingerprints, lanes.size() - fingerprints, lanes);
	}

	private static WorkLane opposite(WorkLane lane) {
		return lane == WorkLane.FINGERPRINT ? WorkLane.QUERY : WorkLane.FINGERPRINT;
	}

	private static Set<PatternPageKey> addInvalidated(Set<PatternPageKey> current, PatternPageKey key) {
		LinkedHashSet<PatternPageKey> changed = new LinkedHashSet<>(current);
		changed.add(key);
		return Set.copyOf(changed);
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
		return new PatternReply(reply.queryId(), reply.generation(), reply.status(),
			reply.pattern() == null ? null : snapshotRecord(reply.pattern()));
	}

	private static boolean validRoot(CompoundTag root) {
		return hasExactKeys(root, PAGE_ORDER, CACHE, FINGERPRINT_CURSOR, GENERATION, COMPLETE, QUERIES, REPLIES)
			&& hasType(root, PAGE_ORDER, Tag.TAG_LIST) && hasType(root, CACHE, Tag.TAG_LIST)
			&& hasType(root, FINGERPRINT_CURSOR, Tag.TAG_INT) && hasType(root, GENERATION, Tag.TAG_LONG)
			&& hasType(root, COMPLETE, Tag.TAG_BYTE) && hasType(root, QUERIES, Tag.TAG_LIST)
			&& hasType(root, REPLIES, Tag.TAG_LIST)
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
