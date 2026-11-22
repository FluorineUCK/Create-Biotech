package com.nobodiiiii.createbiotech.content.biocomputing.application.production.material;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DispatchManifestTest {
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

    @Test void exactTypedMaterialsAggregateWithoutMutatingSourcesOrMergingComponents() {
        var item = new ItemStack(Items.IRON_INGOT);
        var named = item.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
        var exact = new StackKey(item);
        var otherComponent = new StackKey(named);
        var source = new ArrayList<>(List.of(new OrderedIngredient(exact, 2),
            new OrderedIngredient(exact, 3), new OrderedIngredient(otherComponent, 7)));
        var manifest = DispatchManifest.decode(source);
        assertEquals(Map.of(exact, 5L, otherComponent, 7L), manifest);
        assertEquals(3, source.size());
        source.clear(); named.remove(DataComponents.CUSTOM_NAME); item.setCount(64);
        assertEquals(Map.of(exact, 5L, otherComponent, 7L), manifest);
        assertThrows(UnsupportedOperationException.class, () -> manifest.clear());
        assertNotEquals(exact, otherComponent);
    }

    @Test void positiveAndRegisteredMaterialGuardsRemainStrict() {
        var exact = new StackKey(new ItemStack(Items.IRON_INGOT));
        assertThrows(NullPointerException.class, () -> new OrderedIngredient(null, 1));
        for (long invalid : List.of(Long.MIN_VALUE, -1L, 0L))
            assertThrows(IllegalArgumentException.class, () -> new OrderedIngredient(exact, invalid));
        assertEquals(Long.MAX_VALUE, new OrderedIngredient(exact, Long.MAX_VALUE).amount());
        assertThrows(NullPointerException.class, () -> DispatchManifest.decode(null));
        assertThrows(IllegalArgumentException.class, () -> DispatchManifest.decode(List.of()));
        var nullEntry = new ArrayList<OrderedIngredient>(); nullEntry.add(null);
        assertThrows(NullPointerException.class, () -> DispatchManifest.decode(nullEntry));
        var unregistered = new StackKey(new ItemStack(new net.minecraft.world.item.Item(
            new net.minecraft.world.item.Item.Properties())));
        assertThrows(IllegalArgumentException.class,
            () -> DispatchManifest.decode(List.of(new OrderedIngredient(unregistered, 1))));
    }

    @Test void aggregationRejectsOverflowAndDoesNotPublishPartialResults() {
        var exact = new StackKey(new ItemStack(Items.IRON_INGOT));
        var source = List.of(new OrderedIngredient(exact, Long.MAX_VALUE), new OrderedIngredient(exact, 1));
        assertThrows(ArithmeticException.class, () -> DispatchManifest.decode(source));
        assertEquals(2, source.size());
        assertEquals(Long.MAX_VALUE, source.getFirst().amount());
        assertEquals(Map.of(exact, Long.MAX_VALUE), DispatchManifest.decode(source.subList(0, 1)));
    }
}
