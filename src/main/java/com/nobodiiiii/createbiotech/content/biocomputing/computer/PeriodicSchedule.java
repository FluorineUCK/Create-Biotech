package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Objects;

public record PeriodicSchedule(long intervalTicks, long remainingTicks, boolean enabled, DataValue.RecordValue inputs) {
	public PeriodicSchedule {
		if (intervalTicks <= 0 || remainingTicks < 0 || remainingTicks > intervalTicks)
			throw new IllegalArgumentException("Invalid periodic interval/countdown");
		Objects.requireNonNull(inputs);
	}
	public PeriodicSchedule withRemaining(long remaining) { return new PeriodicSchedule(intervalTicks, remaining, enabled, inputs); }
	public PeriodicSchedule withEnabled(boolean value) { return new PeriodicSchedule(intervalTicks, remainingTicks, value, inputs); }
}
