package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkNamesSavedDataTest {
    @TempDir Path folder;
    @BeforeAll static void minecraft() { net.minecraft.SharedConstants.tryDetectVersion(); }

    DimensionDataStorage storage() { return new DimensionDataStorage(folder.toFile(), null, RegistryAccess.EMPTY); }
    NetworkNamesSavedData open(DimensionDataStorage disk) { return NetworkNamesSavedData.get(disk, folder); }
    Path old() { return folder.resolve(LegacyNetworkNames.FILE); }
    Path current() { return folder.resolve(NetworkNamesSavedData.NAME + ".dat"); }
    void flush(DimensionDataStorage disk) {
        disk.save(); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    }
    void legacy(LogisticsNetworkDirectory directory) throws Exception {
        var record = CurrentSchemaStateNbt.save(directory.snapshot(), RegistryAccess.EMPTY);
        assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectory$State", record.getString("Type"));
        var directoryEntry = new CompoundTag(); directoryEntry.putString("Key","logistics_network_directory/global"); directoryEntry.put("Value",record);
        var unrelated = new CompoundTag(); unrelated.putString("Key","pending_order/example"); unrelated.put("Value",new CompoundTag());
        var entries = new ListTag(); entries.add(unrelated); entries.add(directoryEntry);
        var journal = new CompoundTag(); journal.putInt("Version",1); journal.put("Entries",entries);
        var file = new CompoundTag(); file.put("data",journal); NbtIo.writeCompressed(file, old());
    }

    @Test void importsAliasOrdinalAndCasRevisionOnceAndNeverChangesTheOldJournal() throws Exception {
        var first = UUID.randomUUID(); var second = UUID.randomUUID(); var original = new LogisticsNetworkDirectory();
        original.ensure(first); original.compareAndSetAlias(first, 0, Optional.of("主仓")); legacy(original);
        byte[] prior = Files.readAllBytes(old());
        var disk = storage(); var data = open(disk); var names = data.names().orElseThrow();
        assertSame(data,open(disk)); assertTrue(data.isDirty()); assertEquals(original.snapshot(), names.snapshot());
        assertFalse(names.compareAndSetAlias(first, 0, Optional.of("stale")));
        assertTrue(names.compareAndSetAlias(first, 1, Optional.of("新仓")));
        assertEquals(2,names.ensure(second).ordinal());
        flush(disk); assertArrayEquals(prior,Files.readAllBytes(old())); assertTrue(Files.exists(current()));
        Files.writeString(old(),"unreadable historical file should no longer be consulted");
        assertEquals(names.snapshot(),open(storage()).names().orElseThrow().snapshot());
    }

    @Test void readsOnlyHistoricalDirectoryRecordVersionsAndRejectsMalformedChildren() throws Exception {
        var directory = new LogisticsNetworkDirectory();
        directory.ensure(UUID.randomUUID());
        var network = UUID.randomUUID();
        directory.ensure(network);
        directory.compareAndSetAlias(network, 0, Optional.of("named"));
        // Use the original writer as a test oracle, not a release dependency.
        var archive = CurrentSchemaStateNbt.save(directory.snapshot(), RegistryAccess.EMPTY);
        for (int version : new int[] {1, 2, 4, 5, 6}) {
            archive.putInt("Version", version);
            assertEquals(directory.snapshot(), LegacyNetworkDirectoryRecord.decode(archive));
        }
        archive.putInt("Version", 3);
        assertThrows(java.io.IOException.class, () -> LegacyNetworkDirectoryRecord.decode(archive));
        archive.putInt("Version", 6);
        var entries = archive.getCompound("Value").getCompound("entries").getList("Entries", 10);
        var duplicate = entries.getCompound(0).copy();
        entries.add(duplicate);
        assertThrows(java.io.IOException.class, () -> LegacyNetworkDirectoryRecord.decode(archive));
        entries.remove(entries.size() - 1);
        entries.getCompound(0).getCompound("Value").getCompound("customAlias").putByte("Present", (byte) 2);
        assertThrows(java.io.IOException.class, () -> LegacyNetworkDirectoryRecord.decode(archive));
    }

    @Test void absentWorldDoesNotWriteEitherFileUntilAnExplicitAllocation() {
        var disk = storage(); var data = open(disk); assertFalse(data.isDirty()); flush(disk);
        assertFalse(Files.exists(current())); assertFalse(Files.exists(old()));
        data.names().orElseThrow().ensure(UUID.randomUUID()); flush(disk);
        assertTrue(Files.isRegularFile(current())); assertFalse(Files.exists(old()));
    }

    @Test void badCurrentFileCannotBeReseededFromEvenAValidOldJournal() throws Exception {
        legacy(new LogisticsNetworkDirectory()); Files.writeString(current(),"not a valid SavedData");
        var disk=storage(); var denied=open(disk); assertTrue(denied.names().isEmpty());
        denied.setDirty(); flush(disk); assertEquals("not a valid SavedData",Files.readString(current()));
    }

    @Test void unreadableLegacyDoesNotTurnIntoAnEmptyWritableDirectory() throws Exception {
        Files.writeString(old(),"broken");
        var disk=storage(); var denied=open(disk); assertTrue(denied.names().isEmpty());
        denied.setDirty(); flush(disk);
        assertFalse(Files.exists(current())); assertEquals("broken",Files.readString(old()));
    }
}
