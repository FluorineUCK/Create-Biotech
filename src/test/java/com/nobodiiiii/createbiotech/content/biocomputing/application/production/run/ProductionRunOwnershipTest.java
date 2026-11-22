package com.nobodiiiii.createbiotech.content.biocomputing.application.production.run;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionRunOwnershipTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var bootstrap = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrap.setAccessible(true); bootstrap.setBoolean(null, true);
        Items.AIR.getDefaultInstance(); // Vanilla items must exist before the first archive decode.
    }

    @Test void oldActiveAndCompleteCursorArchivesRestoreWithoutExpandingHistory() throws Exception {
        var original = fixture("active");
        var sequence = CurrentSchemaStateNbt.load(original, RegistryAccess.EMPTY,
            ProductionRun.RunSequence.class).orElseThrow();
        assertEquals(Long.MAX_VALUE, sequence.remainingRuns());
        assertEquals(original, CurrentSchemaStateNbt.save(sequence, RegistryAccess.EMPTY));
        var first = sequence.currentRun();
        var next = sequence.afterOutputSealed(first);
        assertEquals(Long.MAX_VALUE - 1, next.remainingRuns());
        assertNotEquals(first.runId(), next.currentRun().runId());
        assertEquals(first.goalId(), next.currentRun().goalId());
        assertEquals(first.manifest(), next.currentRun().manifest());
        assertEquals(first.fence(), next.currentRun().fence());
        assertEquals(first.outputPolicyGeneration(), next.currentRun().outputPolicyGeneration());
        assertThrows(IllegalArgumentException.class, () -> next.afterOutputSealed(first));
        var emptyArchive = fixture("complete");
        var completed = CurrentSchemaStateNbt.load(emptyArchive, RegistryAccess.EMPTY,
            ProductionRun.RunSequence.class).orElseThrow();
        assertEquals(ProductionRun.RunSequence.completed(), completed);
        assertEquals(emptyArchive, CurrentSchemaStateNbt.save(completed, RegistryAccess.EMPTY));
        assertThrows(IllegalStateException.class, () -> completed.afterOutputSealed(first));
    }

    @Test void relocatedRuntimeNamesDoNotReplaceAnyHistoricalRootOrNestedIdentity() throws Exception {
        for (String name : List.of("run", "active")) {
            Class<?> expected = name.equals("run") ? ProductionRun.class : ProductionRun.RunSequence.class;
            var changed = fixture(name);
            changed.putString("Type", expected.getName());
            assertTrue(CurrentSchemaStateNbt.load(changed, RegistryAccess.EMPTY, expected).isEmpty());
        }
        for (Class<?> type : List.of(ProductionRun.class, ProductionRun.RunSequence.class)) {
            String old = "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ProductionRun"
                + (type==ProductionRun.class ? "" : "$RunSequence");
            var changed = fixture("active");
            var nested = records(changed).stream().filter(tag -> old.equals(tag.getString("Type")))
                .findFirst().orElseThrow();
            nested.putString("Type", type.getName());
            assertTrue(CurrentSchemaStateNbt.load(changed, RegistryAccess.EMPTY,
                ProductionRun.RunSequence.class).isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> CurrentSchemaStateNbt.save(
            new UnlistedApplicationData(1), RegistryAccess.EMPTY));
    }

    @Test void partialUsesCompleteOperationsAndMissingNegativeOrZeroIsNoFeasibleRun() {
        var stone = new StackKey(new ItemStack(Items.STONE));
        var iron = new StackKey(new ItemStack(Items.IRON_INGOT));
        var units = Map.of(stone, 2L, iron, 3L);
        var available = Map.of(stone, 11L, iron, 7L);
        var sequence = expand(8, InputMode.PARTIAL, units, available);
        assertEquals(1, sequence.remainingRuns());
        assertEquals(2, sequence.currentRun().runMultiplier());
        assertEquals(Map.of(stone, 4L, iron, 6L), sequence.currentRun().manifest());
        assertEquals(Map.of(stone, 11L, iron, 7L), available);
        assertEquals(ProductionRun.RunSequence.completed(), expand(8, InputMode.PARTIAL,
            units, Map.of(stone, 11L)));
        for (long unavailable : List.of(-1L, 0L, 1L))
            assertEquals(ProductionRun.RunSequence.completed(), expand(8, InputMode.PARTIAL,
                units, Map.of(stone, unavailable, iron, 7L)));
        assertThrows(NullPointerException.class, () -> expand(8, InputMode.PARTIAL, units, null));
    }

    @Test void contractCopiesInputsAndHasNoSharedMutableManifestOrRouteList() throws Exception {
        var original = CurrentSchemaStateNbt.load(fixture("run"), RegistryAccess.EMPTY,
            ProductionRun.class).orElseThrow();
        var routes = new ArrayList<>(original.inputRoutes());
        var manifest = new HashMap<>(original.manifest());
        var run = new ProductionRun(original.runId(), original.goalId(), original.patternId(), original.fence(),
            original.runMultiplier(), original.inputMode(), routes, original.returnWarehouseRoute(),
            original.outputPolicyGeneration(), manifest);
        routes.clear(); manifest.clear();
        assertEquals(original, run);
        assertThrows(UnsupportedOperationException.class, () -> run.inputRoutes().clear());
        assertThrows(UnsupportedOperationException.class, () -> run.manifest().clear());
    }

    @Test void expansionRetainsOverflowFiniteCountAndExactSealGuards() {
        var stone = new StackKey(new ItemStack(Items.STONE));
        assertThrows(ArithmeticException.class, () -> expand(2, InputMode.BULK,
            Map.of(stone, Long.MAX_VALUE), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> expand(0, InputMode.EXACT, Map.of(stone, 1L), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> expand(1, InputMode.EXACT, Map.of(stone, 0L), Map.of()));
        var exact = expand(Long.MAX_VALUE, InputMode.EXACT, Map.of(stone, 1L), null);
        assertEquals(Long.MAX_VALUE, exact.remainingRuns());
        assertEquals(1, exact.currentRun().runMultiplier());
        var run = exact.currentRun();
        var altered = new ProductionRun(run.runId(), run.goalId(), run.patternId(), run.fence(),
            run.runMultiplier(), run.inputMode(), run.inputRoutes(), run.returnWarehouseRoute(),
            run.outputPolicyGeneration(), Map.of(stone, 2L));
        assertThrows(IllegalArgumentException.class, () -> exact.afterOutputSealed(altered));
        assertThrows(IllegalArgumentException.class, () -> new ProductionRun.RunSequence(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProductionRun.RunSequence(run, 0));
        var bulk = expand(2, InputMode.BULK, Map.of(stone, 1L), null).currentRun();
        assertThrows(IllegalArgumentException.class, () -> new ProductionRun.RunSequence(bulk, 2));
    }

    @Test void actualFreezerAndOutputContractUseSeparatedNewOwnersAndOldClassIsAbsent() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
            "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ProductionRun"));
        assertFalse(Arrays.stream(ProductionRun.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("expand") || method.getName().equals("completeFeasibleOperations")));
        var base = Path.of("src/main/java/com/nobodiiiii/createbiotech/content");
        var freezer = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/content/biocomputing/application/production/plan/ProductionPlanFreezer.java"));
        assertTrue(freezer.contains("ProductionRunExpansion.expand("));
        assertFalse(freezer.contains("ProductionRun.expand("));
        var component = Arrays.stream(ProductionOutputContract.Snapshot.class.getRecordComponents())
            .filter(field -> field.getName().equals("run")).findFirst().orElseThrow();
        assertEquals(ProductionRun.class, component.getType(), "actual output contract, not an unused type alias");
        assertFalse(Files.exists(base.resolve("factorycluster/orchestration/model/ProductionRun.java")));
    }

    private static ProductionRun.RunSequence expand(long count, InputMode mode,
        Map<StackKey, Long> units, Map<StackKey, Long> available) {
        return ProductionRunExpansion.expand(id(41), id(42), 9, count, mode,
            List.of(new CreateRouteKey(id(43), "smelter")), new CreateRouteKey(id(44), "warehouse"),
            7, units, available);
    }

    private static List<CompoundTag> records(Tag value) {
        var result = new ArrayList<CompoundTag>();
        if (value instanceof CompoundTag compound) {
            if (compound.getString("Kind").equals("record")) result.add(compound);
            for (String key : compound.getAllKeys()) result.addAll(records(compound.get(key)));
        } else if (value instanceof ListTag list) for (Tag child : list) result.addAll(records(child));
        return result;
    }
    public record UnlistedApplicationData(int value) {}

    @Test void legacyRunArchiveKeepsEveryTypedFieldAndHistoricalIdentity() throws Exception {
        var original = fixture("run");
        var run = CurrentSchemaStateNbt.load(original, RegistryAccess.EMPTY, ProductionRun.class).orElseThrow();
        assertEquals(id(40), run.runId());
        assertEquals(id(41), run.goalId());
        assertEquals(id(42), run.patternId());
        assertEquals(9, run.fence());
        assertEquals(7, run.outputPolicyGeneration());
        assertEquals(InputMode.EXACT, run.inputMode());
        assertEquals(1, run.runMultiplier());
        assertEquals(original, CurrentSchemaStateNbt.save(run, RegistryAccess.EMPTY),
            "full typed NBT must remain exact, not just a type-label match");
    }

    private static CompoundTag fixture(String name) throws Exception {
        String path = "/com/nobodiiiii/createbiotech/content/biocomputing/application/production/run/legacy-"
            + name + ".nbt";
        try (var input = ProductionRunOwnershipTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "captured by the actual old codec before class deletion");
            return NbtIo.read(new DataInputStream(input), NbtAccounter.unlimitedHeap());
        }
    }

    @Test void bulkExpansionOwnsTheActualFiniteMaterialContract() {
        var stone = new StackKey(new ItemStack(Items.STONE));
        var unit = Map.of(stone, 2L);
        var route = new CreateRouteKey(id(43), "smelter");
        var result = ProductionRunExpansion.expand(id(41), id(42), 9, 4, InputMode.BULK,
            List.of(route), new CreateRouteKey(id(44), "warehouse"), 7, unit, Map.of());
        assertEquals(1, result.remainingRuns());
        var run = result.currentRun();
        assertEquals(4, run.runMultiplier());
        assertEquals(Map.of(stone, 8L), run.manifest());
        assertEquals(unit, Map.of(stone, 2L), "expansion never modifies the source manifest");
        assertEquals(id(41), run.goalId());
        assertEquals(9, run.fence());
        assertEquals(7, run.outputPolicyGeneration());
        assertEquals(List.of(route), run.inputRoutes());
    }
    private static UUID id(long value) { return new UUID(0, value); }
}
