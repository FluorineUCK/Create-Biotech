package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeScreenSession;
import java.util.ArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryCreativeScreenSlotsTest {
    @BeforeAll static void bootstrap() throws Exception {
        // Same plain-JVM environment seam as the existing item/menu tests; no live FML loader here.
        var loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", java.util.List.class, java.util.List.class, java.util.List.class, java.util.List.class, java.util.Map.class)
                .invoke(null, java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        var flag = Bootstrap.class.getDeclaredField("isBootstrapped"); flag.setAccessible(true); flag.setBoolean(null, true);
    }

    @Test void actualCreativeSlotWrappersMapToTheDisplayedInventoryPosition() throws Exception {
        var constructor = Class.forName("net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper")
            .getDeclaredConstructor(Slot.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var container = new SimpleContainer(46);
        var visible = new ArrayList<Slot>();
        // Mirrors vanilla selectTab: wrappers are appended, not passed through AbstractContainerMenu.addSlot.
        for (int i = 0; i < 46; i++) {
            var target = new Slot(container, i >= 36 && i <= 44 ? i - 36 : i, 0, 0);
            target.index = i;
            visible.add((Slot) constructor.newInstance(target, i, 0, 0));
        }
        for (int i : new int[] {44, 9, 5, 45})
            assertEquals(i, LibraryCreativeScreenSession.inventorySlot(visible, visible.get(i)));
        assertEquals(-1, LibraryCreativeScreenSession.inventorySlot(visible, new Slot(container, 0, 0, 0)));
    }
}
