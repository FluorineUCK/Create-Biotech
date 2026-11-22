package com.nobodiiiii.createbiotech.content.factorycluster.agent;

/** Durable target-side phases. Arrival order is never used as evidence for a transition. */
public enum RemoteAgentPhase {
	QUEUED,
	RESERVED,
	DISPATCH_INTENT,
	DISPATCH_CONFIRMED,
	INPUT_ACTIVE,
	OUTPUT_DRAIN,
	RELEASED,
	RESET_REQUIRED
}
