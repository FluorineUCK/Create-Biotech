package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ChildExecutionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

class ProcessModelTest {
	private static final UUID PROCESS = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID GOAL = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID COMPUTER = UUID.fromString("00000000-0000-0000-0000-000000000003");
	private static final UUID CHILD = UUID.fromString("00000000-0000-0000-0000-000000000004");

	@Test
	void processMustOwnExactlyOneValidComputerSlot() {
		ProductionResourceProfile librarian = profile(ProductionProfession.LIBRARIAN);
		assertThrows(IllegalArgumentException.class,
			() -> new ProcessRecord(PROCESS, GOAL, COMPUTER, -1, librarian, ProcessKind.GOAL,
				ProcessState.RUNNABLE, null, 0, Map.of()));
		assertThrows(IllegalArgumentException.class,
			() -> new ProcessRecord(PROCESS, GOAL, COMPUTER, librarian.processSlots(), librarian,
				ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of()));
	}

	@Test
	void alchemistProcessesCannotOccupyAnIncapableComputer() {
		ProductionResourceProfile librarian = profile(ProductionProfession.LIBRARIAN);
		assertThrows(IllegalArgumentException.class,
			() -> new ProcessRecord(PROCESS, GOAL, COMPUTER, 0, librarian, ProcessKind.AGENT,
				ProcessState.RUNNABLE, null, 0, Map.of()));
	}

	@Test
	void priorityLeaseUsesAnOrdinaryComputerSlot() {
		ProcessRecord lease = new ProcessRecord(PROCESS, GOAL, COMPUTER, 0,
			profile(ProductionProfession.LIBRARIAN), ProcessKind.PRIORITY_LEASE,
			ProcessState.RUNNABLE, null, 0, Map.of());

		assertEquals(ProcessKind.PRIORITY_LEASE, lease.kind());
	}

	@Test
	void parentRejectsStaleChildRevisionsAndChangedTotalPromises() {
		ProcessRecord parent = parent();
		ChildExecutionRevision first = revision(3, 8, 3, 5);
		ProcessRecord.RevisionAcceptance accepted = parent.acceptChildRevision(first);

		assertEquals(new ChildExecutionRevision.Acknowledgement(CHILD, 3, 8), accepted.acknowledgement());
		assertThrows(IllegalArgumentException.class,
			() -> accepted.parent().acceptChildRevision(revision(3, 8, 3, 5)));
		assertThrows(IllegalArgumentException.class,
			() -> accepted.parent().acceptChildRevision(revision(3, 9, 4, 6)));
	}

	@Test
	void irreversibleBoundaryAuthorizationRejectsUndurableAckAndPermitsExactDurableIdentity() {
		ProcessRecord.RevisionAcceptance accepted = parent().acceptChildRevision(revision(3, 8, 3, 5));

		assertThrows(IllegalStateException.class,
			() -> accepted.parent().authorizeIrreversibleChildBoundary(accepted.acknowledgement()));
		ProcessRecord durable = accepted.parent().markChildAcknowledgementDurable(accepted.acknowledgement());
		ProcessRecord.IrreversibleBoundaryPermit permit =
			durable.authorizeIrreversibleChildBoundary(accepted.acknowledgement());
		assertEquals(PROCESS, permit.parentProcessId());
		assertEquals(accepted.acknowledgement(), permit.acknowledgement());
		assertThrows(IllegalArgumentException.class,
			() -> durable.authorizeIrreversibleChildBoundary(
				new ChildExecutionRevision.Acknowledgement(CHILD, 3, 9)));
	}

	private static ProcessRecord parent() {
		return new ProcessRecord(PROCESS, GOAL, COMPUTER, 0, profile(ProductionProfession.CLERIC),
			ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
	}

	private static ChildExecutionRevision revision(long generation, long revision,
		long immediatelyFeasible, long remaining) {
		return new ChildExecutionRevision(CHILD, generation, revision, InputMode.BULK,
			InputMode.PARTIAL, 8, immediatelyFeasible, remaining);
	}

	private static ProductionResourceProfile profile(ProductionProfession profession) {
		return ProductionResourceProfile.forProfession(profession, 1, 0).orElseThrow();
	}
}
