package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

/**
 * Explicit synthetic ISA-1a capability for algorithm/owner regression fixtures.
 * Preserves every resource, pin and device limit. Resident integration tests must
 * select an actual cleric through BiocomputingConfig instead of using this helper.
 */
public final class ParserHardwareFixture {
    private ParserHardwareFixture() {}

    public static ComputerHostLimits withIsa1a(ComputerHostLimits base) {
        return new ComputerHostLimits(base.hardware(), base.archive(), base.mailbox(),
            base.pins(), base.serial(), base.taskSlots(), true, base.isa1b());
    }

    /** Explicit synthetic master parser for tests which author dynamic register signatures. */
    public static ComputerHostLimits withMasterParser(ComputerHostLimits base) {
        return new ComputerHostLimits(base.hardware(), base.archive(), base.mailbox(),
            base.pins(), base.serial(), base.taskSlots(), true, true);
    }
}
