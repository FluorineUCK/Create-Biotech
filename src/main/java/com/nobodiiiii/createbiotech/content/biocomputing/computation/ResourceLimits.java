package com.nobodiiiii.createbiotech.content.biocomputing.computation;

/** Supplied by hardware/config adapters, never derived from production processSlots. */
public record ResourceLimits(long programUnits, long memoryUnits, long workPerSlice, int registerSlots) {
    /** Legacy, graph-only or unbounded analysis callers do not impose a physical register count. */
    public ResourceLimits(long programUnits,long memoryUnits,long workPerSlice) {
        this(programUnits,memoryUnits,workPerSlice,Integer.MAX_VALUE);
    }
    public ResourceLimits withMemoryUnits(long memory) {
        return new ResourceLimits(programUnits,memory,workPerSlice,registerSlots);
    }
	public ResourceLimits {
		if (programUnits < 0 || memoryUnits < 0 || workPerSlice <= 0 || registerSlots < 1)
			throw new IllegalArgumentException("Capacities must be nonnegative and workPerSlice positive");
	}
}
