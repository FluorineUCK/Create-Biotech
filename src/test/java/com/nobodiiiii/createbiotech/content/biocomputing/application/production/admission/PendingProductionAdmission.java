package com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;

public record PendingProductionAdmission(UUID admissionId, UUID goalId, UUID clusterId,
	UUID epochId, long snapshotGeneration, long viewRevision, ProductionAdmissionOrigin origin,
	UUID logisticsId, StackKey requestedOutput, String deliveryAddress, long multiplier,
	InputMode mode, int priority, ProductionAdmissionPhase phase, Optional<UUID> searchId,
	boolean searchLeasesAdopted,
	Optional<SelectedProviderDescriptor> selectedProvider,
	Optional<FrozenProductionPlan> preparedPlan,
	Optional<GoalObservationState> preparedObservation, String diagnostic) {
	public PendingProductionAdmission {
		admissionId = Objects.requireNonNull(admissionId, "admissionId");
		goalId = Objects.requireNonNull(goalId, "goalId");
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		epochId = Objects.requireNonNull(epochId, "epochId");
		origin = Objects.requireNonNull(origin, "origin");
		logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
		requestedOutput = Objects.requireNonNull(requestedOutput, "requestedOutput");
		deliveryAddress = RecipeAddressRules.requireNormalized(deliveryAddress);
		mode = Objects.requireNonNull(mode, "mode");
		phase = Objects.requireNonNull(phase, "phase");
		searchId = Objects.requireNonNull(searchId, "searchId");
		selectedProvider = Objects.requireNonNull(selectedProvider, "selectedProvider");
		preparedPlan = Objects.requireNonNull(preparedPlan, "preparedPlan");
		preparedObservation = Objects.requireNonNull(preparedObservation,
			"preparedObservation");
		diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
		if (snapshotGeneration < 0 || viewRevision < 0 || multiplier <= 0
			|| diagnostic.isBlank() || diagnostic.length() > ProductionEffect.MAX_TEXT_LENGTH)
			throw new IllegalArgumentException("Invalid pending production admission");
		boolean prepared = phase == ProductionAdmissionPhase.PREPARED
			|| phase == ProductionAdmissionPhase.GOAL_PUBLISHED;
		if (prepared != (selectedProvider.isPresent()
			&& preparedPlan.isPresent() && preparedObservation.isPresent()))
			throw new IllegalArgumentException("Prepared admission evidence is incomplete");
		if (searchLeasesAdopted != (phase == ProductionAdmissionPhase.GOAL_PUBLISHED))
			throw new IllegalArgumentException("Search-lease adoption fence differs from phase");
		FrozenProductionPlan exactPlan = preparedPlan.orElse(null);
		if (exactPlan != null && (!exactPlan.goalId().equals(goalId)
			|| !exactPlan.clusterId().equals(clusterId)
			|| !exactPlan.output().equals(requestedOutput)
			|| !exactPlan.selectedProvider().equals(selectedProvider.orElseThrow())))
			throw new IllegalArgumentException("Prepared plan does not belong to admission");
		GoalObservationState exactObservation = preparedObservation.orElse(null);
		if (exactObservation != null && (!exactObservation.goalId().equals(goalId)
			|| !exactObservation.clusterId().equals(clusterId)
			|| !exactObservation.item().equals(requestedOutput)))
			throw new IllegalArgumentException("Prepared observation does not belong to admission");
	}

	/** Source-compatible Panel constructor; durable v5 data is migrated explicitly by the codec. */
	public PendingProductionAdmission(UUID admissionId, UUID goalId, UUID clusterId,
		UUID epochId, long snapshotGeneration, long viewRevision, UUID panelId,
		SpaceAddress panelAddress, UUID menuSessionId, Optional<SpaceAddress> actorAddress,
		UUID logisticsId, StackKey requestedOutput, String deliveryAddress, long multiplier,
		InputMode mode, int priority, ProductionAdmissionPhase phase, Optional<UUID> searchId,
		boolean searchLeasesAdopted, Optional<SelectedProviderDescriptor> selectedProvider,
		Optional<FrozenProductionPlan> preparedPlan, Optional<GoalObservationState> preparedObservation,
		String diagnostic) {
		this(admissionId, goalId, clusterId, epochId, snapshotGeneration, viewRevision,
			ProductionAdmissionOrigin.panel(panelId, panelAddress, menuSessionId, actorAddress),
			logisticsId, requestedOutput, deliveryAddress, multiplier, mode, priority, phase,
			searchId, searchLeasesAdopted, selectedProvider, preparedPlan, preparedObservation, diagnostic);
	}

	public PendingProductionAdmission withDiagnostic(String nextDiagnostic) {
		return new PendingProductionAdmission(admissionId, goalId, clusterId, epochId,
			snapshotGeneration, viewRevision, origin, logisticsId, requestedOutput, deliveryAddress, multiplier, mode,
			priority, phase, searchId, searchLeasesAdopted, selectedProvider, preparedPlan,
			preparedObservation, nextDiagnostic);
	}

	public PendingProductionAdmission searching(Optional<UUID> activeSearch,
		String nextDiagnostic) {
		if (phase != ProductionAdmissionPhase.SEARCHING)
			throw new IllegalStateException("Only a searching admission has a search cursor");
		return new PendingProductionAdmission(admissionId, goalId, clusterId, epochId,
			snapshotGeneration, viewRevision, origin, logisticsId, requestedOutput, deliveryAddress, multiplier, mode,
			priority, phase, activeSearch, false, selectedProvider,
			preparedPlan, preparedObservation, nextDiagnostic);
	}

	public PendingProductionAdmission prepared(SelectedProviderDescriptor provider,
		FrozenProductionPlan plan, GoalObservationState observation, Optional<UUID> selectedSearch) {
		return new PendingProductionAdmission(admissionId, goalId, clusterId, epochId,
			snapshotGeneration, viewRevision, origin, logisticsId, requestedOutput, deliveryAddress,
			plan.requestedOutputCount() / plan.outputPerOperation(), mode,
			priority, ProductionAdmissionPhase.PREPARED, selectedSearch, false,
			Optional.of(Objects.requireNonNull(provider, "provider")), Optional.of(plan),
			Optional.of(observation), "PREPARED");
	}

	public PendingProductionAdmission goalPublishedWithAdoptedSearchLeases() {
		return new PendingProductionAdmission(admissionId, goalId, clusterId, epochId,
			snapshotGeneration, viewRevision, origin, logisticsId, requestedOutput, deliveryAddress, multiplier, mode,
			priority, ProductionAdmissionPhase.GOAL_PUBLISHED, searchId, true,
			selectedProvider, preparedPlan, preparedObservation,
			"GOAL_PUBLISHED");
	}

	public PendingProductionAdmission withoutRetiredSearch() {
		if (phase != ProductionAdmissionPhase.GOAL_PUBLISHED || !searchLeasesAdopted)
			throw new IllegalStateException("Only an adopted Goal may retire its search cursor");
		return new PendingProductionAdmission(admissionId, goalId, clusterId, epochId,
			snapshotGeneration, viewRevision, origin, logisticsId, requestedOutput, deliveryAddress, multiplier, mode,
			priority, phase, Optional.empty(), true, selectedProvider, preparedPlan,
			preparedObservation, "GOAL_PUBLISHED");
	}

	public PendingProductionAdmission cleanupPending(String reason) {
		return cleanupPending(reason, true);
	}

	public PendingProductionAdmission cleanupPending(String reason, boolean retainSearch) {
		return new PendingProductionAdmission(admissionId, goalId, clusterId, epochId,
			snapshotGeneration, viewRevision, origin, logisticsId, requestedOutput, deliveryAddress, multiplier, mode,
			priority, ProductionAdmissionPhase.CLEANUP_PENDING,
			retainSearch ? searchId : Optional.empty(), false,
			Optional.empty(), Optional.empty(), Optional.empty(), reason);
	}

	public PendingProductionAdmission failed(String reason) {
		return new PendingProductionAdmission(admissionId, goalId, clusterId, epochId,
			snapshotGeneration, viewRevision, origin, logisticsId, requestedOutput, deliveryAddress, multiplier, mode,
			priority, ProductionAdmissionPhase.FAILED, searchId, false, Optional.empty(),
			Optional.empty(), Optional.empty(), reason);
	}

	public Optional<UUID> selectedLibraryId() {
		return selectedProvider.map(SelectedProviderDescriptor::libraryId);
	}

	public Optional<UUID> selectedPatternId() {
		return selectedProvider.map(SelectedProviderDescriptor::patternId);
	}
}
