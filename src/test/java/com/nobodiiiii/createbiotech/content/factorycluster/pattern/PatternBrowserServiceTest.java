package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserEntry;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeIndexSnapshot;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibrarySummary;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class PatternBrowserServiceTest {
    static final SpaceAddress CORE = new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO);
    static final RecipeSourceLocation SOURCE = new RecipeSourceLocation(CORE.dimension(), CORE.subLevelId(), CORE.localPos());
    static final UUID LIBRARY = UUID.randomUUID();
    static final BrowserFence FENCE = new BrowserFence(UUID.randomUUID(), SOURCE, LIBRARY, 7);
    @BeforeAll static void bootstrap() { PatternLibraryIndexTest.bootstrapMinecraftRegistries(); Items.AIR.getDefaultInstance(); }
    static PatternBrowserEntry entry(int i) {
        return new PatternBrowserEntry(new PatternRecord(new UUID(0, i),
            new PatternPageKey(new RecipeSourceLocation(CORE.dimension(), CORE.subLevelId(), CORE.localPos()), i / 100, i % 100),
            List.of(new PatternIngredient(null, ResourceLocation.parse("forge:plates"), 1, DataComponentPredicate.EMPTY)),
            List.of(new PatternOutput(new StackKey(Items.IRON_INGOT.getDefaultInstance()), 2)),
            new StandardPatternBody("Assembly Hall"), OptionalInt.of(9)));
    }
    static RecipeIndexSnapshot snapshot(int count) {
        return new RecipeIndexSnapshot(7, false, IntStream.range(0, count).mapToObj(PatternBrowserServiceTest::entry).map(PatternBrowserEntry::pattern).toList(), 0, 0, List.of());
    }
    static PatternBrowserService service(RecipeIndexSnapshot snapshot) {
        return new PatternBrowserService(FENCE, () -> snapshot);
    }
    @Test void filtersCachedFactsByItemTagAddressAndTypeWithRootLocale() {
        var service = service(snapshot(40));
        var old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            for (String query : List.of("MINECRAFT:IRON", "FORGE:PLATES", "ASSEMBLY", "STANDARD")) {
                var page = service.page(FENCE, new PatternBrowserFilter(query, Optional.empty(), Optional.empty()), 0);
                assertEquals(40, page.totalMatches()); assertEquals(32, page.entries().size());
            }
            assertTrue(service.page(FENCE, new PatternBrowserFilter("missing", Optional.empty(), Optional.empty()), 0).entries().isEmpty());
        } finally { Locale.setDefault(old); }
    }
    @Test void ghostRetainsExactOutputComponentsAndDoesNotInventTagInputs() {
        var stack = Items.IRON_INGOT.getDefaultInstance();
        var exact = new StackKey(stack);
        assertEquals(1, service(snapshot(1)).page(FENCE, new PatternBrowserFilter("forge:plates", Optional.of(exact), Optional.empty()), 0).entries().size());
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("different"));
        assertTrue(service(snapshot(1)).page(FENCE, new PatternBrowserFilter("", Optional.of(new StackKey(stack)), Optional.empty()), 0).entries().isEmpty());
        assertTrue(service(snapshot(1)).page(FENCE, new PatternBrowserFilter("", Optional.of(new StackKey(Items.GOLD_INGOT.getDefaultInstance())), Optional.empty()), 0).entries().isEmpty());
        assertTrue(service(snapshot(1)).page(FENCE, new PatternBrowserFilter("", Optional.empty(), Optional.of(PatternKind.SEQUENCED)), 0).entries().isEmpty());
    }
    @Test void stockKeeperPrefixesAndExactDisplayNamesAreSearchable() {
        var original = entry(0).pattern();
        var stack = Items.IRON_INGOT.getDefaultInstance();
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Precision Alloy"));
        var named = new PatternBrowserEntry(new PatternRecord(original.patternId(), original.source(),
            original.inputs(), List.of(new PatternOutput(new StackKey(stack), 2)), original.body(), OptionalInt.empty()));
        var browser = service(new RecipeIndexSnapshot(7, false, List.of(named.pattern()), 0, 0, List.of()));
        for (String query : List.of("@minecraft", "#forge:plates", "precision alloy")) {
            var page = browser.page(FENCE, new PatternBrowserFilter(query, Optional.empty(), Optional.empty()), 0);
            assertEquals(1, page.totalMatches(), query);
            assertEquals(new StackKey(stack), page.entries().getFirst().mainOutput());
        }
        assertTrue(browser.page(FENCE, new PatternBrowserFilter("@forge", Optional.empty(), Optional.empty()), 0).entries().isEmpty());
    }
    @Test void localizedClientItemHintsAreOnlyFiltersAndCannotBeMutatedAfterConstruction() {
        var matches = new BitSet(); matches.set(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(Items.IRON_INGOT));
        var filter = new PatternBrowserFilter("铁锭", Optional.empty(), Optional.empty(), matches);
        matches.clear(); filter.localizedItems().clear();
        assertEquals(1, service(snapshot(1)).page(FENCE, filter, 0).totalMatches());
        assertEquals(0, service(snapshot(1)).page(FENCE,
            new PatternBrowserFilter("金锭", Optional.empty(), Optional.empty()), 0).totalMatches());
    }
    @Test void pageBoundsAndSelectionCannotEscapeCurrentPage() {
        var service = service(snapshot(40));
        assertEquals(8, service.page(FENCE, PatternBrowserFilter.EMPTY, 1).entries().size());
        assertEquals(PatternBrowserPage.Status.INVALID_PAGE, service.page(FENCE, PatternBrowserFilter.EMPTY, -1).status());
        assertEquals(PatternBrowserPage.Status.INVALID_PAGE, service.page(FENCE, PatternBrowserFilter.EMPTY, Integer.MAX_VALUE).status());
        assertTrue(service.page(FENCE, PatternBrowserFilter.EMPTY, 0, Optional.of(new UUID(0, 35))).detail().isEmpty());
        assertEquals(new UUID(0, 2), service.page(FENCE, PatternBrowserFilter.EMPTY, 0, Optional.of(new UUID(0, 2))).detail().orElseThrow().patternId());
        assertThrows(IllegalArgumentException.class, () -> new PatternBrowserFilter("x".repeat(65), Optional.empty(), Optional.empty()));
    }
    @Test void staleRefreshDoesNotMixGenerationAndWrongIdentityIsDenied() {
        var current = new AtomicReference<>(snapshot(2));
        var service = new PatternBrowserService(FENCE, current::get);
        current.set(new RecipeIndexSnapshot(8, true, List.of(entry(3).pattern()), 0, 0, List.of()));
        var stale = service.page(FENCE, PatternBrowserFilter.EMPTY, 0);
        assertEquals(PatternBrowserPage.Status.STALE, stale.status()); assertEquals(8, stale.fence().generation()); assertTrue(stale.entries().isEmpty());
        assertEquals(1, service.page(FENCE.withGeneration(8), PatternBrowserFilter.EMPTY, 0).entries().size());
        for (var bad : List.of(new BrowserFence(UUID.randomUUID(), SOURCE, LIBRARY, 8), new BrowserFence(FENCE.sessionId(), SOURCE, UUID.randomUUID(), 8),
            new BrowserFence(FENCE.sessionId(), new RecipeSourceLocation(Level.NETHER, null, BlockPos.ZERO), LIBRARY, 8)))
            assertEquals(PatternBrowserPage.Status.DENIED, service.page(bad, PatternBrowserFilter.EMPTY, 0).status());
    }
    @Test void repeatedOpenCloseAndPagingCannotTouchProductionIndexAccounting() {
        var index = new LibraryIndexScenario();
        index.enqueue(new PatternQuery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new StackKey(Items.IRON_INGOT.getDefaultInstance()), 0, 0));
        index.enqueue(new PatternQuery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new StackKey(Items.GOLD_INGOT.getDefaultInstance()), 0, 0));
        var before = index.save(RegistryAccess.EMPTY);
        for (int i = 0; i < 100; i++) {
            var opened = new PatternBrowserService(FENCE, index::recipeSnapshot);
            opened.page(FENCE.withGeneration(0), PatternBrowserFilter.EMPTY, 0);
            opened.page(FENCE.withGeneration(0), PatternBrowserFilter.EMPTY, Integer.MAX_VALUE);
        }
        assertEquals(before, index.save(RegistryAccess.EMPTY));
        assertEquals(2, index.activeQueryCount()); assertEquals(0, index.readyReplyCount()); assertEquals(0, index.lastFingerprintUnits());
    }

    @Test void paidOverlayUsesExistingOwnerProcessProofAndDoesNotAllocateSlots() {
        UUID cluster = UUID.randomUUID(), goalId = UUID.randomUUID(), processId = UUID.randomUUID(), computerId = UUID.randomUUID();
        var profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
        var pattern = entry(0);
        var goal = new GoalRecord(goalId, cluster, new GoalSubmission(goalId, pattern.patternId(), 1, InputMode.BULK),
            new CreateRouteKey(UUID.randomUUID(), "coordination"), new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
        var process = new ProcessRecord(processId, goalId, computerId, 0, profile, ProcessKind.PATTERN_PRIORITY_LEASE, ProcessState.RUNNABLE, null, 0, Map.of());
        var state = new ClusterEngineState(cluster, UUID.randomUUID(), 1, 1, 0, 0, Map.of(goalId, goal), Map.of(goalId, 0L),
            Map.of(goalId, ProcessKind.PATTERN_PRIORITY_LEASE), Map.of(processId, process),
            List.of(new ClusterEngineState.MemberSnapshot(computerId, profile, true, 1, 1)), Map.of(), Set.of(), 0, null)
            .withCoordinationAssociation(new CoordinationAssociation(processId, LIBRARY, pattern.patternId(), 7));
        var proofs = PatternPriorityAuthority.fromAuthoritativeState(state);
        var service = new PatternBrowserService(FENCE, () -> snapshot(2), 4, cluster, () -> proofs);
        var before = state.processes();
        for (int i = 0; i < 100; i++) {
            var rows = service.page(FENCE, PatternBrowserFilter.EMPTY, 0).entries();
            assertTrue(rows.getFirst().overrideActive()); assertEquals(9, rows.getFirst().effectivePriority());
            assertFalse(rows.get(1).overrideActive()); assertEquals(4, rows.get(1).effectivePriority());
        }
        assertSame(before, state.processes()); assertEquals(1, state.processes().size());
        var wrongOwner = new PatternBrowserService(FENCE, () -> snapshot(1), 4, UUID.randomUUID(), () -> proofs);
        assertFalse(wrongOwner.page(FENCE, PatternBrowserFilter.EMPTY, 0).entries().getFirst().overrideActive());
        var nextSnapshot = new RecipeIndexSnapshot(8, false, List.of(pattern.pattern()), 0, 0, List.of());
        var next = new PatternBrowserService(FENCE, () -> nextSnapshot, 4, cluster, () -> proofs);
        assertFalse(next.page(FENCE.withGeneration(8), PatternBrowserFilter.EMPTY, 0).entries().getFirst().overrideActive());
    }

    @Test void stageOnlyInputItemAndAddressAreSearchableWithoutFabricatingAnExactStack() {
        var input = new PatternIngredient(ResourceLocation.parse("minecraft:gold_ingot"), null, 1, DataComponentPredicate.EMPTY);
        var original = entry(0).pattern();
        var sequenced = new PatternBrowserEntry(new PatternRecord(original.patternId(), original.source(), original.inputs(), original.outputs(),
            new SequencedPatternBody(List.of(new PatternStage(0, "Press Depot", List.of(input)), new PatternStage(1, "Final Depot", List.of()))), OptionalInt.empty()));
        var service = service(new RecipeIndexSnapshot(7, true, List.of(sequenced.pattern()), 1, 0, List.of()));
        for (var term : List.of("GOLD_INGOT", "PRESS DEPOT", "FINAL", "sequenced")) {
            var page = service.page(FENCE, new PatternBrowserFilter(term, Optional.empty(), Optional.of(PatternKind.SEQUENCED)), 0);
            assertEquals(1, page.entries().size()); assertTrue(page.reindexing()); assertEquals(1, page.invalidPages());
        }
    }
}
