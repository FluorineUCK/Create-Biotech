package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.resident.ResidentProjection;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ComputerResidentDisplayTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
        assertNotNull(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.VILLAGER));
        assertNotNull(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(VillagerProfession.FLETCHER));
    }

    @Test void independentPhysicalHostsNeedNoApplicationOrProductionPacketFields() {
        var a = new ComputerResidentDisplay(Set.of(), Set.of());
        var b = new ComputerResidentDisplay(Set.of(), Set.of());
        assertEquals(ComputerResidentView.EMPTY, a.view());
        var first = view(VillagerProfession.LIBRARIAN, 1);
        var second = view(VillagerProfession.FLETCHER, 2);
        var input = packet(first);
        assertTrue(a.readPacket(input)); assertTrue(b.readPacket(packet(second)));
        input.getCompound(ComputerResidentDisplay.ROOT).getCompound("Resident").putInt("Level", 5);
        a.view().save().getCompound("Resident").putInt("Level", 3);
        assertEquals(first, a.view()); assertEquals(second, b.view());
        assertTrue(a.readPacket(packet(ComputerResidentView.EMPTY)));
        assertEquals(ComputerResidentView.EMPTY, a.view()); assertEquals(second, b.view());
    }

    @Test void onlyDeclaredRequiredAndOptionalExtensionsAreAcceptedWithoutRetainingThem() {
        var required = new HashSet<>(Set.of("Status"));
        var optional = new HashSet<>(Set.of("Target"));
        var display = new ComputerResidentDisplay(required, optional);
        required.clear(); optional.add("Unexpected");
        var first = packet(view(VillagerProfession.CLERIC, 3));
        assertFalse(display.readPacket(first));
        first.put("Status", new CompoundTag());
        assertTrue(display.readPacket(first));
        var before = display.view();
        first.putString("Target", "extension decoding belongs to its host");
        assertTrue(display.readPacket(first));
        first.putString("Unexpected", "not part of the constructed schema");
        assertFalse(display.readPacket(first)); assertEquals(before, display.view());
        assertEquals(Set.of("Version", "Resident"), display.view().save().getAllKeys());
        assertThrows(IllegalArgumentException.class, () -> new ComputerResidentDisplay(Set.of("Status"), Set.of("Status")));
        assertThrows(IllegalArgumentException.class, () -> new ComputerResidentDisplay(Set.of(ComputerResidentDisplay.ROOT), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ComputerResidentDisplay(Set.of(), Set.of(" ")));
    }

    @Test void rejectedPacketRootTypeVersionAndFieldsNeverEraseTheAcceptedProjection() {
        var display = new ComputerResidentDisplay(Set.of(), Set.of());
        var resident = view(VillagerProfession.CARTOGRAPHER, 4);
        assertTrue(display.readPacket(packet(resident)));
        var wrongType = new CompoundTag(); wrongType.putString(ComputerResidentDisplay.ROOT, "not a resident");
        var wrongVersion = packet(resident); wrongVersion.getCompound(ComputerResidentDisplay.ROOT).putInt("Version", 1);
        var unknownField = packet(resident); unknownField.getCompound(ComputerResidentDisplay.ROOT).putInt("ProcessSlots", 8);
        var unknownRoot = packet(resident); unknownRoot.put("PrivateMemory", new CompoundTag());
        for (var rejected : Arrays.asList(null, new CompoundTag(), wrongType, wrongVersion, unknownField, unknownRoot)) {
            assertFalse(display.readPacket(rejected)); assertEquals(resident, display.view());
        }
    }

    @Test void freshDisplayPacketStripsServerRootsAndRetainsOnlyAppearanceWithoutMutatingSource() {
        assertTrue(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("villager")), "Fixture must initialize vanilla entity types before reading IDs");
        var entity = new CompoundTag(); entity.putString("id", "minecraft:villager"); entity.putUUID("UUID", UUID.randomUUID());
        entity.putString("Offers", "private-inventory"); entity.putString("Brain", "private-memory");
        var villager = new CompoundTag(); villager.putString("profession", "minecraft:fletcher"); villager.putInt("level", 2);
        entity.put("VillagerData", villager);
        var data = new CompoundTag(); data.put("CapturedEntity", entity);
        var source = new ItemStack(Items.PAPER); CBItemData.set(source, data); var original = source.copy();
        var display = new ComputerResidentDisplay(Set.of("Status"), Set.of("Target"));
        var target = new CompoundTag(); target.put("ComputerData", entity); target.putString("Status", "stale"); target.putString("Target", "stale");
        display.writeFresh(target, source, RegistryAccess.EMPTY);
        assertTrue(ItemStack.matches(original, source));
        assertEquals(Set.of(ComputerResidentDisplay.ROOT), target.getAllKeys());
        assertEquals(view(VillagerProfession.FLETCHER, 2), display.view());
        assertFalse(target.toString().contains("private-")); assertFalse(target.toString().contains("UUID"));
        assertFalse(display.readPacket(target), "Host must append its required extension before sending");
        target.put("Status", new CompoundTag()); assertTrue(display.readPacket(target));
        assertEquals(ComputerResidentView.EMPTY, display.refresh(ItemStack.EMPTY, RegistryAccess.EMPTY));
    }

    private static ComputerResidentView view(VillagerProfession profession, int level) {
        return new ComputerResidentView(new ResidentProjection(EntityType.VILLAGER, profession, level, null));
    }
    private static CompoundTag packet(ComputerResidentView view) {
        var result = new CompoundTag(); result.put(ComputerResidentDisplay.ROOT, view.save()); return result;
    }
}
