package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.resident.ResidentProjection;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ComputerResidentViewTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }

    @Test void emptyAndEachResidentFamilyRoundTripWithoutProductionFields() {
        assertFalse(ComputerResidentView.EMPTY.renderResident());
        assertEquals(Set.of("Version"), ComputerResidentView.EMPTY.save().getAllKeys());
        assertEquals(ComputerResidentView.EMPTY, ComputerResidentView.load(ComputerResidentView.EMPTY.save()).orElseThrow());
        for (var projection : List.of(
            new ResidentProjection(EntityType.VILLAGER, VillagerProfession.LIBRARIAN, 4, "Archivist"),
            new ResidentProjection(EntityType.WANDERING_TRADER, VillagerProfession.NONE, 1, "Trader"),
            new ResidentProjection(EntityType.ZOMBIE_VILLAGER, VillagerProfession.CLERIC, 3, "Zombie"))) {
            var view = new ComputerResidentView(projection);
            assertTrue(view.renderResident());
            assertEquals(Set.of("Version", "Resident"), view.save().getAllKeys());
            assertEquals(view, ComputerResidentView.load(view.save()).orElseThrow());
        }
    }

    @Test void malformedPacketsAndProductionFieldsAreRejectedRatherThanBecomingEmptyResidents() {
        var view = new ComputerResidentView(new ResidentProjection(EntityType.VILLAGER, VillagerProfession.FLETCHER, 2, null));
        assertTrue(ComputerResidentView.load(null).isEmpty());
        assertTrue(ComputerResidentView.load(new CompoundTag()).isEmpty());
        var old = view.save(); old.putInt("Version", 1);
        assertTrue(ComputerResidentView.load(old).isEmpty());
        var byteVersion = view.save(); byteVersion.putByte("Version", (byte) 2);
        assertTrue(ComputerResidentView.load(byteVersion).isEmpty());
        for (String key : List.of("ProcessSlots", "DisplayState", "RenderResident", "Extra")) {
            var packet = view.save(); packet.putInt(key, 0);
            assertTrue(ComputerResidentView.load(packet).isEmpty(), key);
        }
        var malformed = view.save(); malformed.putString("Resident", "not a projection");
        assertTrue(ComputerResidentView.load(malformed).isEmpty());
        var extraEntityData = view.save(); extraEntityData.getCompound("Resident").putUUID("UUID", UUID.randomUUID());
        assertTrue(ComputerResidentView.load(extraEntityData).isEmpty());
        var trader = new ComputerResidentView(new ResidentProjection(EntityType.WANDERING_TRADER, VillagerProfession.NONE, 1, null)).save();
        trader.getCompound("Resident").putInt("Level", 2);
        assertTrue(ComputerResidentView.load(trader).isEmpty());
    }

    @Test void snapshotProjectionCannotPublishEntityArchiveAndDecodedValuesDoNotAliasPackets() {
        var entity = new CompoundTag(); entity.putString("id", "minecraft:villager"); entity.putUUID("UUID", UUID.randomUUID());
        entity.putString("Offers", "server-only"); entity.putString("Brain", "server-only");
        var villager = new CompoundTag(); villager.putString("profession", "minecraft:cartographer"); villager.putInt("level", 4);
        entity.put("VillagerData", villager);
        var item = new CompoundTag(); item.put("CapturedEntity", entity);
        var snapshot = new ItemStack(Items.PAPER); CBItemData.set(snapshot, item);
        var view = ComputerResidentView.fromSnapshot(snapshot, RegistryAccess.EMPTY);
        assertEquals(VillagerProfession.CARTOGRAPHER, view.resident().profession());
        var packet = view.save();
        assertEquals(Set.of("EntityType", "Profession", "Level"), packet.getCompound("Resident").getAllKeys());
        var decoded = ComputerResidentView.load(packet).orElseThrow();
        packet.getCompound("Resident").putInt("Level", 1);
        decoded.save().getCompound("Resident").putInt("Level", 2);
        assertEquals(view, decoded); assertEquals(4, decoded.resident().level());
        assertEquals(ComputerResidentView.EMPTY, ComputerResidentView.fromSnapshot(ItemStack.EMPTY, RegistryAccess.EMPTY));
    }
}
