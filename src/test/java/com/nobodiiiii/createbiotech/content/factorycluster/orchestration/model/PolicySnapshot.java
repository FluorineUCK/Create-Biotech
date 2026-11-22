package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model;

/** Frozen policy choices for a Goal or Run. */
public record PolicySnapshot(long generation, int priority, boolean directRequiredOutputs) {
	public PolicySnapshot {
		if (generation < 0)
			throw new IllegalArgumentException("Policy generation must be non-negative");
	}
}
