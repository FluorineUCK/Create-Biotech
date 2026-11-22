package com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.DispatchManifest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionEffectTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
        Items.AIR.getDefaultInstance();
    }

    @Test void actualHistoricalDispatchKeepsEveryFieldAndExactMaterialThroughNewOwner() throws Exception {
        var original = fixture("CreateDispatch");
        var effect = CurrentSchemaStateNbt.load(original, RegistryAccess.EMPTY,
            ProductionEffect.CreateDispatch.class).orElseThrow();
        assertEquals(new UUID(0, 14), effect.intentId());
        assertEquals(new UUID(0, 26), effect.processId());
        assertEquals(new UUID(0, 27), effect.runId());
        assertEquals(11, effect.generation());
        assertEquals(7, effect.fence().routeGeneration());
        assertEquals(9, effect.fence().validationFence());
        assertEquals(Map.of(new StackKey(new ItemStack(Items.IRON_INGOT)), 2L,
            new StackKey(new ItemStack(Items.GOLD_INGOT)), 3L), DispatchManifest.decode(effect.ingredients()));
        assertEquals(original, CurrentSchemaStateNbt.save(effect, RegistryAccess.EMPTY));
    }

    @Test void allTenHistoricalRecordsRoundTripAndReservedEffectsRemainUnsupported() throws Exception {
        int supported = 0;
        for (Class<?> type : types()) {
            var original = fixture(type.getSimpleName());
            assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$"
                + type.getSimpleName(), original.getString("Type"));
            assertEquals(6, original.getInt("Version"));
            var restored = CurrentSchemaStateNbt.load(original, RegistryAccess.EMPTY, type).orElseThrow();
            assertEquals(original, CurrentSchemaStateNbt.save(restored, RegistryAccess.EMPTY), type.getName());
            if (restored instanceof ProductionEffect effect) {
                boolean expected = Set.of("CreateDispatch", "StockSampleRequest", "OutputPackageRequest",
                    "PanelSnapshotPublication").contains(type.getSimpleName());
                assertEquals(expected, ProductionEffectPolicy.supported(effect));
                if (expected) supported++;
            }
        }
        assertEquals(4, supported);
        assertThrows(NullPointerException.class, () -> ProductionEffectPolicy.supported(null));
    }

    @Test void fullActualRuntimeArchivePreservesPendingAndUncertainEffects() throws Exception {
        var original = fixture("runtime");
        var state = RuntimeNbt.load(original, RegistryAccess.EMPTY).orElseThrow();
        assertEquals(new UUID(0, 1), state.clusterId());
        assertEquals(new UUID(0, 2), state.epochId());
        assertEquals(11, state.generation());
        assertEquals(7, state.pendingIntents().size());
        assertEquals(Set.of(new UUID(0, 14)), state.uncertainIntentIds());
        for (Class<?> type : types()) if (ProductionEffect.class.isAssignableFrom(type)) {
            var typed = CurrentSchemaStateNbt.load(fixture(type.getSimpleName()), RegistryAccess.EMPTY, type).orElseThrow();
            assertEquals(typed, state.pendingIntents().get(((ProductionEffect) typed).intentId()));
        }
        assertEquals(original, RuntimeNbt.save(state, RegistryAccess.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> state.pendingIntents().clear());
        var corrupt = original.copy(); corrupt.putLong("Generation", -1);
        assertTrue(RuntimeNbt.load(corrupt, RegistryAccess.EMPTY).isEmpty());
        corrupt = original.copy(); corrupt.putInt("Unexpected", 1);
        assertTrue(RuntimeNbt.load(corrupt, RegistryAccess.EMPTY).isEmpty());
        corrupt = original.copy();
        var pending = corrupt.getList("PendingIntents", Tag.TAG_COMPOUND);
        var dispatch = pending.stream().map(CompoundTag.class::cast)
            .filter(value -> value.getString("Type").equals("CreateDispatch")).findFirst().orElseThrow();
        dispatch.putString("Type", ProductionEffect.CreateDispatch.class.getName());
        assertTrue(RuntimeNbt.load(corrupt, RegistryAccess.EMPTY).isEmpty(),
            "simple legacy discriminators stay strict too");
        corrupt = original.copy();
        corrupt.getList("PendingIntents", Tag.TAG_COMPOUND).getCompound(0).putUUID("ClusterId", new UUID(0, 999));
        assertTrue(RuntimeNbt.load(corrupt, RegistryAccess.EMPTY).isEmpty());
        assertEquals(original, RuntimeNbt.save(state, RegistryAccess.EMPTY), "rejection cannot mutate the live archive");
    }

    @Test void canonicalRootAndNestedIdentitiesCannotExpandReflectionAuthority() throws Exception {
        for (Class<?> type : types()) {
            var root = fixture(type.getSimpleName()); root.putString("Type", type.getName());
            assertTrue(CurrentSchemaStateNbt.load(root, RegistryAccess.EMPTY, type).isEmpty(), type.getName());
            var original = fixture(type.getSimpleName());
            for (var record : records(original)) {
                String old = record.getString("Type");
                var relocated = types().stream().filter(candidate -> old.endsWith("$" + candidate.getSimpleName()))
                    .findFirst().orElse(null);
                if (relocated == null) continue;
                var corrupt = original.copy();
                records(corrupt).stream().filter(value -> value.getString("Type").equals(old))
                    .findFirst().orElseThrow().putString("Type", relocated.getName());
                assertTrue(CurrentSchemaStateNbt.load(corrupt, RegistryAccess.EMPTY, type).isEmpty(), old);
            }
        }
        assertThrows(IllegalArgumentException.class,
            () -> CurrentSchemaStateNbt.save(new UnregisteredApplicationValue(1), RegistryAccess.EMPTY));
    }

    @Test void malformedTypedFieldsCannotCreateAnEffectOrOverwriteAnExistingValue() throws Exception {
        var original = fixture("CreateDispatch");
        var changed = original.copy(); changed.getCompound("Value").remove("processId");
        assertTrue(CurrentSchemaStateNbt.load(changed, RegistryAccess.EMPTY, ProductionEffect.CreateDispatch.class).isEmpty());
        changed = original.copy(); changed.getCompound("Value").putLong("Unexpected", 1);
        assertTrue(CurrentSchemaStateNbt.load(changed, RegistryAccess.EMPTY, ProductionEffect.CreateDispatch.class).isEmpty());
        changed = original.copy();
        assertEquals(11, changed.getCompound("Value").getCompound("generation").getLong("Value"));
        changed.getCompound("Value").getCompound("generation").putLong("Value", -1);
        assertTrue(CurrentSchemaStateNbt.load(changed, RegistryAccess.EMPTY, ProductionEffect.CreateDispatch.class).isEmpty());
        var ingredient = fixture("OrderedIngredient");
        assertEquals(2, ingredient.getCompound("Value").getCompound("amount").getLong("Value"));
        ingredient.getCompound("Value").getCompound("amount").putLong("Value", 0);
        assertTrue(CurrentSchemaStateNbt.load(ingredient, RegistryAccess.EMPTY, OrderedIngredient.class).isEmpty());
        assertEquals(original, CurrentSchemaStateNbt.save(CurrentSchemaStateNbt.load(original, RegistryAccess.EMPTY,
            ProductionEffect.CreateDispatch.class).orElseThrow(), RegistryAccess.EMPTY));
    }

    @Test void effectConstructionCopiesListsAndKeepsHistoricalBoundsAndFences() throws Exception {
        var old = CurrentSchemaStateNbt.load(fixture("CreateDispatch"), RegistryAccess.EMPTY,
            ProductionEffect.CreateDispatch.class).orElseThrow();
        var source = new ArrayList<>(old.ingredients());
        var effect = new ProductionEffect.CreateDispatch(old.intentId(), old.clusterId(), old.generation(),
            old.fence(), old.processId(), old.runId(), old.route(), source);
        source.clear(); assertEquals(old, effect);
        assertThrows(UnsupportedOperationException.class, () -> effect.ingredients().clear());
        var item = old.ingredients().getFirst();
        var fence = old.fence();
        var alias = new ProductionEffect.CreateDispatch(old.intentId(), old.clusterId(), 0, fence,
            old.runId(), old.route(), Collections.nCopies(ProductionEffect.MAX_LIST_ENTRIES, item));
        assertEquals(alias.runId(), alias.processId());
        assertEquals(ProductionEffect.MAX_LIST_ENTRIES, alias.ingredients().size());
        assertThrows(IllegalArgumentException.class, () -> new ProductionEffect.CreateDispatch(old.intentId(),
            old.clusterId(), 0, fence, old.runId(), old.route(), Collections.nCopies(ProductionEffect.MAX_LIST_ENTRIES + 1, item)));
        assertThrows(IllegalArgumentException.class, () -> new ProductionEffect.CreateDispatch(old.intentId(),
            old.clusterId(), 0, fence, old.runId(), old.route(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProductionEffect.CreateDispatch(old.intentId(),
            old.clusterId(), -1, fence, old.runId(), old.route(), List.of(item)));
        assertThrows(NullPointerException.class, () -> new ProductionEffect.CreateDispatch(old.intentId(),
            old.clusterId(), 0, null, old.runId(), old.route(), List.of(item)));
        assertThrows(IllegalArgumentException.class, () -> new ProductionEffect.TargetFence(fence.epochId(), fence.targetId(), -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProductionEffect.TargetFence(fence.epochId(), fence.targetId(), 0, -1));
        assertThrows(NullPointerException.class, () -> new ProductionEffect.TargetFence(null, fence.targetId(), 0, 0));
        assertEquals(new CreateRouteKey(new UUID(0, 5), "input"), effect.route());
    }

    @Test void historicalPanelAndRadioDescriptionsRemainBoundedImmutableData() {
        var id = new UUID(0, 1);
        var fence = new ProductionEffect.TargetFence(id, id, 0, 0);
        var text = "x".repeat(ProductionEffect.MAX_TEXT_LENGTH);
        var row = new ProductionEffect.PanelRow(id, Integer.MIN_VALUE, text);
        var source = new ArrayList<>(List.of(row));
        var panel = new ProductionEffect.PanelSnapshotPublication(id, id, 0, fence, source);
        source.clear(); assertEquals(List.of(row), panel.rows());
        assertThrows(UnsupportedOperationException.class, () -> panel.rows().clear());
        assertEquals(text, new ProductionEffect.RadioSend(id, id, 0, fence, id, text).payload());
        assertThrows(IllegalArgumentException.class, () -> new ProductionEffect.PanelRow(id, 0, text + "x"));
        assertThrows(IllegalArgumentException.class, () -> new ProductionEffect.RadioSend(id, id, 0, fence, id, text + "x"));
        assertThrows(IllegalArgumentException.class, () -> new ProductionEffect.PanelSnapshotPublication(id, id, 0,
            fence, Collections.nCopies(ProductionEffect.MAX_LIST_ENTRIES + 1, row)));
        assertEquals(List.of(), new ProductionEffect.PanelSnapshotPublication(id, id, 0, fence, List.of()).rows());
        assertThrows(NullPointerException.class, () -> new ProductionEffect.PanelRow(id, 0, null));
    }

    public record UnregisteredApplicationValue(int value) {}

    private static List<Class<?>> types() {
        return List.of(ProductionEffect.TargetFence.class, ProductionEffect.PanelRow.class, OrderedIngredient.class,
            ProductionEffect.LibraryQuery.class, ProductionEffect.RadioSend.class, ProductionEffect.StockSampleRequest.class,
            ProductionEffect.AgentTicketOffer.class, ProductionEffect.CreateDispatch.class,
            ProductionEffect.OutputPackageRequest.class, ProductionEffect.PanelSnapshotPublication.class);
    }

    private static List<CompoundTag> records(Tag value) {
        var result = new ArrayList<CompoundTag>();
        if (value instanceof CompoundTag compound) {
            if (compound.getString("Kind").equals("record")) result.add(compound);
            for (String key : compound.getAllKeys()) result.addAll(records(compound.get(key)));
        } else if (value instanceof ListTag list) for (Tag child : list) result.addAll(records(child));
        return result;
    }

    private static CompoundTag fixture(String name) throws Exception {
        var path = "/com/nobodiiiii/createbiotech/content/biocomputing/application/production/effect/legacy-"
            + name + ".nbt";
        try (var input = ProductionEffectTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "captured by the actual old codec before removal");
            return NbtIo.read(new DataInputStream(input), NbtAccounter.unlimitedHeap());
        }
    }
}
