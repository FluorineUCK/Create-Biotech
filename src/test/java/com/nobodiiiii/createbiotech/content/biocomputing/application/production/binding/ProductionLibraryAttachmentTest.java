package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionLibraryAttachment.Preparation.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ProductionLibraryAttachmentTest {
    private static final UUID GROUP = new UUID(0, 1), MEMBER = new UUID(0, 2), NETWORK = new UUID(0, 3);

    @Test void preparationDoesNotCommitAndTopologyChangesRespectIdentityIdleAndRevision() {
        var owner = new ProductionLibraryAttachment(); var first = binding(4);
        assertNull(owner.binding()); assertTrue(owner.valid()); assertTrue(owner.canRebind(true));
        assertEquals(READY, owner.prepareRebind(first, true, true)); assertNull(owner.binding());
        assertEquals(IDENTITY, owner.prepareRebind(first, false, false));
        assertEquals(IDENTITY, owner.prepareRebind(null, true, true));
        assertEquals(IDENTITY, owner.prepareRebind(new ProductionBinding(GROUP, 4, null, List.of()), true, true));
        assertEquals(ACTIVE, owner.prepareRebind(first, true, false));
        owner.commit(first); assertFalse(owner.canRebind(false));
        assertEquals(READY, owner.prepareRebind(binding(4), true, true));
        assertEquals(REVISION, owner.prepareRebind(binding(3), true, true));
        assertEquals(REVISION, owner.prepareRebind(new ProductionBinding(GROUP, 4, first.authority(), List.of(NETWORK)), true, true));
        assertEquals(READY, owner.prepareRebind(new ProductionBinding(MEMBER, 5, first.authority(), List.of()), true, true));
        assertEquals(first, owner.binding());
    }

    @Test void logisticsUpdateRequiresExistingExactAuthorityAndStrictlyNewerRevision() {
        var owner = new ProductionLibraryAttachment(); var next = binding(5);
        assertEquals(IDENTITY, owner.prepareLogistics(next, true));
        owner.commit(new ProductionBinding(GROUP, 4, null, List.of()));
        assertEquals(IDENTITY, owner.prepareLogistics(next, true));
        owner.commit(binding(4));
        assertEquals(IDENTITY, owner.prepareLogistics(next, false));
        assertEquals(IDENTITY, owner.prepareLogistics(null, true));
        assertEquals(IDENTITY, owner.prepareLogistics(new ProductionBinding(MEMBER, 5, next.authority(), List.of()), true));
        assertEquals(IDENTITY, owner.prepareLogistics(new ProductionBinding(GROUP, 5,
            new ProductionBinding.Authority(ProductionBinding.Role.LIBRARY, MEMBER), List.of()), true));
        assertEquals(IDENTITY, owner.prepareLogistics(new ProductionBinding(GROUP, 5,
            new ProductionBinding.Authority(ProductionBinding.Role.COORDINATOR, NETWORK), List.of()), true));
        assertEquals(REVISION, owner.prepareLogistics(binding(4), true));
        assertEquals(REVISION, owner.prepareLogistics(binding(3), true));
        assertEquals(READY, owner.prepareLogistics(next, true)); assertEquals(binding(4), owner.binding());
        var changed = new ProductionBinding(GROUP, 5, next.authority(), List.of(NETWORK));
        owner.commit(changed); assertEquals(List.of(NETWORK), owner.logisticsIds());
        assertEquals(REVISION, owner.prepareLogistics(changed, true));
    }

    @Test void bindingArchiveIsStagedIndependentAndDoesNotOverwriteOtherOwnerFields() {
        var owner = new ProductionLibraryAttachment(); owner.commit(binding(4));
        var tag = new CompoundTag(); tag.putString("OtherOwner", "keep"); owner.write(tag);
        assertEquals("keep", tag.getString("OtherOwner"));
        var loaded = ProductionLibraryAttachment.prepareLoad(tag).orElseThrow();
        tag.getCompound("BindingState").putLong("Revision", 98);
        assertEquals(binding(4), loaded.binding()); assertTrue(loaded.valid()); assertFalse(loaded.hadCorruption());
        var restored = new ProductionLibraryAttachment(); assertNull(restored.binding());
        restored.restore(loaded); assertEquals(binding(4), restored.binding());
        restored.commit(null); restored.write(tag);
        assertFalse(tag.contains("BindingState")); assertFalse(tag.getBoolean("BindingStateInvalid"));
        assertEquals("keep", tag.getString("OtherOwner"));
    }

    @Test void invalidAndCorruptBindingsNeverRestoreAsAuthorizedStandaloneLibraries() {
        var owner = new ProductionLibraryAttachment(); owner.commit(binding(4));
        var explicitlyInvalid = new CompoundTag(); owner.write(explicitlyInvalid); explicitlyInvalid.putBoolean("BindingStateInvalid", true);
        var denied = ProductionLibraryAttachment.prepareLoad(explicitlyInvalid).orElseThrow();
        assertEquals(binding(4), denied.binding()); assertFalse(denied.valid()); assertFalse(denied.hadCorruption());
        owner.restore(denied); assertFalse(owner.canRebind(true));
        assertEquals(IDENTITY, owner.prepareRebind(binding(5), true, true));
        assertEquals(IDENTITY, owner.prepareLogistics(binding(5), true));
        var corrupt = explicitlyInvalid.copy(); corrupt.putBoolean("BindingStateInvalid", false);
        corrupt.getCompound("BindingState").putInt("Revision", 4);
        var damaged = ProductionLibraryAttachment.prepareLoad(corrupt).orElseThrow();
        assertTrue(damaged.hadCorruption()); assertFalse(damaged.valid()); assertNull(damaged.binding());
        owner.restore(damaged); var rewritten = new CompoundTag(); owner.write(rewritten);
        assertTrue(rewritten.getBoolean("BindingStateInvalid")); assertFalse(rewritten.contains("BindingState"));
        var reloaded = ProductionLibraryAttachment.prepareLoad(rewritten).orElseThrow();
        assertFalse(reloaded.valid()); assertNull(reloaded.binding()); assertFalse(reloaded.hadCorruption());
    }

    @Test void malformedEnvelopeCannotPartiallyRestoreExistingAttachment() {
        var owner = new ProductionLibraryAttachment(); owner.commit(binding(4));
        var valid = new CompoundTag(); owner.write(valid);
        for (var mutation : List.<java.util.function.Consumer<CompoundTag>>of(
            tag -> tag.remove("BindingStateInvalid"), tag -> tag.putInt("BindingStateInvalid", 0),
            tag -> tag.putByte("BindingStateInvalid", (byte)2), tag -> tag.putString("BindingState", "bad"))) {
            var invalid = valid.copy(); mutation.accept(invalid); var before = invalid.copy();
            assertTrue(ProductionLibraryAttachment.prepareLoad(invalid).isEmpty());
            assertEquals(before, invalid); assertEquals(binding(4), owner.binding()); assertTrue(owner.valid());
        }
        assertTrue(ProductionLibraryAttachment.prepareLoad(null).isEmpty());
    }

    private static ProductionBinding binding(long revision) {
        return new ProductionBinding(GROUP, revision,
            new ProductionBinding.Authority(ProductionBinding.Role.COORDINATOR, MEMBER), List.of());
    }
}
