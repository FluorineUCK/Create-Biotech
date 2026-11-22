package com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.ProductionPlanFreezer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Owns the durable transaction from a validated production request to a published Goal. */
public final class ProductionAdmissions {
	public interface Persistence {
		ProductionAdmissionLedger.State loadLedger();
		void saveLedger(ProductionAdmissionLedger.State state);
		void savePrepared(ProductionAdmissionLedger.State state, FrozenProductionPlan plan, GoalObservationState observation);
		Map<String, FactoryRuntimeService.ProductionSearchSnapshot> loadSearches();
		void deleteSearchEntry(String storedKey);
		void saveSearch(FactoryRuntimeService.ProductionSearchSnapshot search);
		void deleteSearch(UUID searchId);
	}
	public interface PlanningEvidence {
		Optional<StockObservationScope> outputScope(UUID logisticsId, String deliveryAddress, StackKey output);
		StockObservationService.Observation observe(StockObservationScope scope);
		Map<StackKey, Long> availableManifest(PatternRecord pattern, UUID logisticsId);
	}
	private final int capacity;
	private final ProductionAdmissionLedger ledger;
	private final FactoryRuntimeService runtime;
	private final Persistence persistence;
	private final PlanningEvidence evidence;
	private final BiConsumer<FrozenProductionPlan, GoalObservationState> publication;
	private final BiConsumer<UUID, String> diagnostics;

	public ProductionAdmissions(int capacity, FactoryRuntimeService runtime, Persistence persistence,
		PlanningEvidence evidence, BiConsumer<FrozenProductionPlan, GoalObservationState> publication,
		BiConsumer<UUID, String> diagnostics) {
		this.capacity = capacity;
		this.runtime = Objects.requireNonNull(runtime);
		this.persistence = Objects.requireNonNull(persistence);
		this.evidence = Objects.requireNonNull(evidence);
		this.publication = Objects.requireNonNull(publication);
		this.diagnostics = Objects.requireNonNull(diagnostics);
		this.ledger = ProductionAdmissionLedger.restore(persistence.loadLedger(), capacity);
	}
	public ProductionAdmissionLedger.State snapshot() { return ledger.snapshot(); }
	public List<PendingProductionAdmission> values() { return ledger.values(); }
	public Optional<PendingProductionAdmission> get(UUID admissionId) { return ledger.get(admissionId); }
	public int liveSize() { return ledger.liveSize(); }

	/** This transaction is only for batches already validated by the native command entry path. */
	public boolean admitValidatedBatch(List<PendingProductionAdmission> batch) {
		ProductionAdmissionLedger staged = ProductionAdmissionLedger.restore(ledger.snapshot(), capacity);
		for (PendingProductionAdmission admission : List.copyOf(batch)) if (!staged.admit(admission)) return false;
		try { persistence.saveLedger(staged.snapshot()); }
		catch (RuntimeException unavailable) { return false; }
		ledger.install(staged.snapshot());
		for (PendingProductionAdmission admission : batch) diagnostics.accept(admission.clusterId(), "PENDING");
		return true;
	}
	/** Must run after recovered Library crash-demotion has been published to the production service. */
	public void restoreSearchesAfterLibraryBootstrap() {
		Set<UUID> liveSearches = new HashSet<>();
		ledger.values().forEach(admission -> admission.searchId().ifPresent(liveSearches::add));
		for (var entry : persistence.loadSearches().entrySet()) {
			var search = entry.getValue();
			if (liveSearches.contains(search.searchId())) runtime.restoreProductionSearchSnapshot(search);
			else persistence.deleteSearchEntry(entry.getKey());
		}
	}
	public void advanceTick(int limit) {
		int remaining = Math.max(1, limit);
		ProductionAdmissionLedger.State prior = ledger.snapshot();
		List<PendingProductionAdmission> batch =
			ledger.schedulingBatch(remaining);
		ProductionAdmissionLedger.State scheduled = ledger.snapshot();
		if (!scheduled.equals(prior)) {
			try {
				persistence.saveLedger(scheduled);
			} catch (RuntimeException failure) {
				ledger.install(prior);
				throw failure;
			}
		}
		for (PendingProductionAdmission admission : batch) {
			advancePendingProductionAdmission(admission);
		}
	}
	private void advancePendingProductionAdmission(PendingProductionAdmission admission) {
		if (admission.phase() == ProductionAdmissionPhase.CLEANUP_PENDING) {
			persistPendingProductionAdmission(admission.failed(admission.diagnostic()));
			return;
		}
		if (admission.phase() == ProductionAdmissionPhase.FAILED) {
			retireProductionSearch(admission);
			runtime.releaseProductionGoalProcesses(admission.goalId());
			return;
		}
		ClusterEngineState state = runtime.snapshot(admission.clusterId()).orElse(null);
		if (state == null) {
			persistPendingProductionAdmission(admission.withDiagnostic("WAIT_COMPUTER"));
			return;
		}
		if (!state.epochId().equals(admission.epochId())) {
			persistPendingProductionAdmission(admission.failed("EPOCH_FENCED"));
			return;
		}
		if (admission.phase() == ProductionAdmissionPhase.SEARCHING) {
			FactoryRuntimeService.SelectedPattern selected = runtime.selectProductionPattern(
				admission.clusterId(), admission.logisticsId(), admission.requestedOutput())
				.orElse(null);
			Optional<FactoryRuntimeService.ProductionSearchSnapshot> search =
				runtime.productionSearchSnapshot(admission.clusterId(), admission.logisticsId(),
					admission.requestedOutput());
			search.ifPresent(persistence::saveSearch);
			if (selected == null) {
				String diagnostic = search.map(snapshot -> "WAIT_" + snapshot.status())
					.orElse("WAIT_SEARCH");
				persistPendingProductionAdmission(admission.searching(
					search.map(FactoryRuntimeService.ProductionSearchSnapshot::searchId), diagnostic));
				return;
			}
			admission = preparePendingProductionAdmission(admission, selected, search).orElse(null);
			if (admission == null) return;
		}

		if (admission.phase() == ProductionAdmissionPhase.PREPARED
			|| admission.phase() == ProductionAdmissionPhase.GOAL_PUBLISHED) {
			FrozenProductionPlan plan = admission.preparedPlan().orElseThrow();
			GoalObservationState observation = admission.preparedObservation().orElseThrow();
			publication.accept(plan, observation);
			Optional<UUID> installed = runtime.ensurePanelGoal(admission.goalId(),
				admission.clusterId(), admission.epochId(), plan.patternId(), plan.deliveryRoute(),
				admission.multiplier(), admission.mode(), admission.priority());
			if (installed.isEmpty()) {
				persistPendingProductionAdmission(admission.withDiagnostic("WAIT_GOAL:"
					+ runtime.lastPanelAdmissionDiagnostic(admission.clusterId())));
				return;
			}
			if (admission.phase() == ProductionAdmissionPhase.PREPARED) {
				if (admission.searchId().isPresent() && !runtime.adoptProductionSearchWinner(
					admission.searchId().orElseThrow(), admission.goalId())) {
					persistPendingProductionAdmission(admission.withDiagnostic(
						"WAIT_SEARCH_LEASE_ADOPTION"));
					return;
				}
				admission = admission.goalPublishedWithAdoptedSearchLeases();
				persistPendingProductionAdmission(admission);
			}
			if (!retireProductionSearch(admission)) {
				persistPendingProductionAdmission(admission.withDiagnostic("WAIT_SEARCH_RETIRE"));
				return;
			}
			if (admission.searchId().isPresent()) {
				admission = admission.withoutRetiredSearch();
				persistPendingProductionAdmission(admission);
			}
			ClusterEngineState installedState = runtime.snapshot(admission.clusterId()).orElse(null);
			GoalRecord goal = installedState == null ? null
				: installedState.goals().get(admission.goalId());
			if (goal != null && goal.state() == GoalState.TERMINAL) {
				removePendingProductionAdmission(admission.admissionId());
			}
		}
	}

	private Optional<PendingProductionAdmission> preparePendingProductionAdmission(
		PendingProductionAdmission admission, FactoryRuntimeService.SelectedPattern selected,
		Optional<FactoryRuntimeService.ProductionSearchSnapshot> search) {
		UUID selectedSearchId = search.map(
			FactoryRuntimeService.ProductionSearchSnapshot::searchId).orElse(null);
		if (selectedSearchId != null && ledger.searchClaimedByOther(
			selectedSearchId, admission.admissionId())) {
			persistPendingProductionAdmission(admission.searching(Optional.of(selectedSearchId),
				"WAIT_SEARCH_CLAIM"));
			return Optional.empty();
		}
		PatternRecord pattern = selected.pattern();
		if (!pattern.mainOutput().stack().equals(admission.requestedOutput())) {
			persistPendingProductionAdmission(admission.failed("SEARCH_OUTPUT_MISMATCH"));
			return Optional.empty();
		}
		StockObservationScope baselineScope = evidence.outputScope(admission.logisticsId(),
			admission.deliveryAddress(), admission.requestedOutput()).orElse(null);
		if (baselineScope == null) {
			persistPendingProductionAdmission(admission.withDiagnostic("WAIT_OUTPUT_SCOPE"));
			return Optional.empty();
		}
		StockObservationService.Observation baseline = evidence.observe(baselineScope);
		if (baseline.status() == StockObservationService.Status.WAIT_STOCK_OBSERVATION) {
			persistPendingProductionAdmission(admission.withDiagnostic("WAIT_STOCK_OBSERVATION"));
			return Optional.empty();
		}
		long requestedCount;
		long multiplier;
		long absoluteTarget;
		try {
			multiplier = admission.origin().multiplier(admission.multiplier(), pattern.mainOutput().count());
			requestedCount = Math.multiplyExact(pattern.mainOutput().count(), multiplier);
			absoluteTarget = Math.addExact(baseline.actualCount(), requestedCount);
		} catch (ArithmeticException overflow) {
			persistPendingProductionAdmission(admission.failed("COUNT_OVERFLOW"));
			return Optional.empty();
		}
		StockObservationScope scope = withAbsoluteTarget(baselineScope, absoluteTarget);
		Map<StackKey, Long> available;
		try { available = evidence.availableManifest(pattern, admission.logisticsId()); }
		catch (RuntimeException invalid) {
			persistPendingProductionAdmission(admission.failed(panelPlanFailureDiagnostic(invalid)));
			return Optional.empty();
		}
		FrozenProductionPlan plan;
		try {
			plan = ProductionPlanFreezer.freeze(admission.goalId(), admission.clusterId(),
				selected.provider(), pattern, new CreateRouteKey(admission.logisticsId(),
					admission.deliveryAddress()), multiplier, admission.mode(),
				baseline.actualCount(), scope, available).orElse(null);
		} catch (RuntimeException invalid) {
			persistPendingProductionAdmission(admission.failed(panelPlanFailureDiagnostic(invalid)));
			return Optional.empty();
		}
		if (plan == null) {
			String diagnostic = exactManifestAmbiguous(pattern, available)
				? "AMBIGUOUS_INPUT" : "WAIT_INPUT_MANIFEST";
			if (diagnostic.equals("AMBIGUOUS_INPUT"))
				persistPendingProductionAdmission(admission.failed(diagnostic));
			else persistPendingProductionAdmission(admission.withDiagnostic(diagnostic));
			return Optional.empty();
		}
		GoalObservationState observation = new GoalObservationState(admission.goalId(),
			admission.clusterId(), scope, baseline.actualCount(), requestedCount, 0, 0,
			Optional.empty());
		PendingProductionAdmission prepared = admission.prepared(selected.provider(),
			plan, observation,
			search.map(FactoryRuntimeService.ProductionSearchSnapshot::searchId));
		ProductionAdmissionLedger.State priorAdmissions = ledger.snapshot();
		if (!ledger.update(prepared))
			throw new IllegalStateException("Prepared admission lost its live ledger slot");
		try { persistence.savePrepared(ledger.snapshot(), plan, observation); }
		catch (RuntimeException failure) {
			ledger.install(priorAdmissions);
			throw failure;
		}
		publication.accept(plan, observation);
		return Optional.of(prepared);
	}

	private boolean exactManifestAmbiguous(PatternRecord pattern,
		Map<StackKey, Long> available) {
		ArrayList<com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient>
			ingredients = new ArrayList<>(pattern.inputs());
		if (pattern.body() instanceof SequencedPatternBody sequenced)
			sequenced.stages().forEach(stage -> ingredients.addAll(stage.inputs()));
		return ingredients.stream().anyMatch(ingredient -> available.keySet().stream()
			.filter(key -> ingredient.matches(key.stack())).limit(2).count() > 1);
	}

	private void persistPendingProductionAdmission(PendingProductionAdmission admission) {
		PendingProductionAdmission current = ledger
			.get(admission.admissionId()).orElse(null);
		if (admission.phase() == ProductionAdmissionPhase.FAILED && current != null) {
			String failureDiagnostic = admission.diagnostic();
			if (current.phase() != ProductionAdmissionPhase.CLEANUP_PENDING) {
				PendingProductionAdmission cleanup = ledger
					.cleanupFenceForFailure(current, failureDiagnostic);
				ProductionAdmissionLedger.State beforeCleanupFence =
					ledger.snapshot();
				if (!ledger.update(cleanup))
					throw new IllegalStateException(
						"Production admission ledger rejected cleanup fence");
				try { persistence.saveLedger(ledger.snapshot()); }
				catch (RuntimeException failure) {
					ledger.install(beforeCleanupFence);
					throw failure;
				}
				current = cleanup;
			}
			boolean searchCleaned = retireProductionSearch(current);
			boolean goalCleaned = runtime.releaseProductionGoalProcesses(current.goalId());
			if (!searchCleaned || !goalCleaned) {
				diagnostics.accept(current.clusterId(), current.diagnostic());
				return;
			}
			admission = current.failed(failureDiagnostic);
		}
		if (admission.equals(current)) return;
		ProductionAdmissionLedger.State prior = ledger.snapshot();
		boolean changed;
		if (admission.phase() == ProductionAdmissionPhase.FAILED)
			changed = ledger.fail(admission.admissionId(),
				admission.diagnostic());
		else if (current == null) changed = ledger.admit(admission);
		else changed = ledger.update(admission);
		if (!changed) throw new IllegalStateException("Production admission ledger rejected update");
		try { persistence.saveLedger(ledger.snapshot()); }
		catch (RuntimeException failure) {
			ledger.install(prior);
			throw failure;
		}
		diagnostics.accept(admission.clusterId(), admission.diagnostic());
	}

	private void removePendingProductionAdmission(UUID admissionId) {
		PendingProductionAdmission admission = ledger.get(admissionId)
			.orElse(null);
		if (admission != null) {
			boolean searchCleaned = retireProductionSearch(admission);
			boolean goalCleaned = runtime.releaseProductionGoalProcesses(admission.goalId());
			if (!searchCleaned || !goalCleaned) return;
		}
		ProductionAdmissionLedger.State prior = ledger.snapshot();
		if (!ledger.remove(admissionId)) return;
		try { persistence.saveLedger(ledger.snapshot()); }
		catch (RuntimeException failure) {
			ledger.install(prior);
			throw failure;
		}
	}

	private boolean retireProductionSearch(PendingProductionAdmission admission) {
		UUID searchId = admission.searchId().orElse(null);
		if (searchId == null) return true;
		boolean retired = runtime.retireProductionSearch(searchId);
		if (!retired) {
			FactoryRuntimeService.ProductionSearchSnapshot current = runtime
				.productionSearchSnapshot(admission.clusterId(), admission.logisticsId(),
					admission.requestedOutput()).orElse(null);
			if (current != null && current.searchId().equals(searchId)) return false;
		}
		persistence.deleteSearch(searchId);
		return true;
	}
	public static String panelPlanFailureDiagnostic(RuntimeException failure) {
		RuntimeException exact = Objects.requireNonNull(failure, "failure");
		String message = Objects.toString(exact.getMessage(), "")
			.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
		String diagnostic = "PLAN:" + exact.getClass().getSimpleName()
			+ (message.isEmpty() ? "" : ":" + message);
		return diagnostic.length() <= ProductionEffect.MAX_TEXT_LENGTH ? diagnostic
			: diagnostic.substring(0, ProductionEffect.MAX_TEXT_LENGTH);
	}
	public static StockObservationScope withAbsoluteTarget(StockObservationScope scope,
		long absoluteTarget) {
		if (absoluteTarget < 0) throw new IllegalArgumentException("negative absolute target");
		return switch (Objects.requireNonNull(scope, "scope")) {
			case StockObservationScope.NetworkSummary network ->
				new StockObservationScope.NetworkSummary(network.logisticsNetworkId(),
					network.item(), absoluteTarget);
			case StockObservationScope.AttachedInventory attached ->
				new StockObservationScope.AttachedInventory(attached.packager(),
					attached.inventory(), attached.item(), absoluteTarget);
		};
	}
}
