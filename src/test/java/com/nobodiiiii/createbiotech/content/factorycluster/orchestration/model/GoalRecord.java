package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;

/** Durable state owned by the Cluster that admitted the Goal. */
public record GoalRecord(UUID goalId, UUID owningClusterId, GoalSubmission submission,
	CreateRouteKey deliveryRoute, PolicySnapshot policy, GoalState state,
	@Nullable WaitReason waitReason) {
	public GoalRecord {
		goalId = Objects.requireNonNull(goalId, "goalId");
		owningClusterId = Objects.requireNonNull(owningClusterId, "owningClusterId");
		submission = Objects.requireNonNull(submission, "submission");
		if (!goalId.equals(submission.goalId()))
			throw new IllegalArgumentException("Goal record identity must match its submission");
		deliveryRoute = Objects.requireNonNull(deliveryRoute, "deliveryRoute");
		policy = Objects.requireNonNull(policy, "policy");
		state = Objects.requireNonNull(state, "state");
		if ((state == GoalState.WAITING) != (waitReason != null))
			throw new IllegalArgumentException("Only waiting Goals have a wait reason");
	}
}
