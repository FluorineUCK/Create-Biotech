# Pattern Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the reversible librarian-powered Pattern Storage Core, its connected bookshelf multiblock, and a bounded incremental JSON pattern index/search service.

**Architecture:** A pure scanner class validates a same-space connected component without loading chunks. The lower core BE owns the captured librarian snapshot, structure snapshot, page fingerprints, cache, queries, and replies; the upper half has no BE. Page parsing and query scheduling are isolated pure services so budget/fairness/error behavior is unit tested.

**Tech Stack:** Java 21, Gson/DFU codecs, Minecraft writable-book data components, vanilla bookshelf BEs, NeoForge events, Create renderer conventions, JUnit Jupiter, `CBMultiBlockLifecycle`, `CapturedEntityBoxHelper`, `SubLevelCompat`.

## Global Constraints

- Requires the interfaces from `2026-08-14-factory-cluster-foundation.md`.
- The library may contain only one logical core, vanilla bookshelves, and vanilla chiseled bookshelves.
- Limit defaults are exactly 64 logical members, 16 blocks per axis, 600 pages/bookcase, and 600 page checks/tick.
- The lower core connects horizontally and downward; `UP` is reserved for the upper half and never starts a bookshelf edge.
- A librarian snapshot is not inventory and must not be cleared from `Clearable.clearContent()`.
- Source removal with `isMoving=true` must neither release nor delete the librarian snapshot.
- A controlled player break or sneak-wrench is cancelled before block replacement unless exact-UUID release is confirmed. A forced/non-player removal that cannot release the librarian emits a filled, recoverable captured-entity box; the source snapshot is cleared only after `ServerLevel.addFreshEntity` confirms either the librarian or recovery `ItemEntity`. If even the recovery item cannot be accepted, the guarded removal transaction synchronously restores the two-block core and copies the untouched snapshot into the new lower BE rather than lose the entity.
- Removing the upper half always delegates to the lower transaction; a synchronous transaction guard keyed by `(Level identity, lower anchor)` makes lower/upper/restoration callbacks idempotent and is cleared in `finally`. `isMoving=true` is the only path that transfers both halves and the untouched snapshot without release/drop.
- Never load a missing chunk. Existing structures pause on missing known members; new structures form only when the candidate frontier is loaded.
- Pattern NBT is strict and canonical. Malformed list children are isolated and discarded without defaulting fields or discarding valid siblings.
- Server save data and client update data use disjoint root tags. Client updates contain only `LibraryId` plus a bounded renderer/status DTO and exclude complete binding state, complete villager NBT/UUID/trades, page bodies, fingerprints, cache entries, index cursors/generations, active queries, and ready replies.
- No new custom network packet protocol is made in this plan; normal BE update tags carry only the bounded renderer/status projection above.

---

## File Map

**Create**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternPageKey.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/StackKey.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternIngredient.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternOutput.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternRecord.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternPageError.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternValueCodecs.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternJsonParser.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryScanner.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStructureSnapshot.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternQuery.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternReply.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryIndex.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlock.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlockEntity.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreConversionHandler.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreLifecycle.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternCoreClientState.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreRenderer.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternJsonParserTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryScannerTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryIndexTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreLifecycleTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlockEntityTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/cardboardbox/CapturedEntityBoxHelperTest.java`
- `src/main/java/com/nobodiiiii/createbiotech/gametest/PatternLibraryGameTests.java`
- `src/main/resources/assets/create_biotech/blockstates/pattern_storage_core.json`
- `src/main/resources/assets/create_biotech/models/block/pattern_storage_core_lower.json`
- `src/main/resources/assets/create_biotech/models/block/pattern_storage_core_upper.json`
- `src/main/resources/data/create_biotech/loot_table/blocks/pattern_storage_core.json`

**Modify**

- `src/main/java/com/nobodiiiii/createbiotech/content/cardboardbox/CapturedEntityBoxHelper.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlocks.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlockEntityTypes.java`
- `src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java`
- `src/main/java/com/nobodiiiii/createbiotech/client/CreateBiotechClient.java`
- `src/main/resources/assets/create_biotech/lang/en_us.json`
- `src/main/resources/assets/create_biotech/lang/zh_cn.json`

### Task 1: Define Exact Stack and Pattern JSON Types

**Files:**
- Create: `PatternPageKey.java`, `StackKey.java`, `PatternIngredient.java`, `PatternOutput.java`, `PatternRecord.java`, `PatternPageError.java`, `PatternQuery.java`, `PatternReply.java`, `PatternValueCodecs.java`, and `PatternJsonParser.java`.
- Test: `PatternJsonParserTest.java`.

**Interfaces:**
- Consumes: `SpaceAddress`, registry lookup, `DataComponentPredicate.CODEC`, `DataComponentPatch.CODEC`.
- Produces: immutable `PatternRecord` snapshots, page-local parse results, and the only strict canonical save/load boundary for every pattern value later persisted by Tasks 2–5.

- [ ] **Step 1: Write failing parser tests**

Cover the approved schema with exact assertions:

```java
@Test
void parsesVersionOneItemPattern() {
	ParseResult result = parser.parse(PAGE, """
		{"v":1,"in":[{"item":"minecraft:iron_nugget","count":9}],
		 "out":[{"item":"minecraft:iron_ingot","count":1}],"to":"iron_processing"}
		""");
	PatternRecord pattern = assertInstanceOf(ParseResult.Valid.class, result).pattern();
	assertEquals(9, pattern.inputs().getFirst().count());
	assertEquals(Items.IRON_INGOT, pattern.mainOutput().stack().stack().getItem());
	assertEquals("iron_processing", pattern.recipeAddress());
}

@ParameterizedTest
@ValueSource(strings = {
	"{}",
	"{\"v\":2,\"in\":[],\"out\":[],\"to\":\"x\"}",
	"{\"v\":1,\"in\":[{\"item\":\"minecraft:stone\",\"tag\":\"minecraft:stone_crafting_materials\",\"count\":1}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"to\":\"x\"}",
	"{\"v\":1,\"in\":[],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":0}],\"to\":\"x\"}"
})
void invalidPageReturnsLocalError(String json) {
	assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, json));
}

@Test
void everyParseOutcomeCarriesTheSameNormalizedRawFingerprint() {
	String blankFingerprint = sha256LowerHex("   ");
	assertEquals(blankFingerprint,
		assertInstanceOf(ParseResult.Blank.class, parser.parse(PAGE, "   ")).fingerprint());
	assertEquals(sha256LowerHex(VALID_JSON),
		assertInstanceOf(ParseResult.Valid.class, parser.parse(PAGE, VALID_JSON)).fingerprint());
	assertEquals(sha256LowerHex("{"),
		assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, "{")).fingerprint());
}

@Test
void stackKeyAndRecordAccessorsCannotMutateStoredIdentity() {
	ItemStack source = new ItemStack(Items.PAPER, 4);
	source.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
	StackKey key = new StackKey(source);
	ItemStack obtained = key.stack();
	obtained.set(DataComponents.CUSTOM_NAME, Component.literal("B"));
	assertEquals(Component.literal("A"), key.stack().get(DataComponents.CUSTOM_NAME));
	assertEquals(new StackKey(source), key);
}

@Test
void allPersistedValuesRoundTripComponentsAndRejectWrongNbtTypes() {
	PatternRecord record = componentSensitivePattern();
	assertEquals(record, PatternValueCodecs.loadRecord(
		PatternValueCodecs.saveRecord(record, registries), registries).orElseThrow());
	CompoundTag corrupt = PatternValueCodecs.saveRecord(record, registries);
	corrupt.putString("Inputs", "not-a-list");
	assertTrue(PatternValueCodecs.loadRecord(corrupt, registries).isEmpty());
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*PatternJsonParserTest" --offline`

Expected: compilation fails because the pattern types do not exist.

- [ ] **Step 3: Implement normalized exact stack keys**

```java
public record StackKey(ItemStack stack) {
	public StackKey {
		Objects.requireNonNull(stack, "stack");
		if (stack.isEmpty())
			throw new IllegalArgumentException("StackKey cannot be empty");
		stack = stack.copyWithCount(1);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof StackKey key
			&& ItemStack.isSameItemSameComponents(stack, key.stack);
	}

	@Override
	public int hashCode() {
		return ItemStack.hashItemAndComponents(stack);
	}

	@Override
	public ItemStack stack() {
		return stack.copy();
	}
}
```

`PatternIngredient` has exactly one of `ResourceLocation itemId` or `ResourceLocation tagId`, a positive `int count`, and `DataComponentPredicate components`. `matches(ItemStack)` tests selector and `components.test(stack)`. `PatternOutput` has `StackKey stack` and positive `int count`. Every constructor stores `List.copyOf` for list fields; every `ItemStack` boundary copies and normalizes count to one. No accessor exposes a mutable object held by a cache key or record.

- [ ] **Step 4: Implement page provenance and parser errors**

```java
public record PatternPageKey(SpaceAddress shelf, int slot, int page) {
	public PatternPageKey {
		if (slot < 0 || slot >= 6 || page < 0 || page >= 100)
			throw new IllegalArgumentException("Invalid chiseled bookshelf page address");
	}
}

public enum PatternErrorReason {
	JSON, VERSION, INPUT_SELECTOR, COUNT, OUTPUT, ADDRESS, COMPONENTS
}

public record PatternPageError(PatternPageKey source, PatternErrorReason reason, String detail) {
	public PatternPageError {
		detail = StringUtil.truncateStringIfNecessary(detail, 96, false);
	}
}
```

Define the query/reply value types here, rather than reopening them in Task 3:

```java
public record PatternQuery(UUID queryId, UUID requesterComputerId, UUID logisticsId,
	StackKey requestedOutput, long passGeneration, int cursor) {
	public PatternQuery restart(long generation) {
		return new PatternQuery(queryId, requesterComputerId, logisticsId,
			requestedOutput, generation, 0);
	}
}

public enum PatternReplyStatus { MATCH, NOT_FOUND, INVALID_LIBRARY }

public record PatternReply(UUID queryId, long generation, PatternReplyStatus status,
	@Nullable PatternRecord pattern) {}
```

Add `PatternValueCodecs` with exact, registry-aware methods (all loaders return `Optional.empty()` on any missing field, wrong NBT tag type, invalid enum/ID, out-of-range integer, invalid component codec, duplicate child, or trailing/defaulted semantic state):

```java
public static CompoundTag saveStackKey(StackKey value, HolderLookup.Provider registries);
public static Optional<StackKey> loadStackKey(CompoundTag tag, HolderLookup.Provider registries);
public static CompoundTag saveIngredient(PatternIngredient value, HolderLookup.Provider registries);
public static Optional<PatternIngredient> loadIngredient(CompoundTag tag, HolderLookup.Provider registries);
public static CompoundTag saveOutput(PatternOutput value, HolderLookup.Provider registries);
public static Optional<PatternOutput> loadOutput(CompoundTag tag, HolderLookup.Provider registries);
public static CompoundTag savePageKey(PatternPageKey value);
public static Optional<PatternPageKey> loadPageKey(CompoundTag tag);
public static CompoundTag savePageError(PatternPageError value);
public static Optional<PatternPageError> loadPageError(CompoundTag tag);
public static CompoundTag saveRecord(PatternRecord value, HolderLookup.Provider registries);
public static Optional<PatternRecord> loadRecord(CompoundTag tag, HolderLookup.Provider registries);
public static CompoundTag saveQuery(PatternQuery value, HolderLookup.Provider registries);
public static Optional<PatternQuery> loadQuery(CompoundTag tag, HolderLookup.Provider registries);
public static CompoundTag saveReply(PatternReply value, HolderLookup.Provider registries);
public static Optional<PatternReply> loadReply(CompoundTag tag, HolderLookup.Provider registries);
```

`StackKey` uses `ItemStack.CODEC` with `registries.createSerializationContext(NbtOps.INSTANCE)` and verifies decoded count is exactly one. `PatternIngredient` encodes `DataComponentPredicate.CODEC`; `PatternOutput`/`StackKey` encode complete item components; `PatternPageKey` delegates its nested `SpaceAddress` exclusively to `SpaceAddress.save/tryLoad`. Lists require `TAG_LIST` of `TAG_COMPOUND`, preserve order, reject duplicate `PatternPageKey`/`patternId`, and isolate malformed children only at the owning aggregate boundary in Task 3. Individual value loaders are all-or-nothing and never silently substitute defaults.

- [ ] **Step 5: Implement strict JSON parsing**

Use a sealed result:

```java
public sealed interface ParseResult permits ParseResult.Blank, ParseResult.Valid, ParseResult.Invalid {
	record Blank(String fingerprint) implements ParseResult {}
	record Valid(PatternRecord pattern, String fingerprint) implements ParseResult {}
	record Invalid(PatternPageError error, String fingerprint) implements ParseResult {}
}

public record PageInspection(String fingerprint, Optional<ParseResult> changedResult) {}
```

Parsing rules are implemented directly, not through a permissive POJO mapper:

```java
String fingerprint = sha256LowerHex(raw);
if (raw.isBlank()) return new ParseResult.Blank(fingerprint);
JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
if (!root.has("v") || !root.get("v").isJsonPrimitive()
	|| root.get("v").getAsInt() != 1)
	return new ParseResult.Invalid(new PatternPageError(source,
		PatternErrorReason.VERSION, "Expected integer version 1"), fingerprint);
JsonArray inputs = GsonHelper.getAsJsonArray(root, "in");
JsonArray outputs = GsonHelper.getAsJsonArray(root, "out");
String address = GsonHelper.getAsString(root, "to").strip();
if (address.isEmpty() || address.length() > 25)
	return new ParseResult.Invalid(new PatternPageError(source,
		PatternErrorReason.ADDRESS, "Address length must be 1..25"), fingerprint);
```

For input `components`, decode with `DataComponentPredicate.CODEC.parse(registryOps, json)`. For output `components`, decode `DataComponentPatch.CODEC`, create an item stack, and call `applyComponentsAndValidate`. Reject `item`+`tag`, missing selectors, empty outputs, missing main output, non-positive counts, counts above `BigItemStack.INF`, unknown IDs, multiplication overflow, and unknown root/member fields. Fingerprints are lower-case SHA-256 of the exact raw page string (no trim or JSON normalization).

Never derive persisted identity from record/object `toString()`. `patternId` is `UUID.nameUUIDFromBytes(canonicalPatternIdentity(source, fingerprint))`, where `canonicalPatternIdentity` writes these fields in this exact order to a `DataOutputStream`: UTF dimension resource location, boolean `hasSubLevel`, optional two UUID longs, local `x/y/z` ints, slot byte, page byte, and the validated 64-character lower-case ASCII fingerprint. The test saves/reloads `PatternPageKey` and reparses the same raw page, asserting the UUID is unchanged and differs when any address field, slot, page, or fingerprint changes.

`PatternJsonParser.inspect(PatternPageKey source, String raw, @Nullable String cachedFingerprint)` is the index's single-call boundary: it computes SHA-256 exactly once; when equal to the cached fingerprint it returns `PageInspection(fingerprint, Optional.empty())`, otherwise it parses and returns a present `Blank`/`Valid`/`Invalid` carrying that same fingerprint. `parse(source, raw)` delegates to `inspect(source, raw, null)` for tests. No index/page-reader code independently implements or recomputes the fingerprint rule.

- [ ] **Step 6: Run tests and commit**

Run: `./gradlew.bat test --tests "*PatternJsonParserTest" --offline`

Expected: valid schema, all three fingerprint outcomes, component-sensitive defensive immutability, strict all-or-nothing value codec round-trip/rejection, and canonical PatternId identity tests pass. Aggregate sibling isolation is owned only by Task 3's `PatternLibraryIndexTest`.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternJsonParserTest.java
git commit -m "feat: parse factory pattern pages"
```

### Task 2: Implement the Bounded Same-Space Library Scanner

**Files:**
- Create: `PatternLibraryScanner.java`
- Create: `PatternStructureSnapshot.java`
- Test: `PatternLibraryScannerTest.java`

**Interfaces:**
- Produces: `PatternLibraryScanner.scan(View, BlockPos, int, int)` and persistent `PatternStructureSnapshot`.

- [ ] **Step 1: Write scanner boundary tests using a fake view**

```java
@Test
void acceptsExactlySixtyFourMembersAndSixteenSpan() {
	// Lower core is (0,3,0); the other coordinates in x/y/z=0..3 are shelves.
	// Its required upper half is (0,4,0), outside the logical cube. Thus the
	// connected component is exactly 1 lower core + 63 shelves = 64 members.
	BlockPos core = new BlockPos(0, 3, 0);
	FakeLibraryView view = compactCuboid(core, 4, 4, 4).withCoreUpper(core.above());
	ScanResult result = PatternLibraryScanner.scan(view, core, 64, 16);
	assertEquals(StructureState.VALID, result.state());
	assertEquals(64, result.snapshot().members().size());
	assertEquals(4, result.snapshot().axisSpanX());
}

@Test
void rejectsSeventeenPositionAxisSpan() {
	// Positions x=0..16 have inclusive span max-min+1 == 17.
	FakeLibraryView view = connectedLine(CORE, Direction.EAST, 17);
	assertEquals(StructureState.TOO_WIDE,
		PatternLibraryScanner.scan(view, CORE, 64, 16).state());
}

@Test
void rejectsSecondCoreWithoutChoosingByPosition() {
	FakeLibraryView view = connected(CORE, shelf(1, 0, 0), core(2, 0, 0));
	assertEquals(StructureState.CORE_CONFLICT,
		PatternLibraryScanner.scan(view, CORE, 64, 16).state());
}

@Test
void unloadedFrontierPausesInsteadOfBreaking() {
	FakeLibraryView view = connected(CORE, shelf(15, 0, 0)).withChunkUnloaded(1, 0);
	assertEquals(StructureState.PARTIAL,
		PatternLibraryScanner.scan(view, CORE, 64, 16).state());
}

@Test
void upperCoreSideNeverBridgesToShelf() {
	FakeLibraryView view = connected(CORE, coreUpper(0, 1, 0), shelf(1, 1, 0));
	ScanResult result = PatternLibraryScanner.scan(view, CORE, 64, 16);
	assertEquals(StructureState.VALID, result.state());
	assertEquals(List.of(CORE), result.snapshot().members());
}

@Test
void crossSpaceMemberHasExactState() {
	FakeLibraryView view = connected(CORE, shelf(1, 0, 0)).inOtherSpace(1, 0, 0);
	assertEquals(StructureState.SPACE_MISMATCH,
		PatternLibraryScanner.scan(view, CORE, 64, 16).state());
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*PatternLibraryScannerTest" --offline`

Expected: compilation fails because scanner types do not exist.

- [ ] **Step 3: Implement the pure view and result contracts**

```java
public interface View {
	boolean isLoaded(BlockPos pos);
	boolean sameSpace(BlockPos first, BlockPos second);
	MemberKind memberAt(BlockPos pos);
}

public enum MemberKind { NONE, CORE_LOWER, CORE_UPPER, BOOKSHELF, CHISELED_BOOKSHELF }
public enum StructureState {
	UNFORMED, VALID, PARTIAL, TOO_LARGE, TOO_WIDE, CORE_CONFLICT, SPACE_MISMATCH
}
public record ScanResult(StructureState state, @Nullable PatternStructureSnapshot snapshot) {}
```

- [ ] **Step 4: Implement breadth-first scanning**

Seed the queue with the lower core. For the lower core, enqueue only `NORTH`, `SOUTH`, `EAST`, `WEST`, and `DOWN`; for shelves enqueue all six directions. `CORE_UPPER` is a terminal non-member: never add it and never traverse through or sideways from it. Before reading a candidate require `view.isLoaded`; an unloaded frontier returns `PARTIAL`. Call exactly `view.sameSpace(core, candidate)` for every non-`NONE` candidate before admitting it; a false result returns `SPACE_MISMATCH`. The production `View.sameSpace` in Task 5 delegates only to `SubLevelCompat.sameSpace` and performs no manual dimension/sublevel/UUID comparison. Count only the lower core plus shelves as logical members. Stop immediately at member 65 or when any inclusive span `max - min + 1` becomes 17. Count all discovered lower cores and return `CORE_CONFLICT` when the count is not exactly one.

`PatternStructureSnapshot` stores state, immutable ordered member positions, ordinary/chiseled positions, min/max positions, queue count, and the set of chunks containing known members. Add exact NBT `save/load` methods.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew.bat test --tests "*PatternLibraryScannerTest" --offline`

Expected: the compact connected 4x4x4 fixture passes with 64 members; explicit positions `0..16` reject as `TOO_WIDE`; 65-member, second-core, upper-side bridge, `SPACE_MISMATCH`, and partial-frontier cases return their exact states.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryScanner.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStructureSnapshot.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryScannerTest.java
git commit -m "feat: scan connected pattern libraries"
```

### Task 3: Add Incremental Cache, Search Budget, and Fair Reply Queues

**Files:**
- Create: `PatternLibraryIndex.java`
- Test: `PatternLibraryIndexTest.java`

**Interfaces:**
- Consumes: Task-1 `PatternQuery`, `PatternReply`, `PatternValueCodecs`, `PatternPageKey`, `PatternJsonParser`, and Task-2 `PatternStructureSnapshot`.
- Produces: persisted incremental index and `pollReplies(int queueCount)`.

- [ ] **Step 1: Write budget/fairness/cache tests**

```java
@Test
void oneTickNeverReadsMoreThanBudget() {
	FakePages pages = FakePages.withValidPages(600);
	PatternLibraryIndex index = new PatternLibraryIndex();
	index.rebuildPageOrder(pages.keys());
	index.tick(pages, parser, 32);
	assertEquals(32, pages.readCount());
}

@Test
void roundRobinPreventsLargeQueryStarvation() {
	PatternLibraryIndex index = indexedWithOutputs(IRON, GOLD, COPPER);
	index.enqueue(queryFor(DIAMOND));
	index.enqueue(queryFor(GOLD));
	index.tickQueries(4);
	assertEquals(Items.GOLD_INGOT, index.pollReplies(1).getFirst()
		.pattern().mainOutput().stack().stack().getItem());
}

@Test
void sharedSchedulerAlternatesEligibleLanesWithinTotalBudget() {
	PatternLibraryIndex index = fullyIndexed(outputs(IRON, GOLD, COPPER));
	index.beginNextFingerprintSweep();
	index.enqueue(queryFor(DIAMOND));
	index.tick(pages, parser, 6);
	assertEquals(List.of(FINGERPRINT, QUERY, FINGERPRINT, QUERY, FINGERPRINT, QUERY),
		index.lastLaneTrace());
	assertEquals(3, index.lastFingerprintUnits());
	assertEquals(3, index.lastQueryUnits());
	assertEquals(6, index.lastTotalUnits());
}

@Test
void changingOneFingerprintInvalidatesOnlyOnePage() {
	PatternLibraryIndex index = fullyIndexed(twoPages());
	pages.change(PAGE_A, replacementJson);
	index.tick(pages, parser, 2);
	assertEquals(1, index.reparsedPageCount());
	assertTrue(index.cached(PAGE_B));
}

@Test
void unchangedBlankIsCachedAndBlankEditInvalidatesExactlyItsKey() {
	PatternLibraryIndex index = fullyIndexed(blankAndValidPages());
	long reads = pages.readCount();
	index.tick(pages, parser, 2);
	assertEquals(reads + 2, pages.readCount());
	assertEquals(0, parser.parseCountSinceReset());
	pages.change(BLANK_PAGE, validGoldJson);
	index.tick(pages, parser, 2);
	assertEquals(1, parser.parseCountSinceReset());
	assertEquals(Set.of(BLANK_PAGE), index.lastInvalidatedKeys());
}

@Test
void missWaitsForCompleteStableGeneration() {
	PatternLibraryIndex index = new PatternLibraryIndex();
	index.rebuildPageOrder(List.of(PAGE_A, PAGE_B));
	index.enqueue(queryFor(DIAMOND));
	index.tick(pages, parser, 3); // initial index is not yet stable
	assertTrue(index.pollReplies(1).isEmpty());
	index.tick(pages, parser, 3);
	assertEquals(PatternReplyStatus.NOT_FOUND,
		index.pollReplies(1).getFirst().status());
}

@Test
void lateMatchConsumesOneComparisonPerCursorAdvance() {
	PatternLibraryIndex index = fullyIndexed(outputs(IRON, COPPER, GOLD));
	index.enqueue(queryFor(GOLD));
	index.tickQueries(2);
	assertTrue(index.pollReplies(1).isEmpty());
	index.tickQueries(1);
	assertEquals(new StackKey(new ItemStack(Items.GOLD_INGOT)),
		index.pollReplies(1).getFirst().pattern().mainOutput().stack());
}

@Test
void rebuildDuringPassRestartsQueryWithoutPrematureMiss() {
	PatternLibraryIndex index = fullyIndexed(outputs(IRON, COPPER));
	index.enqueue(queryFor(GOLD));
	index.tickQueries(1);
	index.rebuildPageOrder(outputs(IRON, COPPER, GOLD).keys());
	assertEquals(0, index.activeQueries().getFirst().cursor());
	assertEquals(index.generation(), index.activeQueries().getFirst().passGeneration());
	assertTrue(index.pollReplies(1).isEmpty());
}

@Test
void latePageChangeRestartsFingerprintSweepAtFirstKey() {
	PatternLibraryIndex index = fullyIndexed(pages(PAGE_A, PAGE_B, PAGE_C));
	index.beginNextFingerprintSweep();
	index.tickFingerprintChecks(pages, parser, 2);
	pages.change(PAGE_C, validGoldJson);
	long oldGeneration = index.generation();
	index.tickFingerprintChecks(pages, parser, 1);
	assertEquals(oldGeneration + 1, index.generation());
	assertFalse(index.indexPassComplete());
	assertEquals(0, index.fingerprintCursor());
	index.tickFingerprintChecks(pages, parser, 1);
	assertEquals(PAGE_A, pages.lastReadKey());
}

@Test
void twoNonMatchingQueriesAlternateAndBothComplete() {
	PatternLibraryIndex index = fullyIndexed(outputs(IRON, COPPER));
	index.enqueue(queryFor(GOLD));
	index.enqueue(queryFor(DIAMOND));
	index.tickQueries(4);
	assertEquals(List.of(PatternReplyStatus.NOT_FOUND, PatternReplyStatus.NOT_FOUND),
		index.pollReplies(2).stream().map(PatternReply::status).toList());
}

@Test
void corruptNestedEntriesAreIsolatedDuringLoad() {
	CompoundTag saved = indexWithTwoCachedPagesAndTwoQueries().save(registries);
	saved.getList("Cache", Tag.TAG_COMPOUND).getCompound(0)
		.putString("Page", "wrong-type");
	saved.getList("Queries", Tag.TAG_COMPOUND).getCompound(0)
		.remove("QueryId");
	PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, registries);
	assertEquals(1, loaded.index().cachedPageCount());
	assertEquals(1, loaded.index().activeQueryCount());
	assertTrue(loaded.hadCorruption());
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*PatternLibraryIndexTest" --offline`

Expected: compilation fails because the index does not exist.

- [ ] **Step 3: Use the Task-1 query/reply codecs without duplicating persistence**

```java
PatternQuery query = PatternValueCodecs.loadQuery(queryTag, registries).orElseThrow();
PatternReply reply = PatternValueCodecs.loadReply(replyTag, registries).orElseThrow();
```

Task 3 must not add ad-hoc NBT methods to those records. A `MATCH` reply constructs a new `PatternRecord` through its immutable constructor; returned cache snapshots and list accessors are `List.copyOf`, and `StackKey.stack()` returns a defensive copy.

- [ ] **Step 4: Implement the incremental engine**

`PatternLibraryIndex` owns:

```java
List<PatternPageKey> pageOrder;
Map<PatternPageKey, CachedPage> cache;
int fingerprintCursor;
long generation;
boolean indexPassComplete;
ArrayDeque<PatternQuery> activeQueries;
ArrayDeque<PatternReply> readyReplies;
WorkLane nextLane;
TickStats lastTickStats;
```

Package-private test diagnostics are exact and bounded:

```java
enum WorkLane { FINGERPRINT, QUERY }
record TickStats(int fingerprintUnits, int queryUnits, List<WorkLane> lanes) {
	int totalUnits() { return fingerprintUnits + queryUnits; }
}
```

`lanes` contains at most the current call's configured budget and is replaced, not accumulated, after each call. `lastLaneTrace()`, `lastFingerprintUnits()`, `lastQueryUnits()`, and `lastTotalUnits()` delegate to the latest `TickStats` for tests and are not persisted.

`pageOrder` is an immutable, lexicographically ordered `PatternPageKey` list. A generation is stable only after the fingerprint cursor has visited every key in that exact order without structure/page-order replacement. One budget unit performs exactly one raw page read followed by one `PatternJsonParser.inspect` call (whose changed result, if present, is cached in that same unit), or one cached valid-record comparison. Blank and invalid outcomes cache their parser-provided fingerprint; the unchanged page is read/inspected on later sweeps but never reparsed. The index never hashes page text itself.

Queries never compare against a mutating map. At enqueue, a query uses the current `generation` but cannot advance until `indexPassComplete=true`. The generation exposes an immutable ordered list of valid record snapshots sorted by `PatternPageKey`; `cursor` is the index of the next record. Each query unit compares exactly one record, increments cursor once, and rotates that query to the tail. A match emits `MATCH` immediately. `NOT_FOUND` is emitted only after cursor equals the stable record-list size while `query.passGeneration == generation` and `indexPassComplete=true`. Empty stable generations therefore emit a miss in one scheduling unit, not during enqueue.

Any page-order change, page fingerprint change (including a change found at the last key of an in-progress sweep), cache-entry removal/corruption, or structure replacement performs the same atomic restart: increment `generation`, set `indexPassComplete=false`, set `fingerprintCursor=0`, rebuild the immutable record view, discard only ready replies tagged with an older generation, and restart every active query at cursor zero with the new generation. The new generation becomes complete only after keys `0..pageOrder.size()-1` are revisited from the beginning without another change. No stale pass may emit `MATCH` or `NOT_FOUND`.

Expose package-private deterministic test seams `tickQueries(int units)` and `tickFingerprintChecks(PageReader, PatternJsonParser, int units)`; neither spends work from the other lane. Production `tick(PageReader, PatternJsonParser, int totalBudget)` owns the shared cap and alternates fingerprint/query lanes only when both are eligible, carrying the next-lane bit across ticks; an ineligible lane yields its turn without consuming a unit. Within the query lane, rotate after every comparison. The mandatory starvation test therefore supplies four query-only units, while the separate shared-scheduler test proves alternating lanes and `fingerprintUnits + queryUnits <= totalBudget`. This guarantees two nonmatching queries differ by at most one comparison and prevents initial incomplete indexing from producing a premature miss. `pollReplies(queueCount)` preserves completion order and returns at most `max(0, queueCount)`, where production passes `1 + ordinaryBookshelfCount`.

Persist page order, generation, completion flag, fingerprints, valid/blank/error cache entries, fingerprint cursor, active queries with pass generation/cursor, and ready replies by calling only `PatternValueCodecs`. `PatternLibraryIndex.load` returns `LoadResult(index, hadCorruption)`. A malformed cached page/query/reply is discarded individually; a malformed page-order child is discarded and forces a generation rebuild; valid siblings survive. The owning BE marks itself changed when `hadCorruption=true`; world loading never throws.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew.bat test --tests "*PatternLibraryIndexTest" --offline`

Expected: exact total budget, four-unit query fairness, eligible-lane alternation, late match, complete stable miss, initial-incomplete delay, late-page generation restart from key zero, two-query fairness, blank caching, local invalidation, component-sensitive immutable snapshots, strict save/load, and corrupt-child isolation pass.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryIndex.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryIndexTest.java
git commit -m "feat: index pattern books incrementally"
```

### Task 4: Register and Form the Two-Block Pattern Storage Core

**Files:**
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlock.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlockEntity.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreConversionHandler.java`
- Create: `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreLifecycle.java`
- Create: `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreLifecycleTest.java`
- Create: `src/test/java/com/nobodiiiii/createbiotech/content/cardboardbox/CapturedEntityBoxHelperTest.java`
- Modify: `src/main/java/com/nobodiiiii/createbiotech/content/cardboardbox/CapturedEntityBoxHelper.java`
- Modify: `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlocks.java`
- Modify: `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlockEntityTypes.java`
- Modify: `src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java`
- Create: `src/main/resources/assets/create_biotech/blockstates/pattern_storage_core.json`
- Create: `src/main/resources/assets/create_biotech/models/block/pattern_storage_core_lower.json`
- Create: `src/main/resources/assets/create_biotech/models/block/pattern_storage_core_upper.json`
- Create: `src/main/resources/data/create_biotech/loot_table/blocks/pattern_storage_core.json`
- Modify: `src/main/resources/assets/create_biotech/lang/en_us.json`
- Modify: `src/main/resources/assets/create_biotech/lang/zh_cn.json`

**Interfaces:**
- Consumes: foundation cluster contracts and Tasks 1–3.
- Produces: registered `create_biotech:pattern_storage_core` with reversible librarian snapshot.

- [ ] **Step 1: Write failing helper, conversion, and lossless-removal tests**

In `CapturedEntityBoxHelperTest`, cover preserving/no-collision, preserving/collision refusal, reseeding/collision, and captured-health restoration for both successful modes. Put `PatternStorageCoreLifecycleTest` in the exact same Java package as production, `com.nobodiiiii.createbiotech.content.factorycluster.pattern`, so it can use package-private `PatternStorageCoreLifecycle.SpawnSink` without exporting a test API. Cover adult librarian conversion plus child, unemployed/other-profession, wandering-trader, and zombie-villager rejection without held-box/lectern mutation. Assert blocked controlled break leaves both halves/snapshot; entity rejection plus accepted box drop clears only after box confirmation; upper removal delegates once; and calling the lifecycle with the actual `isMoving=true` argument leaves the serialized snapshot byte-for-byte unchanged while invoking neither entity nor item spawn. For forced double failure, call the full block override and assert after it returns that disposition-driven super suppression preserved the new lower BE and its byte-identical snapshot.

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `./gradlew.bat test --tests "*CapturedEntityBoxHelperTest" --tests "*PatternStorageCoreLifecycleTest" --offline`

Expected: compilation fails because the exact-UUID helper, core lifecycle types, and `SpawnSink` seam do not exist.

- [ ] **Step 3: Add exact-UUID entity creation to the shared box helper**

Keep the existing public behavior and add:

```java
public static Entity createCapturedEntityPreservingUuid(ItemStack stack, Level level) {
	return createCapturedEntity(stack, level, false);
}

private static Entity createCapturedEntity(ItemStack stack, Level level, boolean reseedOnCollision) {
	CompoundTag entityData = getCapturedEntityData(stack);
	if (entityData == null)
		return null;
	CompoundTag loadData = entityData.copy();
	if (level instanceof ServerLevel serverLevel && hasUuidCollision(serverLevel.getServer(), loadData)) {
		if (!reseedOnCollision)
			return null;
		reseedEntityTree(loadData);
	}
	Entity entity = EntityType.loadEntityRecursive(loadData, level, Function.identity());
	CompoundTag stackTag = CBItemData.getOrEmpty(stack);
	if (entity instanceof LivingEntity living
		&& stackTag.contains(CAPTURED_ENTITY_HEALTH_TAG, Tag.TAG_ANY_NUMERIC))
		living.setHealth(Math.min(living.getMaxHealth(),
			stackTag.getFloat(CAPTURED_ENTITY_HEALTH_TAG)));
	return entity;
}
```

The existing `createCapturedEntity(stack, level)` delegates with `true`. Machine release uses the preserving method so a duplicated original UUID refuses release instead of silently creating a second identity. Extend the existing helper test suite with both collision branches and a non-default captured-health assertion: reseeding changes the UUID and restores the exact clamped health, preserving refuses on collision, and preserving without collision retains UUID and the same health.

- [ ] **Step 4: Register the block and lower-only BE**

Register `PATTERN_STORAGE_CORE` in `CBBlocks` and `CBBlockEntityTypes`. Do not register a normal item. The block extends `BaseEntityBlock`, implements `IWrenchable` and `CBMultiBlockLifecycle.Part`, defines `HORIZONTAL_FACING` plus `DOUBLE_BLOCK_HALF`, returns a BE only for `LOWER`, returns `PushReaction.BLOCK`, and schedules delayed completeness checks through the committed `CBMultiBlockLifecycle.scheduleValidation` API. `CBMultiBlockLifecycle.Part` supplies only multiblock type/anchor attachment; it is not the owner of removal or movement callbacks.

- [ ] **Step 5: Implement atomic lectern conversion**

At `PlayerInteractEvent.RightClickBlock` high priority, require:

```java
state.is(Blocks.LECTERN)
&& level.getBlockEntity(pos) instanceof LecternBlockEntity lectern
&& lectern.getBook().isEmpty()
&& PatternStorageCoreBlock.hasSpaceForUpperHalf(level, pos)
```

Create a temporary entity from the held box and require an adult `Villager` whose profession holder is `VillagerProfession.LIBRARIAN`. On the server, copy the filled box to `snapshot`, place lower and upper states, obtain the new lower BE, call `installLibrarianSnapshot(snapshot)`, then call `CapturedEntityBoxHelper.clearCapturedEntity(heldStack)`. If either block placement or BE acquisition fails, restore the original lectern state and serialized lectern BE data and leave the held stack unchanged.

- [ ] **Step 6: Implement one lossless lower-half release transaction**

The BE stores `ItemStack librarianSnapshotBox`, `boolean pendingSafeRelease`, and the structure/index state. It never implements `Clearable`.

`findSafeRelease` tries centers of `above`, four horizontal neighbors, `below`, then radius-two positions; each candidate must be within world border and pass `level.noCollision(entity, movedBounds)`. Put transaction orchestration in package-private `PatternStorageCoreLifecycle`; the BE owns data, while the lifecycle service owns block replacement/recovery and exposes package-private `SpawnSink { boolean add(Entity entity); }` only for same-package JUnit injection.

The production owner is the actual block override:

```java
@Override
public void onRemove(BlockState state, Level level, BlockPos pos,
	BlockState newState, boolean isMoving) {
	RemovalDisposition disposition = PatternStorageCoreLifecycle.onRemove(
		state, level, pos, newState, isMoving,
		entity -> level instanceof ServerLevel server && server.addFreshEntity(entity));
	if (disposition == RemovalDisposition.CALL_SUPER)
		super.onRemove(state, level, pos, newState, isMoving);
}
```

`PatternStorageCoreLifecycle.onRemove` returns the exact package-private enum `RemovalDisposition { CALL_SUPER, RESTORED }`. Moving, irrelevant/orphan, active-guard recursive callbacks, and successful entity/recovery-box removal return `CALL_SUPER`; the block then invokes inherited removal exactly once. Only the forced double-failure reconstruction path returns `RESTORED`, and the block must skip the outer `super.onRemove` so it cannot delete the newly installed lower BE.

`PatternStorageCoreLifecycle` guards every controlled, forced, upper-delegated, and restoration replacement with a `ThreadLocal<Set<RemovalKey>>`, where `RemovalKey` compares `Level` by `==` and stores the immutable lower anchor. The service adds the `(level identity, anchor)` key before mutation and removes it in `finally`; when the set becomes empty it calls `ThreadLocal.remove()`. A recursive callback from replacing either half—including callbacks observed by a newly created lower BE during restoration—sees the same active key and returns without release/drop. Never store this guard as a boolean/epoch on the removed BE.

Controlled player destruction and sneak-wrench have separate real entry points and neither recursively fires a break event:

1. `PatternStorageCoreConversionHandler` subscribes to NeoForge's already-fired `BlockEvent.BreakEvent` at `EventPriority.LOWEST`, `receiveCanceled=false`. For either half it resolves the lower anchor, cancels that event, then calls `PatternStorageCoreLifecycle.controlledPlayerBreak`; it never constructs/posts another `BreakEvent`.
2. `PatternStorageCoreBlock.onSneakWrenched` directly calls `PatternStorageCoreLifecycle.controlledWrench` on the server and returns `SUCCESS`; it does not call `IWrenchable.super`, post a break event, or allow a later default removal.
3. Each controlled method creates the exact-UUID librarian, finds a safe point, and calls the production `SpawnSink`.
4. On decode, UUID, safe-point, or spawn-confirmation failure, it sets `pendingSafeRelease=true` and leaves both halves/snapshot unchanged. The already-fired player event remains canceled; wrench returns without mutation.
5. After confirmed spawn, the guarded transaction clears the snapshot and completes all block changes itself. Player break manually emits exactly one lectern loot result and removes both halves with suppressed core drops; wrench removes upper and replaces lower with `Blocks.LECTERN` without a lectern item. No vanilla/default removal is left pending, so later cancellation cannot produce a spawned librarian beside an uncleared core.
6. The entity already contains its original AI/interaction NBT and captured health from the shared helper.

Forced/non-player removal includes explosions, `/setblock`, piston-like callbacks not marked moving, and any lower `onRemove` reached without the pre-authorized player token. If exact release cannot be confirmed, copy the still-filled `librarianSnapshotBox` into one recoverable `ItemEntity` at the lower-core center and call `ServerLevel.addFreshEntity`. Clear the BE snapshot only after that call returns true.

If both entity and recovery item are rejected, the guarded `RESTORED` path is ordered exactly: retain `ItemStack originalSnapshot = oldLower.snapshot().copy()` in a local variable; explicitly call `level.removeBlockEntity(anchor)` to remove the old lower BE; rebuild lower then upper states while the `(level, anchor)` ThreadLocal key is active; obtain the newly created lower `PatternStorageCoreBlockEntity`; install a byte-identical copy of `originalSnapshot`; verify both states/new BE before returning `RESTORED`. The outer block override then skips `super.onRemove`. Every exit, including reconstruction failure, clears the ThreadLocal key in `finally`. Never clear merely because block replacement began. A successful direct entity spawn emits no box; a successful box drop emits no entity.

Upper-half player/forced removal obtains the lower BE and delegates to that same anchor transaction before changing either half. An orphan upper with no loaded lower only removes the orphan and never fabricates or clears a snapshot. `PatternStorageCoreBlock.onRemove(BlockState, Level, BlockPos, BlockState, boolean)` passes the real `isMoving` flag to the lifecycle, which returns immediately when it is true without snapshot mutation, spawn, drop, or counterpart removal. Create/Sable attachment still comes from `CBMultiBlockLifecycle.Part`; Create serializes the lower BE into its movement payload by its normal machinery. The same-package Task-4 JUnit supplies `true` directly and verifies this branch; Task 6 does not pretend ordinary `setBlock` is a movement callback.

- [ ] **Step 7: Run the focused lifecycle tests to green**

Run: `./gradlew.bat test --tests "*CapturedEntityBoxHelperTest" --tests "*PatternStorageCoreLifecycleTest" --offline`

Expected: helper UUID/health branches and all lossless transaction branches pass. The double-failure test calls the complete production `PatternStorageCoreBlock.onRemove` override against a level fixture whose entity and recovery-item additions both return false; only after that override returns does it assert the replacement lower BE still exists, both halves are restored, and the new BE snapshot is byte-identical to the saved original.

- [ ] **Step 8: Add resources and compile**

Lower model:

```json
{"parent":"minecraft:block/lectern"}
```

Upper model:

```json
{"elements":[]}
```

The loot table has one lectern entry conditioned on `half=lower`; upper drops nothing. Add pickaxe/axe mining tags and bilingual names/status text.

Run: `./gradlew.bat compileJava --offline`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern src/main/java/com/nobodiiiii/createbiotech/content/cardboardbox/CapturedEntityBoxHelper.java src/main/java/com/nobodiiiii/createbiotech/registry src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java src/main/resources/assets/create_biotech src/main/resources/data/create_biotech/loot_table/blocks/pattern_storage_core.json
git commit -m "feat: add librarian pattern storage core"
```

### Task 5: Integrate Structure, Books, Search Budget, Binding, and Renderer

**Files:**
- Modify: `PatternStorageCoreBlockEntity.java`
- Create: `PatternStorageCoreRenderer.java`
- Create: `PatternCoreClientState.java`
- Modify: `CreateBiotechClient.java:135-170`
- Test: `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlockEntityTest.java`

**Interfaces:**
- Consumes: all earlier tasks.
- Produces: live pattern core service for the task runtime plan.

- [ ] **Step 1: Write failing BE adapter, binding, and serialization-boundary tests**

In `PatternStorageCoreBlockEntityTest`, add the six-writable-books/600-key and Efficiency-book cases; fresh-unbound registration, prepare purity, no-fail index-rekey commit, and query-gate-with-supplied-`BindingAccess` cases; server-save round-trip/corrupt-child isolation; strict `PatternCoreClientState` tests for defaults, above-default values, absolute `1024/1024/10000` maxima, and one-above rejection; exact client root/allowlist assertions; valid client `LibraryId` replacement; and malformed-ID atomic rejection named in Steps 3–6 below. Public bind/authority transfer and real server `bindingAccess` are owned by Task 6.

- [ ] **Step 2: Run the focused BE test and confirm failure**

Run: `./gradlew.bat test --tests "*PatternStorageCoreBlockEntityTest" --offline`

Expected: compilation fails because the production adapters, binding lifecycle, and split serialization projection are absent.

- [ ] **Step 3: Implement production scanner/page adapters**

The scanner view maps lower/upper core, `Blocks.BOOKSHELF`, and `Blocks.CHISELED_BOOKSHELF`; `isLoaded` calls `CBMultiBlockLifecycle.isLoaded`; `sameSpace` calls `SubLevelCompat.sameSpace(level, corePos, candidate)`.

For every chiseled bookshelf slot:

```java
ItemStack stack = shelf.getItem(slot);
if (stack.is(Items.WRITABLE_BOOK)) {
	WritableBookContent book = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT,
		WritableBookContent.EMPTY);
	for (int page = 0; page < 100; page++)
		pageKeys.add(new PatternPageKey(SpaceAddress.capture(level, shelfPos), slot, page));
}
```

Pages beyond `book.pages().size()` read as blank. Efficiency enchanted books add `level * level + 1` and no pages. Obtain Efficiency from the enchantment registry and `DataComponents.STORED_ENCHANTMENTS`.

Add adapter tests that a chiseled bookshelf containing six writable books exposes exactly `6 * 100 == 600` distinct `PatternPageKey`s, including blank logical pages past each book's current content, and that swapping slot/page/address changes key identity. An Efficiency enchanted book exposes zero page keys and contributes only its budget bonus.

- [ ] **Step 4: Implement server tick budgets**

Base budgets are `{1:8, 2:16, 3:32, 4:64, 5:128}`. Every server tick compute:

```java
int budget = Math.min(CBConfigs.SERVER.factoryCluster.patternMaxPagesPerTick.get(),
	baseBudget(librarianLevel) + efficiencyBonus);
int queues = 1 + structure.ordinaryBookshelves().size();
index.tick(pageReader, parser, budget);
List<PatternReply> replies = index.pollReplies(queues);
```

Structure changes rebuild page order; partial chunks retain the previous snapshot/index and stop search advancement. A 20-tick lazy scan checks structure membership; the incremental fingerprint cursor detects page edits without scanning all pages in one tick.

- [ ] **Step 5: Implement cluster membership**

The lower BE implements the committed Foundation `ClusterMember` API exactly:

```java
private UUID libraryId = UUID.randomUUID();
@Nullable private ClusterBinding bindingState; // newly converted core is unbound
private boolean bindingStateValid = true;

public UUID memberId();
public @Nullable ClusterBinding bindingState();
public boolean hasValidBindingState();
public ClusterMemberType memberType(); // PATTERN_CORE
public SpaceAddress memberAddress();
public boolean canRebind();
public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed);
public void commitClusterBinding(ClusterBinding prepared);
```

`canRebind()` is true only when binding NBT is valid and the index has no active queries or ready replies. `prepareClusterBinding` is side-effect-free: reject null authority/invalid local state as `IDENTITY`, active queues as `ACTIVE`, over-32 bindings as `CAPACITY`, and—when already bound—lower revision or same-revision unequal state as `REVISION`; accept an authoritative proposed state for an unbound core and accept an equal idempotent state. `commitClusterBinding` never calls prepare and has no refusal/exception branch: with a server it calls `ClusterMemberIndex.rebind(server, this, () -> applyBindingState(prepared))`, otherwise it directly assigns; then it marks dirty and syncs. Assignment is the only mutation inside the rebind callback, so after successful prepare commit cannot fail and the index cannot retain the old cluster key.

`initialize()` calls `super.initialize()` then `ClusterMemberIndex.register(server, this)`; that method only indexes a bound identity and calls `ClusterBindingService.reconcileLoaded`. Reconciliation may adopt an existing authoritative state but never chooses a new authority or increments revision. A newly converted unbound core remains unbound/unindexed until a player uses the public `ClusterBindingService.bind(ServerPlayer, ClusterMember, ClusterMember)` transaction to merge a bound panel with the core; Foundation then selects the pattern core as authority and commits the new revision. Merely registering an unbound or newly loaded coordinator does not demote the core. Authority transfers to a coordinator only when a second public bind transaction merges the bound panel/core cluster with that coordinator; its successful prepare/commit transaction creates the higher revision and selects coordinator authority. `invalidate()` unregisters before `super.invalidate()`. The BE never self-promotes, self-demotes, rewrites revisions, or invokes Foundation's package-private test overloads.

Every server entry to `enqueueQuery` first requires a non-null valid binding, no structural/binding conflict from `ClusterMemberIndex.conflicts`, and `ClusterBindingService.bindingAccess(server, this) == READY`; only then verify `query.logisticsId()` occurs in the reconciled binding and accept it. `AUTHORITY_OFFLINE` and `CONFLICT` return false without mutating queues. This gate is repeated at service time before advancing queries, so losing authority after enqueue pauses rather than answers from stale state.

The package-private pure seam used by the unit test is exact: `static boolean bindingAllowsQuery(ClusterBindingService.BindingAccess access, @Nullable ClusterBinding state, UUID logisticsId)`. Production passes the result of the real public `bindingAccess` call; the helper returns true only for `READY`, non-null state, and a contained logistics UUID.

In `PatternStorageCoreBlockEntityTest`, follow `FactoryPanelBlockEntityTest`'s bootstrap helper and Foundation's fake-member style only for local prepare/commit mechanics. Assert: fresh core has null binding and registration alone does not invent authority; `prepare` failure leaves state/index unchanged; a successful prepared commit rekeys from old cluster to new cluster with exactly one index entry and no post-prepare failure; and the extracted package-private query-gate predicate accepts only `BindingAccess.READY` plus matching cluster/logistics state. Task 6 separately proves both public player bind transactions and the real server `bindingAccess` path against actual members.

Expose these runtime methods with exact signatures:

```java
public StructureState structureState();
public PatternStructureSnapshot structureSnapshot();
public int searchBudget();
public int queueCount();
public List<PatternPageError> pageErrors();
public boolean enqueueQuery(PatternQuery query);
public List<PatternReply> drainReplies(int maxReplies);
```

- [ ] **Step 6: Implement disjoint save and client-sync projections**

Use `SmartBlockEntity.write(CompoundTag, HolderLookup.Provider, boolean clientPacket)` as the single branch point. With `clientPacket == false`, the root contains exactly `LibraryId`, optional strict `BindingState`, `BindingStateInvalid`, and `ServerState`; `ServerState` contains the authoritative filled `LibrarianSnapshotBox`, last valid `PatternStructureSnapshot`, partial/status flags, and `PatternLibraryIndex.save(registries)` (page order, fingerprints, records/errors, cursors/generation, queries, replies). Do not persist raw writable-book JSON: it remains in the book item held by the bookshelf.

With `clientPacket == true`, the root contains exactly `LibraryId` and `ClientState`; it contains neither `BindingState` nor `BindingStateInvalid`. `ClientState` is the strict bounded projection of:

```java
public record PatternCoreClientState(boolean renderLibrarian,
	ResourceLocation villagerType, int librarianLevel, @Nullable String customName,
	StructureState structureState, boolean pendingSafeRelease,
	int logicalMembers, int ordinaryBookshelves, int chiseledBookshelves,
	int searchBudget, int queueCount) {}
```

The codec accepts only the exact keys `RenderLibrarian`, `VillagerType`, `LibrarianLevel`, optional `CustomName`, `StructureState`, `PendingSafeRelease`, `LogicalMembers`, `OrdinaryBookshelves`, `ChiseledBookshelves`, `SearchBudget`, and `QueueCount`. It bounds plain-text `CustomName` to 64 code points and librarian level to `1..5`. Counts use the committed config safety maximum rather than defaults: logical/ordinary/chiseled counts are `0..1024` (`libraryMaxMembers` maximum); queue count is `0..1024`, because `queueCount = 1 + ordinaryBookshelves` and a valid structure has at most 1024 logical members including its one core, hence at most 1023 ordinary shelves; search budget is `0..10000` (`patternMaxPagesPerTick` maximum). Wrong types/out-of-range values reject the DTO rather than clamp. Tests prove above-default values `65` members, `66` queues, and `601` budget decode; absolute maxima `1024/1024/10000` decode; and `1025/1025/10001` reject. The server derives the DTO from the snapshot but sends no complete entity tag, UUID, attributes, inventory, Brain, `Offers`/trades, recipes, AI flags, or complete `ClusterBinding`.

`read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket)` accepts only `ServerState` when `clientPacket=false` and invokes the strict codecs; isolated corrupt index children mark changed. A corrupt librarian snapshot sets `pendingSafeRelease` and preserves the raw box tag for recovery rather than clearing it. With `clientPacket=true`, it first requires the exact two-key root, strictly decodes `LibraryId` as an NBT UUID and `ClientState` as a valid `PatternCoreClientState`, and mutates nothing if either decode fails. After both succeed it sets the client BE's `libraryId` to the decoded UUID and applies renderer/status fields. It does not overwrite server-only binding, librarian snapshot, index, active queries, or ready replies. Add a test starting with a different client `libraryId`, reading a valid packet, and asserting the client identity becomes the transmitted ID while seeded server-only fields remain unchanged; malformed `LibraryId` leaves all fields unchanged.

The client serialization regression asserts `clientTag.getAllKeys().equals(Set.of("LibraryId", "ClientState"))` and `ClientState.getAllKeys()` equals the allowlist above (minus optional `CustomName` when absent). It seeds distinctive server binding UUID/revision, villager UUID, `Offers`, Brain/AI/inventory tags, cached record/fingerprint, cursor/generation, active query, reply, and raw JSON; recursive inspection must find none of `BindingState`, `BindingStateInvalid`, `LibrarianSnapshotBox`, `UUID`, `Offers`, `Recipes`, `Brain`, `Inventory`, `ServerState`, `PatternIndex`, `PageOrder`, `Fingerprint`, `Cache`, `Queries`, `Replies`, `Cursor`, or the raw JSON. The complementary `write(serverTag, registries, false)` round-trips authoritative state and contains no `ClientState`.

- [ ] **Step 7: Render the librarian only from the lower BE**

Register `PatternStorageCoreRenderer`. Cache one client-only display villager reconstructed solely from `PatternCoreClientState`: apply the allowlisted villager type, fixed librarian profession, level, and optional plain custom name. Never reconstruct from a captured-entity NBT snapshot. Render it above the lectern using `EntityRenderDispatcher`, following the pose stack/light pattern in `EvokerEnchantingChamberRenderer`. Do not add a server entity. `shouldRenderOffScreen` is true only for the lower BE and the render AABB covers both halves.

- [ ] **Step 8: Run all pattern tests and commit**

Run:

```powershell
./gradlew.bat test --tests "*pattern*" --offline
./gradlew.bat build --offline
git diff --check
```

Expected: parser/scanner/index/core tests, 600-page identity, binding lifecycle/gating/rekey, client-sync exclusion, server-save round-trip, and full build pass; no whitespace errors.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern src/main/java/com/nobodiiiii/createbiotech/client/CreateBiotechClient.java
git commit -m "feat: activate incremental pattern libraries"
```

### Task 6: Pattern Library Game-Test Gate

**Files:**
- Create: `src/main/java/com/nobodiiiii/createbiotech/gametest/PatternLibraryGameTests.java`

**Interfaces:**
- Produces: server-world coverage for conversion, boundary structure behavior, and entity conservation.

- [ ] **Step 1: Add required empty-template tests**

Annotate the class with `@GameTestHolder(CreateBiotech.MOD_ID)` and `@PrefixGameTestTemplate(false)`. Add `@GameTest(templateNamespace = "minecraft", template = "empty")` tests for:

```java
static void librarianConversionAndRelease(GameTestHelper helper);
static void blockedUpperHalfRefusesConversion(GameTestHelper helper);
static void sixtyFourMemberBoundary(GameTestHelper helper);
static void seventeenPositionAxisIsTooWide(GameTestHelper helper);
static void upperSideShelfDoesNotBridge(GameTestHelper helper);
static void secondCoreConflicts(GameTestHelper helper);
static void blockedControlledBreakKeepsCoreAndSnapshot(GameTestHelper helper);
static void forcedBlockedRemovalDropsRecoverableSnapshot(GameTestHelper helper);
static void upperRemovalDelegatesExactlyOnce(GameTestHelper helper);
static void foundationBindReconcileAndAccessGate(GameTestHelper helper);
```

`sixtyFourMemberBoundary` builds the same connected lower-core-plus-63-shelves 4x4x4 logical cube from Task 2 and asserts inclusive spans remain 4. `seventeenPositionAxisIsTooWide` uses member coordinates `x=0..16` and asserts `TOO_WIDE`. `upperSideShelfDoesNotBridge` places a shelf beside the upper half with no lower-core edge and asserts it is absent from the snapshot.

`blockedControlledBreakKeepsCoreAndSnapshot` fills every candidate AABB and has `GameTestHelper.makeMockServerPlayerInLevel().gameMode.destroyBlock(upperPos)` execute the real server player-break path; NeoForge fires the subscribed `BlockEvent.BreakEvent`, and the test asserts the production subscriber cancels before replacement with both halves and exact snapshot present. `forcedBlockedRemovalDropsRecoverableSnapshot` blocks every safe entity AABB and performs a real server `setBlock` replacement of the lower half; the production five-argument `onRemove` with `isMoving=false` must emit exactly one filled recovery-box `ItemEntity`, no librarian, and no duplicate lectern. `upperRemovalDelegatesExactlyOnce` performs a real server replacement of the upper state and asserts the world contains exactly one original-UUID librarian or one recovery box—not both or duplicates—after the delegated lower transaction. GameTests never import or invoke package-private `SpawnSink`/lifecycle methods.

The `isMoving=true` branch is deliberately absent here because vanilla/GameTest `setBlock` supplies `false` and `CBMultiBlockLifecycle.Part` has no removal callback. Its executable proof is the same-package Task-4 JUnit calling `PatternStorageCoreLifecycle.onRemove(oldState, level, anchor, newState, true, spawnSink)` and verifying no mutation/spawn/drop; Task 6 contains only behavior reachable through real world/event paths.

`foundationBindReconcileAndAccessGate` runs with the real server, real `ClusterMemberIndex`, actual panel/core BEs, a server player authorized for one fixture Create logistics frequency, and a nested fake `ClusterMember` of type `COMPUTER_COORDINATOR` (the Computer block belongs to the later plan). First register the unbound core and assert registration/reconciliation leaves it unbound and chooses no authority. Call the public `ClusterBindingService.bind(serverPlayer, panel, core)` and assert revision 1, pattern authority, and `bindingAccess == READY`. Registering the unbound fake coordinator alone must leave that authority/revision unchanged. Then call the public `ClusterBindingService.bind(serverPlayer, panel, coordinator)`, assert a higher revision with coordinator authority, verify the core is a READY replica and the index has only the committed cluster key. Finally unregister the coordinator, assert `AUTHORITY_OFFLINE`, and verify enqueue plus query advancement refuse without queue/cursor mutation. Neither transaction uses Foundation's package-private overloads.

Every test schedules assertions with `helper.runAfterDelay` and calls `helper.succeed()` only after conservation checks. Direct release requires exactly one original-UUID librarian and the appropriate one-lectern outcome; recovery requires exactly one filled captured-entity box whose nested entity UUID/health equals the source snapshot.

- [ ] **Step 2: Run GameTest**

Run: `./gradlew.bat runGameTestServer --offline`

Expected: all ten real-world pattern-library tests pass and the server exits successfully; controlled/forced/upper paths neither duplicate nor lose the librarian snapshot, while Task 4 owns the isolated moving-flag proof.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/gametest/PatternLibraryGameTests.java
git commit -m "test: cover pattern library lifecycle"
```
