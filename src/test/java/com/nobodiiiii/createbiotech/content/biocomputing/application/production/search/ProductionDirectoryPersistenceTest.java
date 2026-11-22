package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionLibraryDirectoryTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import java.util.LinkedHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionDirectoryPersistenceTest {
    @BeforeAll static void bootstrapRegistry() throws ReflectiveOperationException { ProductionCatalogSearchTest.bootstrap(); }

    @Test void movedDirectoryFactsPreserveHistoricalSchemaIdentitiesAndRejectPackageAliases() {
        var cases = new LinkedHashMap<Object, String>();
        String oldLibrary = "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.";
        cases.put(CommunicationFrequency.of(" 频道 A "),
            "com.nobodiiiii.createbiotech.content.factorycluster.communication.CommunicationFrequency");
        cases.put(peer(8, 20, 3, 7, LibraryRegistration.LoadState.UNLOADED,
            LibraryRegistration.Reachability.UNREACHABLE, " 频道 A "), oldLibrary + "LibraryRegistration");
        for (var state : LibraryRegistration.LoadState.values()) cases.put(state, oldLibrary + "LibraryRegistration$LoadState");
        for (var state : LibraryRegistration.Reachability.values()) cases.put(state, oldLibrary + "LibraryRegistration$Reachability");
        cases.forEach((value, historicalName) -> {
            var tag = CurrentSchemaStateNbt.save(value, RegistryAccess.EMPTY);
            assertEquals(6, tag.getInt("Version"));
            assertEquals(historicalName, tag.getString("Type"));
            assertEquals(value, CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, value.getClass()).orElseThrow());
            var alias = tag.copy(); alias.putString("Type", value.getClass().getName());
            assertTrue(CurrentSchemaStateNbt.load(alias, RegistryAccess.EMPTY, value.getClass()).isEmpty());
            if (tag.getCompound("Value").contains("Type")) {
                var nestedAlias = tag.copy(); nestedAlias.getCompound("Value").putString("Type", value.getClass().getName());
                assertTrue(CurrentSchemaStateNbt.load(nestedAlias, RegistryAccess.EMPTY, value.getClass()).isEmpty());
            }
        });
    }

    @Test void historicalRegistrationFixtureMapsOnlyItsSourceFieldAndKeepsOtherWorldAddresses() {
        var expected = peer(8, 20, 3, 7, LibraryRegistration.LoadState.UNLOADED,
            LibraryRegistration.Reachability.UNREACHABLE, " 频道 A ");
        var source = expected.address();
        var worldAddress = new SpaceAddress(source.dimension(), source.subLevelId(), source.localPos());
        var oldAddressTag = CurrentSchemaStateNbt.save(worldAddress, RegistryAccess.EMPTY);
        String historicalType = "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibraryRegistration";
        var value = new CompoundTag();
        value.putString("Kind", "record"); value.putString("Type", historicalType);
        value.put("libraryId", encoded(expected.libraryId())); value.put("clusterId", encoded(expected.clusterId()));
        value.put("address", oldAddressTag.getCompound("Value").copy());
        value.put("formationGeneration", encoded(3L)); value.put("priority", encoded(20));
        value.put("publicCatalogGeneration", encoded(7L));
        var frequency = new CompoundTag(); frequency.putString("Kind", "communication_frequency");
        frequency.putString("Value", " 频道 A "); value.put("frequency", frequency);
        value.put("loadState", historicalEnum(historicalType + "$LoadState", "UNLOADED"));
        value.put("reachability", historicalEnum(historicalType + "$Reachability", "UNREACHABLE"));
        var fixture = new CompoundTag(); fixture.putInt("Version", 6); fixture.putString("Type", historicalType); fixture.put("Value", value);
        var untouched = fixture.copy();
        assertEquals(expected, CurrentSchemaStateNbt.load(fixture, RegistryAccess.EMPTY, LibraryRegistration.class).orElseThrow());
        assertEquals(fixture, CurrentSchemaStateNbt.save(expected, RegistryAccess.EMPTY), "retains exact historical field encodings");
        assertEquals(untouched, fixture, "decoding does not rewrite the input");
        assertEquals(worldAddress, CurrentSchemaStateNbt.load(oldAddressTag, RegistryAccess.EMPTY, SpaceAddress.class).orElseThrow());
        assertEquals(source, CurrentSchemaStateNbt.load(oldAddressTag, RegistryAccess.EMPTY, RecipeSourceLocation.class).orElseThrow());
        var wrongLocation = fixture.copy();
        wrongLocation.getCompound("Value").getCompound("address").putString("Type", RecipeSourceLocation.class.getName());
        assertTrue(CurrentSchemaStateNbt.load(wrongLocation, RegistryAccess.EMPTY, LibraryRegistration.class).isEmpty());
        var wrongFrequency = fixture.copy(); wrongFrequency.getCompound("Value").put("frequency", encoded(17));
        assertTrue(CurrentSchemaStateNbt.load(wrongFrequency, RegistryAccess.EMPTY, LibraryRegistration.class).isEmpty());
        var wrongState = fixture.copy(); wrongState.getCompound("Value").getCompound("loadState").putString("Value", "MISSING");
        assertTrue(CurrentSchemaStateNbt.load(wrongState, RegistryAccess.EMPTY, LibraryRegistration.class).isEmpty());
    }

    private static CompoundTag encoded(Object value) { return CurrentSchemaStateNbt.save(value, RegistryAccess.EMPTY).getCompound("Value"); }
    private static CompoundTag historicalEnum(String type, String name) {
        var tag = new CompoundTag(); tag.putString("Kind", "enum"); tag.putString("Type", type); tag.putString("Value", name); return tag;
    }
}
