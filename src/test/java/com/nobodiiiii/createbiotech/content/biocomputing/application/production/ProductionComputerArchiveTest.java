package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResident;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.*;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionComputerArchiveTest {
    private static final UUID ID = new UUID(1, 2);
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        var loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true); flag.setBoolean(null, true); Items.AIR.getDefaultInstance();
    }

    @Test void prepareIsDetachedAndDoesNotCommitResidentOrRecovery() {
        var archive = archive(); var resident = new ComputerResident();
        resident.preserveRaw(StringTag.valueOf("live"));
        var input = valid(); input.putString("Resident", "incoming");
        var expected = input.copy();
        var next = archive.prepare(input, resident, RegistryAccess.EMPTY);
        assertFalse(next.valid()); assertEquals(StringTag.valueOf("live"), resident.raw());
        assertFalse(archive.recovery().hasProblems());
        input.putUUID("ComputerId", UUID.randomUUID()); input.putString("Resident", "changed");
        ((CompoundTag) next.runtime()).putInt("cursor", 999);
        assertEquals(ID, next.values().computerId());
        assertEquals(4, ((CompoundTag) next.runtime()).getInt("cursor"));
        resident.commitLoad(next.resident()); archive.commitRecovery(next);
        assertEquals(StringTag.valueOf("incoming"), resident.raw());
        assertEquals(expected, archive.save(next.values(), resident, (CompoundTag) next.runtime(), RegistryAccess.EMPTY));
    }

    @Test void completeSaveAndReloadPreserveTypedChildrenWithoutExposingMutableTags() {
        var archive = archive(); var resident = new ComputerResident(); var source = valid();
        var next = archive.prepare(source, resident, RegistryAccess.EMPTY);
        assertTrue(next.valid()); assertEquals("structure", next.values().structure());
        assertEquals("binding", next.values().binding()); assertEquals("epoch", next.values().epoch());
        archive.commitRecovery(next); resident.commitLoad(next.resident());
        var result = (CompoundTag) archive.save(next.values(), resident, (CompoundTag) next.runtime(), RegistryAccess.EMPTY);
        assertEquals(source, result); result.putString("Structure", "external mutation");
        assertEquals(source, archive.save(next.values(), resident, (CompoundTag) next.runtime(), RegistryAccess.EMPTY));
    }

    @Test void eachMissingRequiredChildRemainsMissingAcrossRepeatedRecovery() {
        for (String key : List.of("ComputerId", "TopologyVersion", "ExternalListeners", "BindingInvalid", "Faults")) {
            var input = valid(); input.remove(key);
            for (int pass = 0; pass < 2; pass++) {
                var archive = archive(); var resident = new ComputerResident();
                var next = archive.prepare(input, resident, RegistryAccess.EMPTY);
                assertFalse(next.valid(), key); archive.commitRecovery(next); resident.commitLoad(next.resident());
                assertTrue(archive.recovery().contains(key));
                var saved = (CompoundTag) archive.save(next.values(), resident, (CompoundTag) next.runtime(), RegistryAccess.EMPTY);
                assertEquals(input, saved, key); assertFalse(saved.contains(key)); input = saved;
            }
        }
    }

    @Test void malformedChildrenAreRetainedAndPreparationCanBeCommittedAgainWithoutLostEvidence() {
        for (String key : List.of("ComputerId", "TopologyVersion", "ExternalListeners", "Structure", "Binding", "BindingInvalid", "Epoch", "Faults")) {
            var archive = archive(); var resident = new ComputerResident(); var input = valid();
            var bad = new ListTag(); bad.add(StringTag.valueOf("not this field's type")); input.put(key, bad);
            var next = archive.prepare(input, resident, RegistryAccess.EMPTY); assertFalse(next.valid(), key);
            archive.commitRecovery(next); archive.recovery().forget(key);
            archive.commitRecovery(next); assertTrue(archive.recovery().contains(key));
            ((ListTag) archive.recovery().raw(key)).clear();
            assertEquals(input, archive.save(next.values(), resident, (CompoundTag) next.runtime(), RegistryAccess.EMPTY), key);
        }
    }

    @Test void unknownEnvelopeIsOpaqueButRetiredRuntimeIsNotReintroduced() {
        var unknown = valid(); unknown.putInt("FutureKey", 1); unknown.putString("Resident", "retain");
        for (Tag input : List.<Tag>of(unknown, StringTag.valueOf("unknown shape"))) {
            var archive = archive(); var resident = new ComputerResident(); var next = archive.prepare(input, resident, RegistryAccess.EMPTY);
            assertFalse(next.valid()); assertNull(next.values().computerId());
            archive.commitRecovery(next); assertTrue(archive.recovery().hasOpaque());
            assertEquals(input, archive.save(next.values(), resident, new CompoundTag(), RegistryAccess.EMPTY));
        }
        var retired = unknown.copy(); retired.put("Runtime", new CompoundTag());
        var archive = archive(); var resident = new ComputerResident(); var next = archive.prepare(retired, resident, RegistryAccess.EMPTY);
        assertFalse(next.valid()); archive.commitRecovery(next); assertFalse(archive.recovery().hasOpaque());
        var saved = (CompoundTag) archive.save(next.values(), resident, new CompoundTag(), RegistryAccess.EMPTY);
        assertFalse(saved.contains("Runtime")); assertFalse(saved.contains("FutureKey"));
    }

    @Test void badOptionalProfileDoesNotEraseSiblingValuesOrBecomeAnInstalledProfile() {
        var archive = archive(); var resident = new ComputerResident(); var input = valid();
        input.put("Profile", new CompoundTag());
        var next = archive.prepare(input, resident, RegistryAccess.EMPTY);
        assertFalse(next.valid()); assertNull(next.values().profile()); assertEquals(ID, next.values().computerId());
        archive.commitRecovery(next); input.remove("Profile");
        assertEquals(input, archive.save(next.values(), resident, (CompoundTag) next.runtime(), RegistryAccess.EMPTY));
    }

    private static ProductionComputerArchive<String, String, String, String, String> archive() {
        var codec = ProductionComputerArchive.compoundCodec(tag -> tag.getAllKeys().equals(Set.of("Value"))
            && tag.get("Value") instanceof StringTag value ? Optional.of(value.getAsString()) : Optional.<String>empty(),
            ProductionComputerArchiveTest::value);
        return new ProductionComputerArchive<>(codec, codec, codec, codec, "empty-listeners", codec, "empty-faults");
    }
    private static CompoundTag value(String value) { var tag = new CompoundTag(); tag.putString("Value", value); return tag; }
    private static CompoundTag valid() {
        var tag = new CompoundTag(); tag.putInt("Version", 5); tag.putUUID("ComputerId", ID); tag.putLong("TopologyVersion", 7);
        tag.put("ExternalListeners", value("listeners")); tag.put("Structure", value("structure"));
        tag.put("Binding", value("binding")); tag.putBoolean("BindingInvalid", false);
        tag.put("Epoch", value("epoch")); tag.put("Faults", value("faults"));
        var runtime = new CompoundTag(); runtime.putInt("cursor", 4); tag.put("OrchestrationRuntime", runtime); return tag;
    }
}
