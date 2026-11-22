package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ComputerResidentTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }

    @Test void ownsDetachedSnapshotAndPreparedLoadPreservesExistingResidentUntilCommit() {
        var resident = new ComputerResident(); var source = captured(); var expected = source.copy();
        resident.restoreSnapshot(source);
        source.setCount(4); resident.snapshot().setCount(7);
        assertTrue(ItemStack.matches(expected, resident.snapshot()));
        var saved = new CompoundTag(); resident.write(saved, RegistryAccess.EMPTY);
        var loaded = new ComputerResident();
        var candidate = loaded.prepareLoad(saved.get("Resident"), RegistryAccess.EMPTY);
        assertFalse(candidate.hadCorruption()); assertFalse(loaded.hasSource());
        saved.getCompound("Resident").putInt("count", 9);
        loaded.commitLoad(candidate);
        assertTrue(ItemStack.matches(expected, loaded.snapshot()));
        assertNull(loaded.raw());
    }

    @Test void absentFieldClearsOnCommitAndReusedWriteTargetCannotKeepAnOldResident() {
        var resident = new ComputerResident(); resident.restoreSnapshot(captured());
        var old = new CompoundTag(); resident.write(old, RegistryAccess.EMPTY);
        var prepared = resident.prepareLoad(null, RegistryAccess.EMPTY);
        assertTrue(resident.hasSource()); assertFalse(prepared.hadCorruption());
        resident.commitLoad(prepared);
        assertFalse(resident.hasSource()); resident.write(old, RegistryAccess.EMPTY);
        assertFalse(old.contains("Resident"));
        assertArrayEquals(new byte[0], resident.serialized(RegistryAccess.EMPTY));
    }

    @ParameterizedTest @MethodSource("brokenSnapshots")
    void unreadableResidentRemainsOccupiedAndRetainsItsExactSource(net.minecraft.nbt.Tag raw) {
        var resident = new ComputerResident(); resident.restoreSnapshot(captured());
        var before = resident.snapshot(); var expected = raw.copy();
        var prepared = resident.prepareLoad(raw, RegistryAccess.EMPTY);
        assertTrue(prepared.hadCorruption()); assertTrue(ItemStack.matches(before, resident.snapshot()));
        if (raw instanceof CompoundTag compound) compound.putString("unrelated", "mutated input");
        resident.commitLoad(prepared);
        assertTrue(resident.hasSource()); assertFalse(resident.hasSnapshot()); assertTrue(resident.hadCorruption());
        var detached = resident.raw();
        if (detached instanceof CompoundTag compound) compound.putInt("count", 32);
        var saved = new CompoundTag(); resident.write(saved, RegistryAccess.EMPTY);
        assertEquals(expected, saved.get("Resident"));
        resident.clear(); resident.write(saved, RegistryAccess.EMPTY);
        assertFalse(resident.hasSource()); assertFalse(saved.contains("Resident"));
    }

    private static java.util.stream.Stream<net.minecraft.nbt.Tag> brokenSnapshots() {
        var badItem = new CompoundTag(); badItem.putString("id", "example:unavailable_item"); badItem.putInt("count", 1);
        var result = new java.util.ArrayList<net.minecraft.nbt.Tag>();
        result.add(net.minecraft.nbt.StringTag.valueOf("unreadable")); result.add(badItem);
        for (int variant = 0; variant < 4; variant++) {
            var box = captured(); var custom = CBItemData.get(box); var entity = custom.getCompound("CapturedEntity");
            switch (variant) {
                case 0 -> entity.putString("id", "example:unavailable_entity");
                case 1 -> entity.putIntArray("UUID", new int[]{1,2});
                case 2 -> entity.putString("Passengers", "wrong type");
                default -> { var passengers = new net.minecraft.nbt.ListTag(); passengers.add(new CompoundTag()); entity.put("Passengers", passengers); }
            }
            CBItemData.set(box, custom); result.add(box.save(RegistryAccess.EMPTY));
        }
        return result.stream();
    }

    private static ItemStack captured() {
        var entity = new CompoundTag(); entity.putString("id", "minecraft:villager"); entity.putUUID("UUID", UUID.randomUUID());
        var data = new CompoundTag(); data.put("CapturedEntity", entity);
        var stack = new ItemStack(Items.PAPER); CBItemData.set(stack, data); return stack;
    }
}
