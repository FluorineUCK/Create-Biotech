package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import java.util.Objects;
import java.util.Optional;

/** Public hardware facts only: no resident NBT, program, RAM, archive or mailbox contents. */
public record ComputerHardwareView(HardwareSelection.Status status, Optional<ResidentHardwareIdentity> resident,
    String profile, Optional<Effective> effective) {
    public ComputerHardwareView {
        Objects.requireNonNull(status); Objects.requireNonNull(resident);
        Objects.requireNonNull(profile); Objects.requireNonNull(effective);
        resident.ifPresent(value -> { Objects.requireNonNull(value.entityType()); Objects.requireNonNull(value.profession()); });
        if ((status == HardwareSelection.Status.READY) != effective.isPresent())
            throw new IllegalArgumentException("Inconsistent public hardware status");
    }

    public record Effective(ResourceLimits resources, HardwarePins pins, SerialHardware serial, int taskSlots, boolean isa1a, boolean isa1b) {
        public Effective {
            Objects.requireNonNull(resources); Objects.requireNonNull(pins);
            Objects.requireNonNull(serial).requireSeparateNames(pins);
            if (taskSlots < 1) throw new IllegalArgumentException("Invalid task capacity");
        }
        public Effective(ResourceLimits resources, HardwarePins pins, SerialHardware serial, int taskSlots, boolean isa1a) { this(resources, pins, serial, taskSlots, isa1a, false); }
        public Effective(ResourceLimits resources, HardwarePins pins, SerialHardware serial, int taskSlots) { this(resources, pins, serial, taskSlots, false); }
        public Effective(ResourceLimits resources, HardwarePins pins, SerialHardware serial) { this(resources, pins, serial, 1); }
        public Effective(ResourceLimits resources, HardwarePins pins) { this(resources, pins, SerialHardware.NONE); }
    }

    public static ComputerHardwareView from(HardwareSelection selection) {
        // Unavailable selections carry fallback host ceilings, NOT usable installed capacities.
        return new ComputerHardwareView(selection.status(), selection.resident()
            .filter(value -> value.entityType() != null && value.profession() != null), selection.profile(),
            selection.ready() ? Optional.of(new Effective(selection.limits().hardware(), selection.limits().pins(), selection.limits().serial(), selection.limits().taskSlots(), selection.limits().isa1a(), selection.limits().isa1b())) : Optional.empty());
    }
}
