package com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;

/** Shared immutable capture for local and radio production expansion. */
public final class PureExpansionCapture {
	private PureExpansionCapture() {}
	public static RemoteExpansionRequest capture(
		net.minecraft.core.HolderLookup.Provider registries, FrozenProductionPlan plan,
		ProductionRun run, ProcessRecord sourceProcess, long parentStateGeneration) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(run, "run");
		Objects.requireNonNull(sourceProcess, "sourceProcess");
		if (!plan.goalId().equals(run.goalId()) || !plan.patternId().equals(run.patternId())
			|| !sourceProcess.goalId().equals(run.goalId()) || parentStateGeneration < 0)
			throw new IllegalArgumentException("pure expansion source lineage mismatch");
		LinkedHashMap<StackKey, Long> dependencies = new LinkedHashMap<>();
		List<RemoteExpansionRequest.StageInput> stageInputs = plan.stages().stream()
			.map(stage -> {
				stage.manifest().forEach((stack, count) ->
					dependencies.merge(stack, count, Math::addExact));
				return new RemoteExpansionRequest.StageInput(stage.index(), stage.route(),
					stage.manifest());
			}).toList();
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			Objects.requireNonNull(registries, "registries"), run.goalId(),
			sourceProcess.processId(), sourceProcess.generation(), parentStateGeneration,
			run.patternId(), 2, run.runId(), run.fence(), run.inputMode(),
			run.runMultiplier(), stageInputs, dependencies);
		if (!request.expandedManifest().equals(run.manifest()))
			throw new IllegalArgumentException("Run manifest is not the Pattern expansion input");
		return request;
	}
}
