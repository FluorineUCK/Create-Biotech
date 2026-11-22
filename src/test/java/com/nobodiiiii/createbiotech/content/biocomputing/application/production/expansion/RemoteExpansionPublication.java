package com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion;

import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionRemoteExpansion.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.*;

/** Save before publishing any live protocol, plan or route retirement. */
public final class RemoteExpansionPublication {
	private RemoteExpansionPublication() {}
	@FunctionalInterface
	public interface RemoteComputationRetirementBatchWriter {
		void persist(RemoteComputationLifecycleState lifecycle,
			RadioRouter.LeaseProtocolState leases);
	}

	@FunctionalInterface
	public interface RemoteComputationTerminationBatchWriter {
		void persist(RemoteComputationLifecycleState lifecycle,
			RadioRouter.LeaseProtocolState leases, DurableRadioOutbox.State outbox);
	}

	@FunctionalInterface
	public interface RemoteMaterialHandoffBatchWriter {
		void persist(FrozenProductionPlan plan, RadioRouter.LeaseProtocolState leases);
	}

	@FunctionalInterface
	public interface RemoteMaterialCancellationWriter {
		void persist(FrozenProductionPlan plan);
	}

	public static void publishRemoteMaterialHandoff(
		RemoteMaterialHandoffBatchWriter durablePublication,
		FrozenProductionPlan receipt, RemoteComputationExchange exchange,
		RadioRouter.PreparedMaterialAuthorization authorization,
		java.util.function.Consumer<FrozenProductionPlan> planInstaller,
		java.util.function.Consumer<RadioRouter.LeaseProtocolState> routerInstaller) {
		durablePublication = Objects.requireNonNull(durablePublication, "durablePublication");
		receipt = Objects.requireNonNull(receipt, "receipt");
		exchange = Objects.requireNonNull(exchange, "exchange");
		authorization = Objects.requireNonNull(authorization, "authorization");
		planInstaller = Objects.requireNonNull(planInstaller, "planInstaller");
		routerInstaller = Objects.requireNonNull(routerInstaller, "routerInstaller");
		FrozenProductionPlan.RemoteExpansionInstallation installation = receipt.remoteExpansion()
			.orElseThrow(() -> new IllegalArgumentException("material handoff lacks expansion"));
		if (!exchange.materialDispatchAllowed()
			|| !remoteMaterialHandoffOwnsRun(receipt, exchange.runId())
			|| !receipt.goalId().equals(exchange.goalId())
			|| !installation.request().equals(exchange.lease().expansionRequest())
			|| !authorization.leaseId().equals(exchange.lease().leaseId())
			|| authorization.revision() != exchange.revision()
			|| !authorization.receiver().equals(exchange.offerEnvelope().targetHop().key()))
			throw new IllegalArgumentException("remote material handoff fences disagree");
		durablePublication.persist(receipt, authorization.state());
		planInstaller.accept(receipt);
		routerInstaller.accept(authorization.state());
	}

	public static boolean publishRemoteMaterialCancellation(
		RemoteMaterialCancellationWriter durablePublication,
		FrozenProductionPlan plan, RemoteComputationExchange exchange, RadioRouter router,
		java.util.function.Consumer<FrozenProductionPlan> planInstaller) {
		durablePublication = Objects.requireNonNull(durablePublication, "durablePublication");
		plan = Objects.requireNonNull(plan, "plan");
		exchange = Objects.requireNonNull(exchange, "exchange");
		router = Objects.requireNonNull(router, "router");
		planInstaller = Objects.requireNonNull(planInstaller, "planInstaller");
		if (!remoteMaterialHandoffMayPublish(plan, exchange, router, () -> false)) return false;
		if (plan.cancelAfterDrain()) return true;
		FrozenProductionPlan cancelled = plan.cancelAfterDrainNow();
		durablePublication.persist(cancelled);
		planInstaller.accept(cancelled);
		return true;
	}

	public static void publishRemoteComputationRetirement(
		RemoteComputationRetirementBatchWriter durablePublication,
		RemoteComputationLifecycleState lifecycle,
		RadioRouter.CompletedLeaseRetirement routerRetirement,
		java.util.function.Consumer<RemoteComputationLifecycleState> lifecycleInstaller,
		java.util.function.Consumer<RadioRouter.CompletedLeaseRetirement> routerInstaller,
		java.util.function.Predicate<UUID> deliveredRouteRetirer,
		List<UUID> deliveredMessageIds) {
		durablePublication = Objects.requireNonNull(durablePublication,
			"durablePublication");
		lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
		routerRetirement = Objects.requireNonNull(routerRetirement, "routerRetirement");
		lifecycleInstaller = Objects.requireNonNull(lifecycleInstaller, "lifecycleInstaller");
		routerInstaller = Objects.requireNonNull(routerInstaller, "routerInstaller");
		deliveredRouteRetirer = Objects.requireNonNull(deliveredRouteRetirer,
			"deliveredRouteRetirer");
		deliveredMessageIds = List.copyOf(Objects.requireNonNull(deliveredMessageIds,
			"deliveredMessageIds"));
		if (deliveredMessageIds.size() != 4
			|| new LinkedHashSet<>(deliveredMessageIds).size() != deliveredMessageIds.size())
			throw new IllegalArgumentException(
				"remote computation retirement requires four exact protocol messages");
		validateRemoteComputationRetirementPair(lifecycle, routerRetirement);
		durablePublication.persist(lifecycle, routerRetirement.state());
		lifecycleInstaller.accept(lifecycle);
		routerInstaller.accept(routerRetirement);
		for (UUID messageId : deliveredMessageIds)
			if (!deliveredRouteRetirer.test(messageId))
				throw new IllegalStateException(
					"durably retired remote message still has a non-final route");
	}

	public static void publishRemoteComputationTermination(
		RemoteComputationTerminationBatchWriter durablePublication,
		RemoteComputationLifecycleState lifecycle,
		RadioRouter.TerminalLeaseRetirement routerRetirement,
		DurableRadioOutbox.State outbox,
		java.util.function.Consumer<RemoteComputationLifecycleState> lifecycleInstaller,
		java.util.function.Consumer<RadioRouter.TerminalLeaseRetirement> routerInstaller,
		java.util.function.Consumer<DurableRadioOutbox.State> outboxInstaller,
		java.util.function.Predicate<UUID> terminalRouteRetirer,
		List<UUID> messageIds) {
		durablePublication = Objects.requireNonNull(durablePublication, "durablePublication");
		lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
		routerRetirement = Objects.requireNonNull(routerRetirement, "routerRetirement");
		outbox = Objects.requireNonNull(outbox, "outbox");
		lifecycleInstaller = Objects.requireNonNull(lifecycleInstaller, "lifecycleInstaller");
		routerInstaller = Objects.requireNonNull(routerInstaller, "routerInstaller");
		outboxInstaller = Objects.requireNonNull(outboxInstaller, "outboxInstaller");
		terminalRouteRetirer = Objects.requireNonNull(terminalRouteRetirer,
			"terminalRouteRetirer");
		messageIds = List.copyOf(Objects.requireNonNull(messageIds, "messageIds"));
		if (messageIds.isEmpty() || messageIds.size() > 4
			|| new LinkedHashSet<>(messageIds).size() != messageIds.size())
			throw new IllegalArgumentException("remote termination has invalid message ownership");
		validateRemoteComputationTerminationPair(lifecycle, routerRetirement);
		for (UUID messageId : messageIds)
			if (outbox.entries().containsKey(messageId))
				throw new IllegalArgumentException("terminal outbox retains an aborted message");
		durablePublication.persist(lifecycle, routerRetirement.state(), outbox);
		lifecycleInstaller.accept(lifecycle);
		routerInstaller.accept(routerRetirement);
		outboxInstaller.accept(outbox);
		for (UUID messageId : messageIds)
			if (!terminalRouteRetirer.test(messageId))
				throw new IllegalStateException("durably aborted remote route was not retired");
	}

	private static void validateRemoteComputationRetirementPair(
		RemoteComputationLifecycleState lifecycle,
		RadioRouter.CompletedLeaseRetirement routerRetirement) {
		List<RetiredRemoteComputation> lifecycleMatches = lifecycle.retiredChildren().values()
			.stream().filter(retired ->
				retired.leaseId().equals(routerRetirement.leaseId())).toList();
		List<RadioRouter.RetiredLeaseState> routerMatches = routerRetirement.state()
			.retiredLineages().values().stream().filter(retired ->
				retired.leaseId().equals(routerRetirement.leaseId())).toList();
		if (lifecycleMatches.size() != 1 || routerMatches.size() != 1)
			throw new IllegalArgumentException(
				"remote computation retirement tombstones are not paired");
		RetiredRemoteComputation computation = lifecycleMatches.getFirst();
		RadioRouter.RetiredLeaseState protocol = routerMatches.getFirst();
		if (lifecycle.exchanges().containsKey(computation.runId())
			|| lifecycle.jobs().containsKey(computation.runId())
			|| !computation.childProcessId().equals(protocol.lineage().childProcessId())
			|| !computation.sourceGoalId().equals(protocol.lineage().sourceGoalId())
			|| computation.revisionFence() != protocol.revisionFence()
			|| computation.outcome() != RemoteComputationTerminalOutcome.COMPLETED
			|| protocol.outcome() != RadioRouter.LeaseTerminalOutcome.COMPLETED
			|| !computation.requestFingerprint().equals(protocol.requestFingerprint())
			|| !computation.resultFingerprint().equals(protocol.resultFingerprint()))
			throw new IllegalArgumentException(
				"remote computation retirement tombstones disagree");
	}

	private static void validateRemoteComputationTerminationPair(
		RemoteComputationLifecycleState lifecycle,
		RadioRouter.TerminalLeaseRetirement routerRetirement) {
		List<RetiredRemoteComputation> lifecycleMatches = lifecycle.retiredChildren().values()
			.stream().filter(retired -> retired.leaseId().equals(routerRetirement.leaseId())).toList();
		List<RadioRouter.RetiredLeaseState> routerMatches = routerRetirement.state()
			.retiredLineages().values().stream().filter(retired ->
				retired.leaseId().equals(routerRetirement.leaseId())).toList();
		if (lifecycleMatches.size() != 1 || routerMatches.size() != 1)
			throw new IllegalArgumentException("remote termination tombstones are not paired");
		RetiredRemoteComputation computation = lifecycleMatches.getFirst();
		RadioRouter.RetiredLeaseState protocol = routerMatches.getFirst();
		RemoteComputationTerminalOutcome expected = switch (routerRetirement.outcome()) {
			case CANCELLED -> RemoteComputationTerminalOutcome.CANCELLED;
			case MIGRATED -> RemoteComputationTerminalOutcome.MIGRATED;
			case COMPLETED -> throw new IllegalArgumentException(
				"completed lease used terminal retirement");
		};
		if (lifecycle.exchanges().containsKey(computation.runId())
			|| lifecycle.jobs().containsKey(computation.runId())
			|| !computation.childProcessId().equals(protocol.lineage().childProcessId())
			|| !computation.sourceGoalId().equals(protocol.lineage().sourceGoalId())
			|| computation.revisionFence() != protocol.revisionFence()
			|| computation.outcome() != expected
			|| !computation.requestFingerprint().equals(protocol.requestFingerprint())
			|| computation.resultFingerprint().isPresent()
			|| protocol.resultFingerprint().isPresent())
			throw new IllegalArgumentException("remote termination tombstones disagree");
	}


}
