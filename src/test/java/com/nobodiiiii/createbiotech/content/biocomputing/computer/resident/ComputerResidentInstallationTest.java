package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ComputerResidentInstallationTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException { ComputerResidentTest.bootstrap(); }

    @Test void independentPhysicalHostNeedsNoProductionProfileAndOwnsADetachedSnapshot() {
        var host = new Host(); var held = box(); var before = held.copy();
        var ops = new Ops();
        ops.inspect = snapshot -> snapshot.setCount(12); // Inspection only sees its own copy.
        ops.clear = stack -> {
            assertTrue(ItemStack.matches(before, host.resident.snapshot()));
            CapturedEntityBoxHelper.clearCapturedEntity(stack);
        };
        assertEquals(SUCCESS, ComputerResidentInstallation.install(held, host, ops));
        assertTrue(ItemStack.matches(before, host.resident.snapshot()));
        assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(held));
        assertEquals("keep", CBItemData.getOrEmpty(held).getString("note"));
        assertEquals(1, host.published);
        held.setCount(9); assertEquals(1, host.resident.snapshot().getCount());
    }

    @Test void clearMustActuallyConsumeTheCapturedSourceOrRestoreOwnership() {
        var host = new Host(); var held = box(); var before = held.copy(); var ops = new Ops();
        ops.clear = ignored -> {};
        assertThrows(IllegalStateException.class, () -> ComputerResidentInstallation.install(held, host, ops));
        assertFalse(host.resident.hasSource()); assertTrue(ItemStack.matches(before, held));
        assertEquals(0, host.published);
    }

    @Test void localCommitAndClearFailuresRestoreSourceAndNeverPublish() {
        for (boolean commitFailure : new boolean[]{true, false}) {
            var host = new Host(); var held = box(); var before = held.copy(); var ops = new Ops();
            host.failCommit = commitFailure;
            if (!commitFailure) ops.clear = stack -> {
                CapturedEntityBoxHelper.clearCapturedEntity(stack); throw new IllegalStateException("clear");
            };
            assertThrows(IllegalStateException.class, () -> ComputerResidentInstallation.install(held, host, ops));
            assertFalse(host.resident.hasSource()); assertTrue(ItemStack.matches(before, held));
            assertEquals(0, host.published);
            host.failCommit = false;
            assertEquals(SUCCESS, ComputerResidentInstallation.install(held, host, new Ops()), "Guard released after failure");
        }
    }

    @Test void notificationFailureDoesNotResurrectASecondSource() {
        var host = new Host(); var held = box(); host.failPublish = true;
        assertThrows(IllegalStateException.class, () -> ComputerResidentInstallation.install(held, host, new Ops()));
        assertTrue(host.resident.hasSource()); assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(held));
        var next = box(); var before = next.copy();
        assertEquals(OCCUPIED, ComputerResidentInstallation.install(next, host, new Ops()));
        assertTrue(ItemStack.matches(before, next));
    }

    @Test void inspectionOrPreparationChangingSourceIsRejectedWithoutUndoingExternalChange() {
        for (boolean duringPreparation : new boolean[]{true, false}) {
            var host = new Host(); var held = box(); var ops = new Ops();
            Runnable mutate = () -> held.setCount(2);
            if (duringPreparation) host.prepare = mutate; else ops.inspect = ignored -> mutate.run();
            assertEquals(SOURCE_CHANGED, ComputerResidentInstallation.install(held, host, ops));
            assertEquals(2, held.getCount()); assertFalse(host.resident.hasSource()); assertEquals(0, host.published);
        }
    }

    @Test void reentrantInstallationCannotAcquireTheSamePhysicalOwnerTwice() {
        var host = new Host(); var held = box(); var ops = new Ops();
        ops.inspect = ignored -> assertEquals(BUSY, ComputerResidentInstallation.install(box(), host, new Ops()));
        assertEquals(SUCCESS, ComputerResidentInstallation.install(held, host, ops));
        assertEquals(1, host.published);
    }

    @Test void identicalReplacementHandOrHostStillLosesItsAuthority() {
        var host = new Host(); var held = box(); var before = held.copy(); var ops = new Ops();
        boolean[] authority = {true};
        ops.inspect = ignored -> authority[0] = false;
        assertEquals(SOURCE_CHANGED, ComputerResidentInstallation.install(held, host, ops, () -> authority[0]));
        assertTrue(ItemStack.matches(before, held)); assertFalse(host.resident.hasSource());
        assertEquals(0, host.published);
    }

    @Test void revokedHostAndInspectionRejectionLeaveBothSourcesUntouched() {
        var host = new Host(); var held = box(); var before = held.copy(); var ops = new Ops();
        ops.inspect = ignored -> host.status = ACTIVE_TOPOLOGY;
        assertEquals(ACTIVE_TOPOLOGY, ComputerResidentInstallation.install(held, host, ops));
        assertTrue(ItemStack.matches(before, held)); assertFalse(host.resident.hasSource());
        host.status = SUCCESS; ops = new Ops(); ops.rejection = PASSENGERS_UNSUPPORTED;
        assertEquals(PASSENGERS_UNSUPPORTED, ComputerResidentInstallation.install(held, host, ops));
        assertTrue(ItemStack.matches(before, held)); assertEquals(0, host.published);
    }

    private static ItemStack box() {
        var stack = new ItemStack(Items.PAPER); var data = new CompoundTag(); var entity = new CompoundTag();
        entity.putString("id", "minecraft:villager"); entity.putUUID("UUID", UUID.randomUUID());
        data.put("CapturedEntity", entity); data.putString("note", "keep"); CBItemData.set(stack, data); return stack;
    }
    private static class Host implements ComputerResidentInstallation.Target {
        final ComputerResident resident = new ComputerResident();
        ComputerInstallResult status = SUCCESS;
        Runnable prepare = () -> {};
        boolean failCommit, failPublish;
        int published;
        @Override public ComputerInstallResult installationStatus() { return resident.hasSource() ? OCCUPIED : status; }
        @Override public ComputerResidentInstallation.Prepared prepareResidentInstallation(ItemStack snapshot, ComputerResidentInstallation.Candidate candidate) {
            prepare.run(); var before = resident.snapshot();
            return new ComputerResidentInstallation.Prepared() {
                @Override public void commit() { resident.restoreSnapshot(snapshot); if (failCommit) throw new IllegalStateException("commit"); }
                @Override public void rollback() { resident.restoreSnapshot(before); }
                @Override public void published() { published++; if (failPublish) throw new IllegalStateException("publish"); }
            };
        }
    }
    private static class Ops implements ComputerResidentInstallation.Operations {
        java.util.function.Consumer<ItemStack> inspect = ignored -> {};
        java.util.function.Consumer<ItemStack> clear = CapturedEntityBoxHelper::clearCapturedEntity;
        ComputerInstallResult rejection;
        @Override public boolean isBox(ItemStack stack) { return true; }
        @Override public boolean hasCapturedEntity(ItemStack stack) { return CapturedEntityBoxHelper.hasCapturedEntity(stack); }
        @Override public ComputerResidentInstallation.Inspection inspect(ItemStack snapshot) {
            inspect.accept(snapshot);
            if (rejection != null) return ComputerResidentInstallation.Inspection.failure(rejection);
            return ComputerResidentInstallation.Inspection.success(new ComputerResidentInstallation.Candidate(
                ComputerResident.identity(snapshot).uuid(), new ResidentHardwareIdentity(
                    ResourceLocation.withDefaultNamespace("villager"), ResourceLocation.withDefaultNamespace("librarian"), 3)));
        }
        @Override public void clearCapturedEntity(ItemStack stack) { clear.accept(stack); }
    }
}
