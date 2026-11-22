package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import java.util.Objects;
import java.util.Optional;

/** One coherent, read-only resolution of resident hardware and its effective server limits. */
public record HardwareSelection(Status status, Optional<ResidentHardwareIdentity> resident,
	String profile, ComputerHostLimits limits) {
	public HardwareSelection {
		Objects.requireNonNull(status);
		Objects.requireNonNull(resident);
		Objects.requireNonNull(profile);
		Objects.requireNonNull(limits);
	}

	public enum Status {
		READY,
		NO_RESIDENT,
		INVALID_RESIDENT,
		UNSUPPORTED_RESIDENT,
		INVALID_PINS,
		INVALID_SERIAL,
		DISABLED
	}

	public boolean ready() {
		return status == Status.READY;
	}
}
