package com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

class ProductionPlanOwnershipTest {
    @BeforeAll static void bootstrap() throws Exception { PlanFixture.bootstrap(); }

    @Test void newFreezerOwnsActualOrderedRecipeConversionAndFiniteImmutablePlan() throws Exception {
        var plan = PlanFixture.stableRun(PlanFixture.freeze(
            "com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.ProductionPlanFreezer"));
        assertEquals(PlanFixture.provider(PlanFixture.pattern()), plan.selectedProvider());
        assertEquals(104, plan.baselineCount());
        assertEquals(12, plan.requestedOutputCount());
        assertEquals(116, plan.absoluteTarget());
        assertEquals(4, plan.runs().remainingRuns());
        assertEquals(1, plan.runs().currentRun().runMultiplier());
        assertEquals(InputMode.EXACT, plan.runs().currentRun().inputMode());
        assertEquals(List.of(new CreateRouteKey(PlanFixture.id(4), "press_input"),
            new CreateRouteKey(PlanFixture.id(4), "furnace_input")),
            plan.stages().stream().map(stage -> stage.route()).toList());
        assertEquals(3, plan.orderedIngredientsForCurrentRun(0).size());
        var archive = CurrentSchemaStateNbt.save(plan, RegistryAccess.EMPTY);
        assertEquals(plan, CurrentSchemaStateNbt.load(archive, RegistryAccess.EMPTY,
            plan.getClass()).orElseThrow());
    }

    @Test void oldBaseArchiveRestoresAndReencodesWithoutAnyTypedFieldChange() throws Exception {
        var original = fixture("base");
        var loaded = CurrentSchemaStateNbt.load(original, RegistryAccess.EMPTY,
            FrozenProductionPlan.class).orElseThrow();
        var newlyFrozen = PlanFixture.stableRun(PlanFixture.freeze(ProductionPlanFreezer.class.getName()));
        assertEquals(newlyFrozen, loaded);
        assertEquals(original, CurrentSchemaStateNbt.save(loaded, RegistryAccess.EMPTY));
        assertEquals(original, CurrentSchemaStateNbt.save(newlyFrozen, RegistryAccess.EMPTY));
        assertFalse(loaded.dispatchQueued());
        assertFalse(loaded.cancelAfterDrain());
        assertTrue(loaded.remoteExpansion().isEmpty());
    }

    @Test void oldRemoteInstallationArchiveRetainsExactMaterialAndCancellationEvidence() throws Exception {
        var original = fixture("installed");
        var loaded = CurrentSchemaStateNbt.load(original, RegistryAccess.EMPTY,
            FrozenProductionPlan.class).orElseThrow();
        var expected = PlanFixture.installed(PlanFixture.stableRun(
            PlanFixture.freeze(ProductionPlanFreezer.class.getName())));
        assertEquals(expected, loaded);
        assertEquals(original, CurrentSchemaStateNbt.save(loaded, RegistryAccess.EMPTY));
        assertEquals(original, CurrentSchemaStateNbt.save(expected, RegistryAccess.EMPTY));
        assertTrue(loaded.dispatchQueued());
        assertTrue(loaded.cancelAfterDrain());
        assertTrue(loaded.remoteExpansionMaterialReady());
        assertEquals(loaded.runs().currentRun().manifest(), loaded.materializeCurrentRun(17).manifest());
        assertSame(loaded, loaded.installRemoteResult(loaded.remoteExpansion().orElseThrow().result().orElseThrow()));
        assertThrows(IllegalStateException.class, () -> loaded.installRemoteRevision(
            loaded.remoteExpansion().orElseThrow().request(), loaded.remoteExpansion().orElseThrow().revision()),
            "an earlier revision-only state cannot replace an already installed result");
    }

    @Test void newRuntimeNamesCannotReplaceAnyHistoricalPlanIdentity() throws Exception {
        String old = "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FrozenProductionPlan";
        for (Class<?> type : List.of(FrozenProductionPlan.class, FrozenProductionPlan.FrozenStage.class,
            FrozenProductionPlan.RemoteExpansionInstallation.class)) {
            String suffix = type == FrozenProductionPlan.class ? "" : "$" + type.getSimpleName();
            var changed = fixture("installed");
            var nested = records(changed).stream().filter(tag ->
                tag.getString("Type").equals(old + suffix)).findFirst().orElseThrow();
            nested.putString("Type", type.getName());
            assertTrue(CurrentSchemaStateNbt.load(changed, RegistryAccess.EMPTY,
                FrozenProductionPlan.class).isEmpty(), "nested identity " + suffix);
        }
        var changedRoot = fixture("base");
        changedRoot.putString("Type", FrozenProductionPlan.class.getName());
        assertTrue(CurrentSchemaStateNbt.load(changedRoot, RegistryAccess.EMPTY,
            FrozenProductionPlan.class).isEmpty());
    }

    @Test void relocatingPlansDoesNotAllowArbitraryNewApplicationRecords() {
        assertThrows(IllegalArgumentException.class, () -> CurrentSchemaStateNbt.save(
            new UnlistedApplicationData("not in the historic schema"), RegistryAccess.EMPTY));
    }

    @Test void admittedValueAndActualAdmissionNoLongerUseOldRecipeFreezingFacade() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
            "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FrozenProductionPlan"));
        assertFalse(Arrays.stream(FrozenProductionPlan.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("freeze") || method.getName().equals("resolveIngredients")));
        var base = Path.of("src/main/java/com/nobodiiiii/createbiotech/content");
        var model = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/content/biocomputing/application/production/plan/FrozenProductionPlan.java"));
        assertFalse(model.contains("PatternIngredient"));
        assertFalse(model.contains("PatternRecord"));
        assertFalse(model.contains("StandardPatternBody"));
        assertFalse(model.contains("SequencedPatternBody"));
        var admission = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/content/biocomputing/application/production/admission/ProductionAdmissions.java"));
        assertTrue(admission.contains("ProductionPlanFreezer.freeze("));
        assertFalse(admission.contains("FrozenProductionPlan.freeze("));
        try (var paths = Files.walk(base)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList())
                assertFalse(Files.readString(path).contains("import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FrozenProductionPlan;"),
                    path.toString());
        }
    }

    private static CompoundTag fixture(String name) throws Exception {
        String resource="/com/nobodiiiii/createbiotech/content/biocomputing/application/production/plan/legacy-" + name + ".nbt";
        try (var input = ProductionPlanOwnershipTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, "captured before migration, not generated by the new owner");
            return NbtIo.read(new DataInputStream(input), NbtAccounter.unlimitedHeap());
        }
    }
    private static List<CompoundTag> records(Tag value) {
        var result = new ArrayList<CompoundTag>();
        if (value instanceof CompoundTag compound) {
            if (compound.getString("Kind").equals("record")) result.add(compound);
            for (String key : compound.getAllKeys()) result.addAll(records(compound.get(key)));
        } else if (value instanceof ListTag list) for (Tag child : list) result.addAll(records(child));
        return result;
    }
    public record UnlistedApplicationData(String value) {}
}
