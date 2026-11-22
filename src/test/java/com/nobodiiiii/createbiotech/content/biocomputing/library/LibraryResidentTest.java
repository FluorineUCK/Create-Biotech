package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LibraryResidentTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }

    @Test void storedAndReturnedSnapshotsDoNotAliasTheSourceAndProjectionIsBounded() {
        var resident = new LibraryResident();
        var source = captured();
        var tag = CBItemData.get(source);
        tag.getCompound("CapturedEntity").putString("CustomName", "\"" + "🌱".repeat(65) + "\"");
        CBItemData.set(source, tag);
        resident.restoreSnapshot(source);
        CapturedEntityBoxHelper.clearCapturedEntity(source);
        var exported = resident.snapshot(); exported.setCount(7);
        CapturedEntityBoxHelper.clearCapturedEntity(exported);
        assertTrue(resident.hasSnapshot()); assertEquals(1, resident.snapshot().getCount());
        var projection = resident.projection(RegistryAccess.EMPTY);
        assertTrue(projection.valid()); assertEquals(4, projection.level());
        assertEquals("🌱".repeat(64), projection.customName());
    }

    @Test void preparedLoadIsDetachedAndOnlyExplicitCommitChangesTheOwner() {
        var original = new LibraryResident(); original.restoreSnapshot(captured()); original.markPendingSafeRelease();
        var saved = save(original);
        var restored = new LibraryResident();
        var prepared = restored.prepareLoad(saved, RegistryAccess.EMPTY);
        saved.getCompound(LibraryResident.SNAPSHOT).putInt("count", 12);
        assertFalse(prepared.hadCorruption()); assertFalse(restored.hasSnapshot());
        restored.commitLoad(prepared);
        assertTrue(restored.pendingSafeRelease());
        assertTrue(ItemStack.matches(original.snapshot(), restored.snapshot()));
        restored.clear(); restored.write(saved, RegistryAccess.EMPTY);
        assertFalse(saved.contains(LibraryResident.SNAPSHOT), "Reusing output compound cannot retain a stale resident");
        assertFalse(saved.getBoolean(LibraryResident.PENDING_RELEASE));
    }

    @Test void malformedCaptureKeepsRawBytesAndCannotBeOverwrittenByAnotherInstallation() {
        var resident = new LibraryResident();
        var raw = new CompoundTag(); raw.putString("id", "example:unavailable_capture"); raw.putInt("count", 1);
        var saved = new CompoundTag(); saved.put(LibraryResident.SNAPSHOT, raw.copy());
        saved.putBoolean(LibraryResident.PENDING_RELEASE, false);
        var prepared = resident.prepareLoad(saved, RegistryAccess.EMPTY);
        assertTrue(prepared.hadCorruption()); assertNull(resident.rawSnapshot());
        resident.commitLoad(prepared);
        resident.rawSnapshot().putString("id", "mutated:copy");
        saved.getCompound(LibraryResident.SNAPSHOT).putString("id", "mutated:input");
        assertEquals(raw, save(resident).getCompound(LibraryResident.SNAPSHOT));
        assertTrue(resident.pendingSafeRelease()); assertFalse(resident.hasSnapshot());
        var held = captured(); var before = held.copy();
        assertEquals(LibraryResident.InstallResult.OCCUPIED, resident.install(held, new Capture(), () -> fail("No synchronization")));
        assertTrue(ItemStack.matches(before, held));
    }

    @Test void failedSynchronizationRestoresCaptureAndPriorPendingStateWithoutUndoingOtherComponents() {
        var resident = new LibraryResident(); resident.markPendingSafeRelease();
        var held = captured(); held.setCount(2);
        var before = held.get(DataComponents.CUSTOM_DATA);
        var name = Component.literal("unrelated change");
        assertEquals(LibraryResident.InstallResult.COMMIT_FAILED, resident.install(held, new Capture(), () -> {
            assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(held));
            assertTrue(resident.hasSnapshot());
            held.setCount(1); held.set(DataComponents.CUSTOM_NAME, name);
            throw new IllegalStateException("transport failed");
        }));
        assertFalse(resident.hasSnapshot()); assertTrue(resident.pendingSafeRelease());
        assertEquals(before, held.get(DataComponents.CUSTOM_DATA)); assertEquals(2, held.getCount());
        assertEquals(name, held.get(DataComponents.CUSTOM_NAME));
        assertEquals(LibraryResident.InstallResult.SUCCESS, resident.install(held, new Capture(), () -> {}));
        assertFalse(resident.pendingSafeRelease()); assertEquals(1, resident.snapshot().getCount());
    }

    @Test void wrongFieldTypesAreRejectedWithoutChangingTheCurrentOwner() {
        var resident = new LibraryResident(); resident.restoreSnapshot(captured());
        var before = save(resident);
        for (int variant = 0; variant < 4; variant++) {
            var bad = before.copy();
            switch (variant) {
                case 0 -> bad.remove(LibraryResident.PENDING_RELEASE);
                case 1 -> bad.putInt(LibraryResident.PENDING_RELEASE, 1);
                case 2 -> bad.putByte(LibraryResident.PENDING_RELEASE, (byte)2);
                default -> bad.putString(LibraryResident.SNAPSHOT, "not a capture");
            }
            assertThrows(IllegalArgumentException.class, () -> resident.prepareLoad(bad, RegistryAccess.EMPTY));
            assertEquals(before, save(resident));
        }
    }

    private static CompoundTag save(LibraryResident resident) {
        var tag = new CompoundTag(); resident.write(tag, RegistryAccess.EMPTY); return tag;
    }
    private static ItemStack captured() {
        var entity = new CompoundTag(); entity.putString("id", "minecraft:villager"); entity.putUUID("UUID", UUID.randomUUID());
        var villager = new CompoundTag(); villager.putString("type", "minecraft:swamp");
        villager.putString("profession", "minecraft:librarian"); villager.putInt("level", 4);
        entity.put("VillagerData", villager);
        var data = new CompoundTag(); data.put("CapturedEntity", entity);
        var stack = new ItemStack(Items.PAPER); CBItemData.set(stack, data); return stack;
    }
    /** Paper stands in for the registered box only in the transfer test; physical validation is native-tested. */
    private static final class Capture implements LibraryResident.CaptureAccess {
        public boolean isBox(ItemStack item) { return item.is(Items.PAPER); }
        public boolean hasCapturedEntity(ItemStack item) { return CapturedEntityBoxHelper.hasCapturedEntity(item); }
        public LibraryResident.InstallResult inspect(ItemStack item) { return LibraryResident.InstallResult.SUCCESS; }
        public boolean clearCapturedEntity(ItemStack item) { CapturedEntityBoxHelper.clearCapturedEntity(item); return true; }
    }
}
