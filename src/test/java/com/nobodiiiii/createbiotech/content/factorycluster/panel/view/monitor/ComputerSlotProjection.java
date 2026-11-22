package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;

/** One bounded frozen slot, including explicit empty slots. */
public record ComputerSlotProjection(int slotIndex, Optional<UUID> processId,
	Optional<ProcessKind> processKind, Optional<UUID> goalId, String goalOutput,
	Optional<ProcessState> state, Optional<WaitReason> waitReason, RemoteState remoteState,
	boolean patternSelected) {
	public ComputerSlotProjection {
		processId = Objects.requireNonNull(processId, "processId");
		processKind = Objects.requireNonNull(processKind, "processKind");
		goalId = Objects.requireNonNull(goalId, "goalId");
		goalOutput = Objects.requireNonNull(goalOutput, "goalOutput");
		state = Objects.requireNonNull(state, "state");
		waitReason = Objects.requireNonNull(waitReason, "waitReason");
		remoteState = Objects.requireNonNull(remoteState, "remoteState");
		if (slotIndex < 0 || goalOutput.length() > WorldFact.MAX_TEXT_LENGTH)
			throw new IllegalArgumentException("invalid Computer slot");
		goalOutput = ComputerMonitorSnapshot.safeText(goalOutput);
		boolean occupied = processId.isPresent();
		if (occupied != processKind.isPresent() || occupied != goalId.isPresent()
			|| occupied != state.isPresent())
			throw new IllegalArgumentException("occupied Computer slot fields disagree");
		if (!occupied && (!goalOutput.isEmpty() || waitReason.isPresent()
			|| remoteState != RemoteState.NONE || patternSelected))
			throw new IllegalArgumentException("empty Computer slot carries process state");
		if (state.isPresent() && (state.orElseThrow() == ProcessState.WAITING)
			!= waitReason.isPresent())
			throw new IllegalArgumentException("only a waiting process has a wait reason");
	}

	public static ComputerSlotProjection empty(int slotIndex) {
		return new ComputerSlotProjection(slotIndex, Optional.empty(), Optional.empty(),
			Optional.empty(), "", Optional.empty(), Optional.empty(), RemoteState.NONE, false);
	}

	public static ComputerSlotProjection occupied(int slotIndex, UUID processId,
		ProcessKind processKind, UUID goalId, String goalOutput, ProcessState state,
		Optional<WaitReason> waitReason, RemoteState remoteState, boolean patternSelected) {
		return new ComputerSlotProjection(slotIndex, Optional.of(processId), Optional.of(processKind),
			Optional.of(goalId), goalOutput, Optional.of(state), waitReason, remoteState,
			patternSelected);
	}

	public enum RemoteState {
		NONE,
		WAITING,
		RUNNING,
		RECOVERING,
		FAULT
	}
}
