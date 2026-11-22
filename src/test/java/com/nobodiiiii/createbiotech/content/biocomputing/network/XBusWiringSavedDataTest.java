package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.saveddata.SavedData;

class XBusWiringSavedDataTest {
    @TempDir Path directory;

    @Test void internalOnlyPinsFenceFutureExternalBranchesIncludingAddRemoveBeforeValidation() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        var pin = new XBusNetwork.Pin(a.computer(), a.pin());
        var captured = store.componentView(List.of(pin), List.of());
        assertTrue(captured.current());
        var unrelated = target(8); var other = target(12);
        var added = store.add(store.view(unrelated.device()), "other", unrelated, other, 8, ignored -> {});
        store.setEnabled(added, "other", true, ignored -> {});
        assertTrue(captured.current(), "An unrelated component does not revoke this capture");
        added = store.add(store.view(b.device()), "new_branch", b, a, 8, ignored -> {});
        assertTrue(captured.current(), "A disabled declaration is not a wire");
        var enabled = store.setEnabled(added, "new_branch", true, ignored -> {});
        store.remove(enabled, "new_branch", ignored -> {});
        assertFalse(captured.current(), "Returning to an empty neighborhood must not revive old authority");
        assertTrue(store.componentView(List.of(pin), List.of()).current());
    }

    @Test void oldDeclarationsStayDisabledUntilAnExplicitVersionedEnableAndKeepTheirBindings() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        store.add(store.view(a.device()), "wire", a, b, 8, ignored -> {});
        var old = store.save(new CompoundTag(), null); old.putInt("Version", 1);
        old.getList("Owners", 10).getCompound(0).getList("Entries", 10).getCompound(0).remove("Enabled");
        var restored = XBusWiringSavedData.load(old, null); var before = restored.view(a.device());
        assertFalse(before.entries().getFirst().enabled()); assertTrue(restored.enabledConnections().isEmpty());
        var enabled = restored.setEnabled(before, "wire", true, ignored -> {});
        assertEquals(before.revision() + 1, enabled.revision());
        assertEquals(a, enabled.entries().getFirst().origin()); assertEquals(b, enabled.entries().getFirst().peer());
        assertTrue(enabled.entries().getFirst().enabled()); assertEquals(1, restored.enabledConnections().size());
        var saved = restored.save(new CompoundTag(), null); assertEquals(2, saved.getInt("Version"));
        var reloaded = XBusWiringSavedData.load(saved, null);
        assertEquals(enabled, reloaded.view(a.device())); assertEquals(1, reloaded.enabledConnections().size());
    }

    @Test void realDiskLoaderRestoresHealthyFilesButNeverReplacesUnreadableFilesWithEmptyData() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        var fresh = new DimensionDataStorage(directory.toFile(), null, null);
        var source = XBusWiringSavedData.get(fresh, directory); var a = target(0); var b = target(4);
        var added = source.add(source.view(a.device()), "wire", a, b, 8, ignored -> {});
        fresh.save(); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var file = directory.resolve("create_biotech_xbus_wiring.dat");
        var good = Files.readAllBytes(file);
        // This positive control rules out bootstrap failures masquerading as NBT parse failures.
        assertEquals(added, XBusWiringSavedData.get(new DimensionDataStorage(directory.toFile(), null, null), directory).view(a.device()));
        for (byte[] damaged : List.of(new byte[] {31, -117, 0, 7}, new byte[] {10, 0, 0, 1})) {
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            Files.write(file, damaged);
            // Baseline control in this test-only temporary directory: vanilla conflates a parse failure with absence.
            // Never mutate or save this fallback; the production protection remains enabled throughout the test.
            var vanilla = new DimensionDataStorage(directory.toFile(), null, null);
            var fallback = vanilla.computeIfAbsent(new SavedData.Factory<>(XBusWiringSavedData::new,
                XBusWiringSavedData::load), "create_biotech_xbus_wiring");
            assertTrue(fallback.view(a.device()).entries().isEmpty());
            assertArrayEquals(damaged, Files.readAllBytes(file));
            var storage = new DimensionDataStorage(directory.toFile(), null, null);
            var blocked = XBusWiringSavedData.get(storage, directory);
            assertEquals("invalid_save", assertThrows(IllegalArgumentException.class, () -> blocked.view(a.device())).getMessage());
            assertThrows(IllegalArgumentException.class, () -> blocked.add(added, "new", a, b, 8, ignored -> fail("bad file preflight")));
            assertThrows(IllegalArgumentException.class, () -> blocked.remove(added, "wire", ignored -> fail("bad file preflight")));
            blocked.setDirty(); storage.save(); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            assertArrayEquals(damaged, Files.readAllBytes(file));
            assertSame(blocked, XBusWiringSavedData.get(storage, directory));
        }
        Files.write(file, good);
        assertEquals(added, XBusWiringSavedData.get(new DimensionDataStorage(directory.toFile(), null, null), directory).view(a.device()));
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    }

    @Test void disablingDeletingAndReenablingInvalidateCapturedRevisionsWithoutTouchingOtherOwners() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        var added = store.add(store.view(a.device()), "wire", a, b, 8, ignored -> {});
        var enabled = store.setEnabled(added, "wire", true, ignored -> {});
        var first = store.enabledConnections().getFirst(); assertTrue(store.current(first));
        store.add(store.view(b.device()), "independent", b, a, 8, ignored -> {});
        assertTrue(store.current(first), "Another device's configuration is not this connection's revision");
        var disabled = store.setEnabled(enabled, "wire", false, ignored -> {});
        assertFalse(store.current(first)); assertTrue(store.enabledConnections().isEmpty());
        var reenabling = store.setEnabled(disabled, "wire", true, ignored -> {});
        assertFalse(store.current(first), "Off/on never resurrects captured authority");
        var second = store.enabledConnections().getFirst();
        var removed = store.remove(reenabling, "wire", ignored -> {});
        assertFalse(store.current(second));
        store.add(removed, "wire", a, b, 8, ignored -> {});
        assertFalse(store.current(second)); assertTrue(store.enabledConnections().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.setEnabled(enabled, "wire", true, ignored -> fail("stale preflight")));
    }

    @Test void enablePreflightFailureAndReentryCannotPublishAndBooleanTagsAreStrict() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        var before = store.add(store.view(a.device()), "wire", a, b, 8, ignored -> {});
        assertThrows(IllegalStateException.class, () -> store.setEnabled(before, "wire", true, ignored -> { throw new IllegalStateException("reply too large"); }));
        assertEquals(before, store.view(a.device()));
        assertThrows(IllegalArgumentException.class, () -> store.setEnabled(before, "wire", true,
            ignored -> store.setEnabled(before, "wire", false, none -> {})));
        assertFalse(store.view(a.device()).entries().getFirst().enabled());
        for (var invalid : List.of(net.minecraft.nbt.IntTag.valueOf(1), net.minecraft.nbt.ByteTag.valueOf((byte)2), net.minecraft.nbt.StringTag.valueOf("true"))) {
            var tag = store.save(new CompoundTag(), null);
            tag.getList("Owners", 10).getCompound(0).getList("Entries", 10).getCompound(0).put("Enabled", invalid);
            var blocked = XBusWiringSavedData.load(tag, null);
            assertThrows(IllegalArgumentException.class, blocked::enabledConnections);
            assertEquals(tag, blocked.save(new CompoundTag(), null));
        }
    }

    @Test void declarationsRoundTripInOneWorldStoreWithoutSharingDeviceNamespaces() {
        var store = new XBusWiringSavedData();
        var a = target(0); var b = target(4);
        var initial = store.view(a.device());
        store.add(initial, "wire", a, b, 8, ignored -> {});
        assertEquals(1, store.view(a.device()).revision());
        assertEquals(0, store.view(b.device()).revision());
        assertTrue(store.view(b.device()).entries().isEmpty());
        var restored = XBusWiringSavedData.load(store.save(new CompoundTag(), null), null);
        assertEquals(store.view(a.device()), restored.view(a.device()));
        assertEquals(new XBusWiringSavedData.Entry("wire", a, b), restored.view(a.device()).entries().getFirst());
    }

    @Test void staleConfigurationCannotOverwriteANewerDeclaration() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        var initial = store.view(a.device());
        var accepted = store.add(initial, "first", a, b, 8, ignored -> {});
        var failure = assertThrows(IllegalArgumentException.class,
            () -> store.add(initial, "late", a, b, 8, ignored -> fail("stale view reached preflight")));
        assertEquals("stale", failure.getMessage());
        assertEquals(accepted, store.view(a.device()));
    }

    static XBusCableTarget target(int x) {
        UUID identity = UUID.randomUUID();
        return new XBusCableTarget(new BlockPos(x, 64, 0), "computer", "port" + x, "x0", identity, identity);
    }

    @Test void malformedOrFutureSaveIsRetainedAndCannotBecomeAnEmptyWritableStore() {
        var source = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        source.add(source.view(a.device()), "wire", a, b, 8, ignored -> {});
        for (String fault : List.of("version", "revision_type", "revision_negative", "duplicate_owner", "duplicate_name",
                "owner_mismatch", "missing_position", "short_position", "self_pin", "entries_type", "unknown_field",
                "uuid_type", "uuid_length", "entries_elements")) {
            var tag = source.save(new CompoundTag(), null);
            var owners = tag.getList("Owners", 10); var owner = owners.getCompound(0);
            var entries = owner.getList("Entries", 10); var entry = entries.getCompound(0);
            switch (fault) {
                case "version" -> tag.putInt("Version", 3);
                case "revision_type" -> owner.putInt("Revision", 1);
                case "revision_negative" -> owner.putLong("Revision", -1);
                case "duplicate_owner" -> owners.add(owner.copy());
                case "duplicate_name" -> entries.add(entry.copy());
                case "owner_mismatch" -> owner.putUUID("Device", UUID.randomUUID());
                case "missing_position" -> entry.getCompound("Peer").remove("Position");
                case "short_position" -> entry.getCompound("Peer").putIntArray("Position", new int[] {1, 2});
                case "self_pin" -> entry.put("Peer", entry.getCompound("Origin").copy());
                case "entries_type" -> owner.putString("Entries", "bad");
                case "unknown_field" -> tag.putString("Future", "retain");
                case "uuid_type" -> tag.putString("Epoch", UUID.randomUUID().toString());
                case "uuid_length" -> owner.putIntArray("Device", new int[] {1, 2, 3});
                case "entries_elements" -> {
                    var wrong = new net.minecraft.nbt.ListTag(); wrong.add(net.minecraft.nbt.StringTag.valueOf("bad"));
                    owner.put("Entries", wrong);
                }
            }
            var pristine = tag.copy(); var restored = XBusWiringSavedData.load(tag, null);
            var failure = assertThrows(IllegalArgumentException.class, () -> restored.view(a.device()), fault);
            assertEquals("invalid_save", failure.getMessage(), fault);
            assertThrows(IllegalArgumentException.class, () -> restored.add(source.view(a.device()), "new", a, b, 8, ignored -> {}), fault);
            tag.putString("AfterLoad", "external mutation");
            assertEquals(pristine, restored.save(new CompoundTag(), null), fault);
        }
    }

    @Test void additionsAreBoundedAndCannotReplaceNamesOrUseAnotherOwnersView() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        var initial = store.view(a.device());
        assertEquals("capacity", assertThrows(IllegalArgumentException.class,
            () -> store.add(initial, "wire", a, b, 0, ignored -> fail("capacity preflight"))).getMessage());
        assertEquals(initial, store.view(a.device()));
        var accepted = store.add(initial, "wire", a, b, 1, ignored -> {});
        assertEquals("capacity", assertThrows(IllegalArgumentException.class,
            () -> store.add(accepted, "second", a, b, 1, ignored -> fail("capacity preflight"))).getMessage());
        assertEquals("duplicate_name", assertThrows(IllegalArgumentException.class,
            () -> store.add(accepted, "wire", a, target(8), 8, ignored -> fail("duplicate preflight"))).getMessage());
        assertEquals("owner_mismatch", assertThrows(IllegalArgumentException.class,
            () -> store.add(accepted, "wrong", b, a, 8, ignored -> fail("wrong owner preflight"))).getMessage());
        assertEquals(accepted, store.view(a.device()));
        assertEquals(List.of(), store.view(b.device()).entries());
    }

    @Test void preflightFailureOrReentryCannotPublishAPartialOrStaleUpdate() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        var initial = store.view(a.device()); var failure = new IllegalArgumentException("response_too_large");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
            store.add(initial, "wire", a, b, 8, next -> { throw failure; })));
        assertEquals(initial, store.view(a.device())); assertFalse(store.isDirty());
        assertEquals("stale", assertThrows(IllegalArgumentException.class, () ->
            store.add(initial, "outer", a, b, 8, next -> store.add(initial, "inner", a, b, 8, ignored -> {}))).getMessage());
        assertEquals(List.of("inner"), store.view(a.device()).entries().stream().map(XBusWiringSavedData.Entry::name).toList());
        assertEquals(1, store.view(a.device()).revision());
    }

    @Test void deletionKeepsItsVersionAcrossReloadAndDoesNotResurrectAnOldEmptyView() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        var empty = store.view(a.device()); var added = store.add(empty, "wire", a, b, 8, ignored -> {});
        assertEquals("not_found", assertThrows(IllegalArgumentException.class,
            () -> store.remove(added, "missing", ignored -> fail("not found preflight"))).getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> store.remove(added, "wire", ignored -> { throw new IllegalArgumentException("preflight"); }));
        assertEquals(added, store.view(a.device()));
        var deleted = store.remove(added, "wire", next -> assertTrue(next.entries().isEmpty()));
        assertEquals(2, deleted.revision());
        var restored = XBusWiringSavedData.load(store.save(new CompoundTag(), null), null);
        assertEquals(deleted, restored.view(a.device()));
        assertEquals("stale", assertThrows(IllegalArgumentException.class,
            () -> restored.add(empty, "wire", a, b, 8, ignored -> {})).getMessage());
        assertEquals("stale", assertThrows(IllegalArgumentException.class,
            () -> restored.remove(added, "wire", ignored -> {})).getMessage());
        assertEquals(3, restored.add(deleted, "wire", a, b, 8, ignored -> {}).revision());
    }

    @Test void fixedCoordinatesDoNotTruncateOnSaveEvenOutsidePackedBlockPosRange() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        var distant = new XBusCableTarget(new BlockPos(Integer.MAX_VALUE, 4000, Integer.MIN_VALUE),
            b.domain(), b.endpoint(), b.pin(), b.device(), b.computer());
        var saved = store.add(store.view(a.device()), "wire", a, distant, 8, ignored -> {});
        assertEquals(saved, XBusWiringSavedData.load(store.save(new CompoundTag(), null), null).view(a.device()));
    }

    @Test void exhaustedVersionRejectsBeforePreflightAndPreservesItsSave() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        store.add(store.view(a.device()), "wire", a, b, 8, ignored -> {});
        var saved = store.save(new CompoundTag(), null);
        saved.getList("Owners", 10).getCompound(0).putLong("Revision", Long.MAX_VALUE);
        var restored = XBusWiringSavedData.load(saved, null); var expected = restored.view(a.device());
        assertEquals("revision_exhausted", assertThrows(IllegalArgumentException.class,
            () -> restored.add(expected, "more", a, b, 8, ignored -> fail("overflow preflight"))).getMessage());
        assertEquals("revision_exhausted", assertThrows(IllegalArgumentException.class,
            () -> restored.remove(expected, "wire", ignored -> fail("overflow preflight"))).getMessage());
        assertEquals(saved, restored.save(new CompoundTag(), null)); assertFalse(restored.isDirty());
    }

    @Test void independentOwnersMayReuseNamesButAnotherStoreCannotReuseAView() {
        var store = new XBusWiringSavedData(); var a = target(0); var b = target(4);
        var initialA = store.view(a.device()); var initialB = store.view(b.device());
        var updatedA = store.add(initialA, "same", a, b, 8, ignored -> {});
        var updatedB = store.add(initialB, "same", b, a, 8, ignored -> {});
        assertEquals(updatedA, store.view(a.device())); assertEquals(updatedB, store.view(b.device()));
        var anotherStore = new XBusWiringSavedData();
        assertEquals("stale", assertThrows(IllegalArgumentException.class,
            () -> anotherStore.add(initialA, "same", a, b, 8, ignored -> fail("wrong store preflight"))).getMessage());
        assertEquals(0, anotherStore.view(a.device()).revision());
        assertThrows(UnsupportedOperationException.class, () -> updatedA.entries().clear());
        var serialized = store.save(new CompoundTag(), null); serialized.getList("Owners", 10).clear();
        assertEquals(updatedA, XBusWiringSavedData.load(store.save(new CompoundTag(), null), null).view(a.device()));
    }
}
