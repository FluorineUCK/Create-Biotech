package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/** Physical ownership transfer. Not a program effect, production admission or a RAM operation. */
public final class ComputerResidentInstallation {
    private static final ThreadLocal<Set<Target>> ACTIVE = ThreadLocal.withInitial(
        () -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private ComputerResidentInstallation() {}

    public record Candidate(UUID uuid, ResidentHardwareIdentity hardware) {
        public Candidate { Objects.requireNonNull(uuid); Objects.requireNonNull(hardware); }
    }
    public record Inspection(ComputerInstallResult result, @Nullable Candidate candidate) {
        public Inspection {
            Objects.requireNonNull(result);
            if ((result == ComputerInstallResult.SUCCESS) != (candidate != null))
                throw new IllegalArgumentException("Only a successful inspection has a candidate");
        }
        public static Inspection success(Candidate candidate) {
            return new Inspection(ComputerInstallResult.SUCCESS, candidate);
        }
        public static Inspection failure(ComputerInstallResult result) { return new Inspection(result, null); }
    }
    public interface Target {
        /** Read-only preflight, including occupancy and any temporary host migration constraints. */
        ComputerInstallResult installationStatus();
        /** Read-only preparation. Neither the live source nor a world entity is exposed here. */
        Prepared prepareResidentInstallation(ItemStack snapshot, Candidate candidate);
    }
    public interface Prepared {
        /** Apply only local ownership state; do not publish external effects until published(). */
        void commit();
        void rollback();
        /** Called after source consumption. Failure here must never resurrect a second source. */
        void published();
    }
    public interface Operations {
        boolean isBox(ItemStack stack);
        boolean hasCapturedEntity(ItemStack stack);
        Inspection inspect(ItemStack snapshot);
        /** Only the CUSTOM_DATA component may be changed; never spawn an entity or mutate inventory. */
        void clearCapturedEntity(ItemStack held);
    }

    public static ComputerInstallResult install(ItemStack held, Target target, Operations operations) {
        return install(held, target, operations, () -> true);
    }

    public static ComputerInstallResult install(ItemStack held, Target target, Operations operations,
        java.util.function.BooleanSupplier authority) {
        Objects.requireNonNull(held); Objects.requireNonNull(target); Objects.requireNonNull(operations);
        Objects.requireNonNull(authority);
        var active = ACTIVE.get();
        if (!active.add(target)) return ComputerInstallResult.BUSY;
        try {
            if (!authority.getAsBoolean()) return ComputerInstallResult.SOURCE_CHANGED;
            if (!operations.isBox(held)) return ComputerInstallResult.NOT_BOX;
            if (!operations.hasCapturedEntity(held)) return ComputerInstallResult.EMPTY_BOX;
            var status = target.installationStatus();
            if (status != ComputerInstallResult.SUCCESS) return status;
            var snapshot = held.copy();
            var inspection = operations.inspect(snapshot.copy());
            if (inspection == null) return ComputerInstallResult.INVALID_IDENTITY;
            if (inspection.result() != ComputerInstallResult.SUCCESS) return inspection.result();
            if (!authority.getAsBoolean() || !ItemStack.matches(held, snapshot)) return ComputerInstallResult.SOURCE_CHANGED;
            status = target.installationStatus();
            if (status != ComputerInstallResult.SUCCESS) return status;
            var prepared = Objects.requireNonNull(target.prepareResidentInstallation(snapshot.copy(), inspection.candidate()));
            if (!authority.getAsBoolean() || !ItemStack.matches(held, snapshot)) return ComputerInstallResult.SOURCE_CHANGED;
            status = target.installationStatus();
            if (status != ComputerInstallResult.SUCCESS) return status;
            try {
                prepared.commit();
                operations.clearCapturedEntity(held);
                if (operations.hasCapturedEntity(held))
                    throw new IllegalStateException("Resident installation did not consume its source");
            } catch (RuntimeException failure) {
                held.set(DataComponents.CUSTOM_DATA, snapshot.get(DataComponents.CUSTOM_DATA));
                try { prepared.rollback(); }
                catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
                throw failure;
            }
            prepared.published();
            return ComputerInstallResult.SUCCESS;
        } finally {
            active.remove(target);
            if (active.isEmpty()) ACTIVE.remove();
        }
    }
}
