package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import java.util.Objects;

/** Current caller-owned hardware and persistence budgets; never read from a computer archive. */
public record ComputerHostLimits(ResourceLimits hardware, ArchiveLimits archive, MailboxLimits mailbox, HardwarePins pins,
    SerialHardware serial, int taskSlots, boolean isa1a, boolean isa1b) {
	public ComputerHostLimits {
        Objects.requireNonNull(hardware); Objects.requireNonNull(archive); Objects.requireNonNull(mailbox); Objects.requireNonNull(pins);
        Objects.requireNonNull(serial).requireSeparateNames(pins);
        if (taskSlots < 1) throw new IllegalArgumentException("At least one task slot is required");
    }
    public ComputerHostLimits withMemoryUnits(long capacity) {
        return capacity == hardware.memoryUnits() ? this : new ComputerHostLimits(
            hardware.withMemoryUnits(capacity),
            archive, mailbox, pins, serial, taskSlots, isa1a, isa1b);
    }

    /** Parser support does not imply master-level type support. */
    public ComputerHostLimits(ResourceLimits hardware, ArchiveLimits archive, MailboxLimits mailbox, HardwarePins pins, SerialHardware serial, int taskSlots, boolean isa1a) {
        this(hardware, archive, mailbox, pins, serial, taskSlots, isa1a, false);
    }
    /** Unspecified hardware must not silently acquire structured-data execution capability. */
    public ComputerHostLimits(ResourceLimits hardware, ArchiveLimits archive, MailboxLimits mailbox, HardwarePins pins, SerialHardware serial, int taskSlots) {
        this(hardware, archive, mailbox, pins, serial, taskSlots, false);
    }
    public ComputerHostLimits(ResourceLimits hardware, ArchiveLimits archive, MailboxLimits mailbox, HardwarePins pins, SerialHardware serial) {
        this(hardware, archive, mailbox, pins, serial, 1);
    }
    /** Legacy/test suppliers do not silently acquire physical serial hardware. */
    public ComputerHostLimits(ResourceLimits hardware, ArchiveLimits archive, MailboxLimits mailbox, HardwarePins pins) {
        this(hardware, archive, mailbox, pins, SerialHardware.NONE);
    }
	public ComputerHostLimits(ResourceLimits hardware, ArchiveLimits archive, MailboxLimits mailbox) { this(hardware, archive, mailbox, HardwarePins.DEFAULT); }
	public ComputerHostLimits(ResourceLimits hardware, ArchiveLimits archive) { this(hardware, archive, MailboxLimits.DEFAULT); }
}
