package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Sole incremental owner of recipe page facts. World reads are supplied by an authorized host. */
public final class RecipePageIndex {
	private static final String PAGE_ORDER = "PageOrder";
	private static final String CACHE = "Cache";
	private static final String FINGERPRINT_CURSOR = "FingerprintCursor";
	private static final String GENERATION = "Generation";
	private static final String COMPLETE = "Complete";
	private static final Comparator<PatternPageKey> PAGE_COMPARATOR = Comparator
		.comparing((PatternPageKey key) -> key.shelf().dimension().location().toString())
		.thenComparing(key -> key.shelf().subLevelId(), Comparator.nullsFirst(Comparator.comparing(UUID::toString)))
		.thenComparingInt(key -> key.shelf().localPos().getX())
		.thenComparingInt(key -> key.shelf().localPos().getY())
		.thenComparingInt(key -> key.shelf().localPos().getZ())
		.thenComparingInt(PatternPageKey::slot)
		.thenComparingInt(PatternPageKey::page);

	private volatile RecipeIndexSnapshot snapshot = new RecipeIndexSnapshot(0, false, List.of(), 0, 0, List.of());
	private List<PatternPageKey> pageOrder = List.of();
	private final Map<PatternPageKey, CachedPage> cache = new HashMap<>();
	private int fingerprintCursor;
	private long generation;
	private boolean indexPassComplete = true;
	private List<PatternRecord> records = List.of();
	private List<PatternRecord> recordBuilder = new ArrayList<>();
	private final NavigableMap<PatternPageKey, PatternPageError> errors =
		new TreeMap<>(PAGE_COMPARATOR);
	private int lastFingerprintUnits;
	private final LinkedHashSet<PatternPageKey> invalidatedKeys = new LinkedHashSet<>();
	private int reparsedPageCount;
	private int lastRecordMaintenanceUnits;
	private int lastInvalidationMaintenanceUnits;
	private int lastGenerationTransitionUnits;
	private int lastLoadMembershipChecks;
	private int lastLoadRecordVisits;

	/** O(1) last completed facts; the reindexing flag does not make them current for production. */
	public RecipeIndexSnapshot snapshot() { return snapshot; }
	/** Current complete generation only; never the old browser snapshot or an incomplete prefix. */
	public List<PatternRecord> completeRecords() { return records; }
	public int errorPageCount() { return errors.size(); }

	public void rebuildPageOrder(Collection<PatternPageKey> keys) {
		Objects.requireNonNull(keys, "keys");
		List<PatternPageKey> canonical = canonicalPageOrder(keys);
		pageOrder = List.copyOf(canonical);
		Set<PatternPageKey> retained = new HashSet<>(pageOrder);
		cache.keySet().retainAll(retained);
		errors.keySet().retainAll(retained);
		restartGeneration();
	}

	public static List<PatternPageKey> canonicalPageOrder(Collection<PatternPageKey> keys) {
		Objects.requireNonNull(keys, "keys");
		return keys.stream().map(key -> Objects.requireNonNull(key, "page key"))
			.distinct().sorted(PAGE_COMPARATOR).toList();
	}

	public static boolean isCanonicalPageOrder(List<PatternPageKey> keys) {
		Objects.requireNonNull(keys, "keys");
		for (int index = 0; index < keys.size(); index++) {
			PatternPageKey key = Objects.requireNonNull(keys.get(index), "page key");
			if (index > 0 && PAGE_COMPARATOR.compare(keys.get(index - 1), key) >= 0)
				return false;
		}
		return true;
	}

	public void tick(PageReader pages, PatternJsonParser parser, int totalBudget) {
		if (!pageOrder.isEmpty() && fingerprintCursor == pageOrder.size())
			beginNextFingerprintSweep();
		tickFingerprintChecks(pages, parser, totalBudget);
	}

	public void beginNextFingerprintSweep() {
		if (indexPassComplete && !pageOrder.isEmpty())
			fingerprintCursor = 0;
	}

	public void tickFingerprintChecks(PageReader pages, PatternJsonParser parser, int units) {
		Objects.requireNonNull(pages, "pages");
		Objects.requireNonNull(parser, "parser");
		resetDiagnostics();
		boolean restartRequired = false;
		while (lastFingerprintUnits < Math.max(0, units) && fingerprintEligible()) {
			restartRequired |= fingerprintUnit(pages, parser);
			lastFingerprintUnits++;
		}
		if (restartRequired) restartGeneration();
		else if (indexPassComplete && snapshot.generation() != generation)
			// Publish only after the whole tick has decided not to restart.
			publishCompletedSnapshot();
	}

	public CompoundTag save(HolderLookup.Provider registries) {
		Objects.requireNonNull(registries, "registries");
		CompoundTag root = new CompoundTag();
		ListTag orderTag = new ListTag();
		pageOrder.forEach(key -> orderTag.add(PatternDataCodecs.savePageKey(key)));
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

		return root;
	}

	public static LoadResult load(CompoundTag root, HolderLookup.Provider registries) {
		Objects.requireNonNull(root, "root");
		Objects.requireNonNull(registries, "registries");
		try {
			if (!validRoot(root))
				return new LoadResult(new RecipePageIndex(), true, Recovery.DISCARDED);
			RecipePageIndex index = new RecipePageIndex();
			Corruption corruption = new Corruption();
			boolean restart = false;

			List<PatternPageKey> loadedOrder = new ArrayList<>();
			for (Tag value : (ListTag) root.get(PAGE_ORDER)) {
				if (!(value instanceof CompoundTag child)) {
					corruption.found = true;
					restart = true;
					continue;
				}
				var key = PatternDataCodecs.loadPageKey(child);
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
				return new LoadResult(new RecipePageIndex(), true, Recovery.DISCARDED);

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

			if (restart) index.restartGeneration();
			else if (index.indexPassComplete) index.publishCompletedSnapshot();
			else index.snapshot = index.snapshot.withReindexing(true);
			return new LoadResult(index, corruption.found, restart ? Recovery.REINDEXED : Recovery.UNCHANGED);
		} catch (RuntimeException exception) {
			return new LoadResult(new RecipePageIndex(), true, Recovery.DISCARDED);
		}
	}

	public enum Recovery { UNCHANGED, REINDEXED, DISCARDED }
	public record LoadResult(RecipePageIndex index, boolean hadCorruption, Recovery recovery) {
		public LoadResult { Objects.requireNonNull(index); Objects.requireNonNull(recovery); }
	}

	public List<PatternPageKey> pageOrder() { return pageOrder; }
	public long generation() { return generation; }
	public boolean indexPassComplete() { return indexPassComplete; }
	public int fingerprintCursor() { return fingerprintCursor; }
    /** O(1) read-only lookup of current facts. Records are owned copies, never mutable cache aliases. */
    public PageSnapshot pageSnapshot(PatternPageKey key) {
        if (!indexPassComplete) return new PageSnapshot("reindexing", "", java.util.Optional.empty());
        var page = cache.get(Objects.requireNonNull(key));
        if (page == null) return new PageSnapshot("unindexed", "", java.util.Optional.empty());
        return new PageSnapshot(page.kind().name().toLowerCase(java.util.Locale.ROOT), page.fingerprint(),
            java.util.Optional.ofNullable(page.pattern()).map(RecipePageIndex::snapshotRecord));
    }
    public record PageSnapshot(String status, String fingerprint, java.util.Optional<PatternRecord> record) {}
	public boolean cached(PatternPageKey key) { return cache.containsKey(key); }
	public int cachedPageCount() { return cache.size(); }
	public List<PatternPageError> pageErrors() { return errors.values().stream().limit(RecipeIndexSnapshot.MAX_ERRORS).toList(); }
	public List<UnsupportedPatternEnvelope> unsupportedEnvelopes() {
		return pageOrder.stream().map(cache::get).filter(Objects::nonNull)
			.filter(page -> page.kind() == CacheKind.UNSUPPORTED)
			.map(CachedPage::unsupported).toList();
	}
	public int indexedPatternCount() { return indexPassComplete ? records.size() : recordBuilder.size(); }
	public int lastRecordMaintenanceUnits() { return lastRecordMaintenanceUnits; }
	public int lastInvalidationMaintenanceUnits() { return lastInvalidationMaintenanceUnits; }
	public int lastGenerationTransitionUnits() { return lastGenerationTransitionUnits; }
	public int lastLoadMembershipChecks() { return lastLoadMembershipChecks; }
	public int lastLoadRecordVisits() { return lastLoadRecordVisits; }
	public Set<PatternPageKey> lastInvalidatedKeys() { return Set.copyOf(invalidatedKeys); }
	public int reparsedPageCount() { return reparsedPageCount; }
	public int lastFingerprintUnits() { return lastFingerprintUnits; }

	private boolean fingerprintEligible() {
		return fingerprintCursor < pageOrder.size();
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

	private void restartGeneration() {
		lastGenerationTransitionUnits++;
		generation++;
		fingerprintCursor = 0;
		indexPassComplete = pageOrder.isEmpty();
		records = List.of();
		recordBuilder = new ArrayList<>();
		if (indexPassComplete) publishCompletedSnapshot();
		else snapshot = snapshot.withReindexing(true);
	}

	private void publishCompletedSnapshot() {
		int unsupported = (int) cache.values().stream().filter(page -> page.kind() == CacheKind.UNSUPPORTED).count();
		snapshot = new RecipeIndexSnapshot(generation, false, records, errors.size() - unsupported, unsupported, pageErrors());
	}

	private void putCachedPage(PatternPageKey key, CachedPage page) {
		cache.put(key, page);
		errors.remove(key);
		if (page.kind() == CacheKind.INVALID || page.kind() == CacheKind.UNSUPPORTED)
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

	public void resetDiagnostics() {
		lastFingerprintUnits = 0;
		invalidatedKeys.clear();
		reparsedPageCount = 0;
		lastRecordMaintenanceUnits = 0;
		lastInvalidationMaintenanceUnits = 0;
		lastGenerationTransitionUnits = 0;
	}

	private static CachedPage cachedPage(ParseResult result) {
		if (result instanceof ParseResult.Blank blank)
			return new CachedPage(blank.fingerprint(), CacheKind.BLANK, null, null, null);
		if (result instanceof ParseResult.Valid valid)
			return new CachedPage(valid.fingerprint(), CacheKind.VALID, valid.pattern(), null, null);
		if (result instanceof ParseResult.Unsupported unsupported)
			return new CachedPage(unsupported.fingerprint(), CacheKind.UNSUPPORTED, null,
				unsupported.error(), unsupported.envelope());
		ParseResult.Invalid invalid = (ParseResult.Invalid) result;
		return new CachedPage(invalid.fingerprint(), CacheKind.INVALID, null, invalid.error(), null);
	}

	private static CompoundTag saveCachedPage(PatternPageKey key, CachedPage page,
		HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.put("Page", PatternDataCodecs.savePageKey(key));
		tag.putString("Fingerprint", page.fingerprint());
		tag.putString("State", page.kind().name());
		if (page.kind() == CacheKind.VALID)
			tag.put("Record", PatternDataCodecs.saveRecord(page.pattern(), registries));
		else if (page.kind() == CacheKind.INVALID)
			tag.put("Error", PatternDataCodecs.savePageError(page.error()));
		else if (page.kind() == CacheKind.UNSUPPORTED) {
			tag.put("Error", PatternDataCodecs.savePageError(page.error()));
			tag.put("Envelope", PatternDataCodecs.saveUnsupported(page.unsupported()));
		}
		return tag;
	}

	private static LoadedCachedPage loadCachedPage(CompoundTag tag, HolderLookup.Provider registries) {
		if (!hasType(tag, "Page", Tag.TAG_COMPOUND) || !hasType(tag, "Fingerprint", Tag.TAG_STRING)
			|| !hasType(tag, "State", Tag.TAG_STRING))
			return null;
		var key = PatternDataCodecs.loadPageKey(tag.getCompound("Page"));
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
				? new LoadedCachedPage(pageKey, new CachedPage(tag.getString("Fingerprint"), kind, null, null, null)) : null;
			case VALID -> {
				if (!hasExactKeys(tag, "Page", "Fingerprint", "State", "Record")
					|| !hasType(tag, "Record", Tag.TAG_COMPOUND)) yield null;
				var record = PatternDataCodecs.loadRecord(tag.getCompound("Record"), registries);
				yield record.isPresent() && record.orElseThrow().source().equals(pageKey)
					&& record.orElseThrow().patternId().equals(PatternJsonParser.canonicalPatternId(
						pageKey, tag.getString("Fingerprint")))
					? new LoadedCachedPage(pageKey, new CachedPage(tag.getString("Fingerprint"), kind,
						record.orElseThrow(), null, null)) : null;
			}
			case INVALID -> {
				if (!hasExactKeys(tag, "Page", "Fingerprint", "State", "Error")
					|| !hasType(tag, "Error", Tag.TAG_COMPOUND)) yield null;
				var error = PatternDataCodecs.loadPageError(tag.getCompound("Error"));
				yield error.isPresent() && error.orElseThrow().source().equals(pageKey)
					? new LoadedCachedPage(pageKey, new CachedPage(tag.getString("Fingerprint"), kind,
						null, error.orElseThrow(), null)) : null;
			}
			case UNSUPPORTED -> {
				if (!hasExactKeys(tag, "Page", "Fingerprint", "State", "Error", "Envelope")
					|| !hasType(tag, "Error", Tag.TAG_COMPOUND)
					|| !hasType(tag, "Envelope", Tag.TAG_COMPOUND)) yield null;
				var error = PatternDataCodecs.loadPageError(tag.getCompound("Error"));
				var envelope = PatternDataCodecs.loadUnsupported(tag.getCompound("Envelope"));
				yield error.isPresent() && envelope.isPresent()
					&& error.orElseThrow().source().equals(pageKey)
					&& error.orElseThrow().reason() == PatternErrorReason.UNSUPPORTED_PATTERN_TYPE
					&& envelope.orElseThrow().source().equals(pageKey)
					&& envelope.orElseThrow().fingerprint().equals(tag.getString("Fingerprint"))
					? new LoadedCachedPage(pageKey, new CachedPage(tag.getString("Fingerprint"), kind,
						null, error.orElseThrow(), envelope.orElseThrow())) : null;
			}
		};
	}

	private static PatternRecord snapshotRecord(PatternRecord record) {
		List<PatternOutput> outputs = record.outputs().stream()
			.map(output -> new PatternOutput(new StackKey(output.stack().stack()), output.count())).toList();
		return new PatternRecord(record.patternId(), record.source(), record.inputs(), outputs,
			record.body(), record.priorityOverride());
	}

	private static boolean validRoot(CompoundTag root) {
		return root.getAllKeys().equals(Set.of(PAGE_ORDER, CACHE, FINGERPRINT_CURSOR, GENERATION, COMPLETE))
			&& hasType(root, PAGE_ORDER, Tag.TAG_LIST) && hasType(root, CACHE, Tag.TAG_LIST)
			&& hasType(root, FINGERPRINT_CURSOR, Tag.TAG_INT) && hasType(root, GENERATION, Tag.TAG_LONG)
			&& hasType(root, COMPLETE, Tag.TAG_BYTE) && (root.getByte(COMPLETE) == 0 || root.getByte(COMPLETE) == 1)
			&& isCompoundList((ListTag) root.get(PAGE_ORDER)) && isCompoundList((ListTag) root.get(CACHE));
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

	private enum CacheKind { VALID, BLANK, INVALID, UNSUPPORTED }

	private record CachedPage(String fingerprint, CacheKind kind, @Nullable PatternRecord pattern,
		@Nullable PatternPageError error, @Nullable UnsupportedPatternEnvelope unsupported) {
		private CachedPage {
			fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
			kind = Objects.requireNonNull(kind, "kind");
			if (!isFingerprint(fingerprint)
				|| (kind == CacheKind.VALID) != (pattern != null)
				|| (kind == CacheKind.INVALID || kind == CacheKind.UNSUPPORTED) != (error != null)
				|| (kind == CacheKind.UNSUPPORTED) != (unsupported != null))
				throw new IllegalArgumentException("Invalid cached page");
			if (pattern != null)
				pattern = snapshotRecord(pattern);
		}
	}

	private record LoadedCachedPage(PatternPageKey key, CachedPage page) {}
	private static final class Corruption { private boolean found; }
	@FunctionalInterface
	public interface PageReader { String read(PatternPageKey key); }
}
