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
- Never load a missing chunk. Existing structures pause on missing known members; new structures form only when the candidate frontier is loaded.
- No packet or network protocol changes are made in this plan.

---

## File Map

**Create**

- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternPageKey.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/StackKey.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternIngredient.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternOutput.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternRecord.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternPageError.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternJsonParser.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryScanner.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStructureSnapshot.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternQuery.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternReply.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryIndex.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlock.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreBlockEntity.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreConversionHandler.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStorageCoreRenderer.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternJsonParserTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryScannerTest.java`
- `src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryIndexTest.java`
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
- Create: value/parser files listed above through `PatternJsonParser.java`.
- Test: `PatternJsonParserTest.java`.

**Interfaces:**
- Consumes: `SpaceAddress`, registry lookup, `DataComponentPredicate.CODEC`, `DataComponentPatch.CODEC`.
- Produces: immutable `PatternRecord` snapshots and page-local parse errors.

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
}
```

`PatternIngredient` has exactly one of `ResourceLocation itemId` or `ResourceLocation tagId`, a positive `int count`, and `DataComponentPredicate components`. `matches(ItemStack)` tests selector and `components.test(stack)`. `PatternOutput` has `StackKey stack` and positive `int count`.

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

- [ ] **Step 5: Implement strict JSON parsing**

Use a sealed result:

```java
public sealed interface ParseResult permits ParseResult.Blank, ParseResult.Valid, ParseResult.Invalid {
	record Blank() implements ParseResult {}
	record Valid(PatternRecord pattern, String fingerprint) implements ParseResult {}
	record Invalid(PatternPageError error, String fingerprint) implements ParseResult {}
}
```

Parsing rules are implemented directly, not through a permissive POJO mapper:

```java
if (raw.isBlank()) return new ParseResult.Blank();
JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
if (GsonHelper.getAsInt(root, "v") != 1) return invalid(VERSION, ...);
JsonArray inputs = GsonHelper.getAsJsonArray(root, "in");
JsonArray outputs = GsonHelper.getAsJsonArray(root, "out");
String address = GsonHelper.getAsString(root, "to").strip();
if (address.isEmpty() || address.length() > 25) return invalid(ADDRESS, ...);
```

For input `components`, decode with `DataComponentPredicate.CODEC.parse(registryOps, json)`. For output `components`, decode `DataComponentPatch.CODEC`, create an item stack, and call `applyComponentsAndValidate`. Reject `item`+`tag`, missing selectors, empty outputs, missing main output, non-positive counts, counts above `BigItemStack.INF`, unknown IDs, and multiplication overflow. Fingerprints are lower-case SHA-256 of the raw page string. `patternId` is `UUID.nameUUIDFromBytes((source + fingerprint).getBytes(StandardCharsets.UTF_8))`.

- [ ] **Step 6: Run tests and commit**

Run: `./gradlew.bat test --tests "*PatternJsonParserTest" --offline`

Expected: valid schema passes; each malformed page returns one local error without throwing.

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
	FakeLibraryView view = lineOfBookshelves(63, CORE);
	ScanResult result = PatternLibraryScanner.scan(view, CORE, 64, 16);
	assertEquals(StructureState.VALID, result.state());
	assertEquals(64, result.snapshot().members().size());
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
public enum StructureState { UNFORMED, VALID, PARTIAL, TOO_LARGE, TOO_WIDE, CORE_CONFLICT }
public record ScanResult(StructureState state, @Nullable PatternStructureSnapshot snapshot) {}
```

- [ ] **Step 4: Implement breadth-first scanning**

Seed the queue with the lower core. For the lower core, enqueue only `NORTH`, `SOUTH`, `EAST`, `WEST`, and `DOWN`; for shelves enqueue all six directions. Before reading a candidate require `view.isLoaded`; an unloaded frontier returns `PARTIAL`. Require `view.sameSpace(core, candidate)` for every member. Count lower+upper core as one logical member by never enqueuing upper. Stop immediately at member 65 or any axis span 17. Count all discovered cores and return `CORE_CONFLICT` when the count is not exactly one.

`PatternStructureSnapshot` stores state, immutable ordered member positions, ordinary/chiseled positions, min/max positions, queue count, and the set of chunks containing known members. Add exact NBT `save/load` methods.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew.bat test --tests "*PatternLibraryScannerTest" --offline`

Expected: 64/16 edges pass; 65/17, second core, cross-space and partial-frontier cases return their exact states.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryScanner.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternStructureSnapshot.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryScannerTest.java
git commit -m "feat: scan connected pattern libraries"
```

### Task 3: Add Incremental Cache, Search Budget, and Fair Reply Queues

**Files:**
- Create: `PatternQuery.java`
- Create: `PatternReply.java`
- Create: `PatternLibraryIndex.java`
- Test: `PatternLibraryIndexTest.java`

**Interfaces:**
- Consumes: `PatternPageKey`, `PatternJsonParser`, `PatternStructureSnapshot`.
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
	index.tick(pages, parser, 3);
	assertEquals(GOLD, index.pollReplies(1).getFirst().pattern().mainOutput().stack());
}

@Test
void changingOneFingerprintInvalidatesOnlyOnePage() {
	PatternLibraryIndex index = fullyIndexed(twoPages());
	pages.change(PAGE_A, replacementJson);
	index.tick(pages, parser, 2);
	assertEquals(1, index.reparsedPageCount());
	assertTrue(index.cached(PAGE_B));
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew.bat test --tests "*PatternLibraryIndexTest" --offline`

Expected: compilation fails because the index does not exist.

- [ ] **Step 3: Implement persisted query/reply records**

```java
public record PatternQuery(UUID queryId, UUID requesterComputerId, UUID logisticsId,
	StackKey requestedOutput, int cursor) {}

public enum PatternReplyStatus { MATCH, NOT_FOUND, INVALID_LIBRARY }

public record PatternReply(UUID queryId, PatternReplyStatus status,
	@Nullable PatternRecord pattern) {}
```

Add NBT save/load to both records. A `PatternRecord` is copied into `MATCH`; it is never a pointer back to a mutable cache entry.

- [ ] **Step 4: Implement the incremental engine**

`PatternLibraryIndex` owns:

```java
List<PatternPageKey> pageOrder;
Map<PatternPageKey, CachedPage> cache;
int fingerprintCursor;
ArrayDeque<PatternQuery> activeQueries;
ArrayDeque<PatternReply> readyReplies;
```

One budget unit performs exactly one page fingerprint/read/parse check or one cached record comparison. Rotate the active query deque after each unit. `pollReplies(queueCount)` returns at most `queueCount`, where production passes `1 + ordinaryBookshelfCount`. Rebuilding page order retains cache entries whose keys remain present; a changed SHA-256 replaces only that key. Blank/error pages remain cached and consume no repeated parse work until fingerprint change.

Persist page order, fingerprints, valid records, errors, cursors, active queries, and ready replies. On decode failure discard only the affected cache/query entry and mark the BE changed; do not fail world loading.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew.bat test --tests "*PatternLibraryIndexTest" --offline`

Expected: budget, fairness, local invalidation, immutable snapshot and save/load round-trip tests pass.

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternQuery.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternReply.java src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryIndex.java src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern/PatternLibraryIndexTest.java
git commit -m "feat: index pattern books incrementally"
```

### Task 4: Register and Form the Two-Block Pattern Storage Core

**Files:**
- Create: core block/BE/conversion handler.
- Modify: `CapturedEntityBoxHelper`, registries, tags, resources.

**Interfaces:**
- Consumes: foundation cluster contracts and Tasks 1–3.
- Produces: registered `create_biotech:pattern_storage_core` with reversible librarian snapshot.

- [ ] **Step 1: Add exact-UUID entity creation to the shared box helper**

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
	return EntityType.loadEntityRecursive(loadData, level, Function.identity());
}
```

The existing `createCapturedEntity(stack, level)` delegates with `true`. Machine release uses the preserving method so a duplicated original UUID refuses release instead of silently creating a second identity.

- [ ] **Step 2: Register the block and lower-only BE**

Register `PATTERN_STORAGE_CORE` in `CBBlocks` and `CBBlockEntityTypes`. Do not register a normal item. The block extends `BaseEntityBlock`, implements `IWrenchable` and `CBMultiBlockLifecycle.Part`, defines `HORIZONTAL_FACING` plus `DOUBLE_BLOCK_HALF`, returns a BE only for `LOWER`, returns `PushReaction.BLOCK`, and schedules delayed completeness checks exactly through `CBMultiBlockLifecycle`.

- [ ] **Step 3: Implement atomic lectern conversion**

At `PlayerInteractEvent.RightClickBlock` high priority, require:

```java
state.is(Blocks.LECTERN)
&& level.getBlockEntity(pos) instanceof LecternBlockEntity lectern
&& lectern.getBook().isEmpty()
&& PatternStorageCoreBlock.hasSpaceForUpperHalf(level, pos)
```

Create a temporary entity from the held box and require an adult `Villager` whose profession holder is `VillagerProfession.LIBRARIAN`. On the server, copy the filled box to `snapshot`, place lower and upper states, obtain the new lower BE, call `installLibrarianSnapshot(snapshot)`, then call `CapturedEntityBoxHelper.clearCapturedEntity(heldStack)`. If either block placement or BE acquisition fails, restore the original lectern state and serialized lectern BE data and leave the held stack unchanged.

- [ ] **Step 4: Implement release and wrench lifecycle**

The BE stores `ItemStack librarianSnapshotBox`, `boolean pendingSafeRelease`, and the structure/index state. It never implements `Clearable`.

`findSafeRelease` tries centers of `above`, four horizontal neighbors, `below`, then radius-two positions; each candidate must be within world border and pass `level.noCollision(entity, movedBounds)`. Controlled sneak-wrench:

1. fire `BlockEvent.BreakEvent`;
2. create exact-UUID librarian;
3. refuse and set `pendingSafeRelease=true` when no safe point exists;
4. add the entity and confirm success;
5. clear the snapshot;
6. remove upper silently and replace lower with `Blocks.LECTERN`;
7. restore original AI/interaction NBT already present in the box snapshot.

Normal lower removal with `isMoving=false` uses the same exact release transaction and lets the loot table drop one lectern. Removal with `isMoving=true` performs no release and does not mutate the snapshot.

- [ ] **Step 5: Add resources and compile**

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

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/pattern src/main/java/com/nobodiiiii/createbiotech/content/cardboardbox/CapturedEntityBoxHelper.java src/main/java/com/nobodiiiii/createbiotech/registry src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java src/main/resources/assets/create_biotech src/main/resources/data/create_biotech/loot_table/blocks/pattern_storage_core.json
git commit -m "feat: add librarian pattern storage core"
```

### Task 5: Integrate Structure, Books, Search Budget, Binding, and Renderer

**Files:**
- Modify: `PatternStorageCoreBlockEntity.java`
- Create: `PatternStorageCoreRenderer.java`
- Modify: `CreateBiotechClient.java:135-170`

**Interfaces:**
- Consumes: all earlier tasks.
- Produces: live pattern core service for the task runtime plan.

- [ ] **Step 1: Implement production scanner/page adapters**

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

- [ ] **Step 2: Implement server tick budgets**

Base budgets are `{1:8, 2:16, 3:32, 4:64, 5:128}`. Every server tick compute:

```java
int budget = Math.min(CBConfigs.SERVER.factoryCluster.patternMaxPagesPerTick.get(),
	baseBudget(librarianLevel) + efficiencyBonus);
int queues = 1 + structure.ordinaryBookshelves().size();
index.tick(pageReader, parser, budget);
List<PatternReply> replies = index.pollReplies(queues);
```

Structure changes rebuild page order; partial chunks retain the previous snapshot/index and stop search advancement. A 20-tick lazy scan checks structure membership; the incremental fingerprint cursor detects page edits without scanning all pages in one tick.

- [ ] **Step 3: Implement cluster membership**

The lower BE implements `ClusterMember` with stable `libraryId`, optional `clusterId`, normalized bindings, `PATTERN_CORE` type, and `canRebind()` false while active queries/replies exist. `initialize/invalidate` register/unregister in `ClusterMemberIndex`. `applyClusterBinding` uses the same unregister-update-register pattern as Factory Panel.

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

- [ ] **Step 4: Persist and sync state**

Save librarian snapshot, identity/bindings, last valid structure snapshot, partial/status flags, page cache/fingerprints/cursors, active queries, and replies. Client packets include only renderer/status data, never all page JSON. A corrupt cache entry is discarded locally; a corrupt librarian snapshot marks `pendingSafeRelease` and is never silently cleared.

- [ ] **Step 5: Render the librarian only from the lower BE**

Register `PatternStorageCoreRenderer`. Cache one client-only villager reconstructed from the snapshot; render it above the lectern using `EntityRenderDispatcher`, following the pose stack/light pattern in `EvokerEnchantingChamberRenderer`. Do not add a server entity. `shouldRenderOffScreen` is true only for the lower BE and the render AABB covers both halves.

- [ ] **Step 6: Run all pattern tests and commit**

Run:

```powershell
./gradlew.bat test --tests "*pattern*" --offline
./gradlew.bat build --offline
git diff --check
```

Expected: parser/scanner/index tests and full build pass; no whitespace errors.

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
static void secondCoreConflicts(GameTestHelper helper);
static void movingRemovalKeepsSnapshot(GameTestHelper helper);
```

Each test places blocks/entities, schedules assertions with `helper.runAfterDelay`, and calls `helper.succeed()` only after counting exactly one restored librarian and one lectern where required.

- [ ] **Step 2: Run GameTest**

Run: `./gradlew.bat runGameTestServer --offline`

Expected: all five required pattern-library tests pass and the server exits successfully.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/nobodiiiii/createbiotech/gametest/PatternLibraryGameTests.java
git commit -m "test: cover pattern library lifecycle"
```
