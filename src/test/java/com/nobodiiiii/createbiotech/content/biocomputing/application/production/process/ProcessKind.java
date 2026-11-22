package com.nobodiiiii.createbiotech.content.biocomputing.application.production.process;

public enum ProcessKind {
	GOAL,
	LIBRARY_QUERY,
	SPECULATIVE_EXPANSION,
	REMOTE_LISTENER,
	RADIO_LISTENER,
	AGENT,
	PRIORITY_LEASE,
	OUTPUT_DESTINATION,
	PURE_EXPANSION,
	PATTERN_PRIORITY_LEASE;

	public boolean requiresAlchemist() {
		return this == SPECULATIVE_EXPANSION || this == AGENT || this == PURE_EXPANSION
			|| this == PATTERN_PRIORITY_LEASE;
	}

	/** Exact same-Cluster pure-work leases stay pinned so their durable owner can release them. */
	public boolean automaticallyMigratesOnOwnerUnload() {
		return this != PURE_EXPANSION;
	}
}
