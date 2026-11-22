package com.nobodiiiii.createbiotech.content.factorycluster.agent;

/** Durable one-way milestones for one Agent input stage. */
public enum AgentInputPhase {
	QUEUED,
	READY,
	DISPATCH_INTENT,
	DISPATCH_CONFIRMED,
	INPUT_CLOSED,
	INPUT_ENDED,
	INPUT_STALLED,
	RESET_REQUIRED
}
