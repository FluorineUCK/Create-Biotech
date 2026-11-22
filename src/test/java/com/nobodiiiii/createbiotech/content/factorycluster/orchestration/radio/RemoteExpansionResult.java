package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ChildExecutionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.world.item.component.WritableBookContent;

/** Receiver-produced terminal Pattern expansion installed by the owning source. */
public record RemoteExpansionResult(RemoteExpansionRevision revisionState,
	List<ExpandedStage> stages, Map<StackKey, Long> manifest,
	RemoteExpansionStackSnapshot exactStackSnapshot) {
	public RemoteExpansionResult {
		revisionState = Objects.requireNonNull(revisionState, "revisionState");
		stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
		manifest = RemoteExpansionRequest.positiveManifest(manifest);
		exactStackSnapshot = Objects.requireNonNull(exactStackSnapshot, "exactStackSnapshot");
		if (stages.isEmpty() || stages.size() > WritableBookContent.PAGE_EDIT_LENGTH)
			throw new IllegalArgumentException("invalid expansion result stage count");
		LinkedHashMap<StackKey, Long> combined = new LinkedHashMap<>();
		int entries = manifest.size();
		for (int index = 0; index < stages.size(); index++) {
			ExpandedStage stage = stages.get(index);
			if (stage.index() != index) throw new IllegalArgumentException("result stages are not contiguous");
			try {
				entries = Math.addExact(entries, stage.manifest().size());
				stage.manifest().forEach((stack, count) ->
					combined.merge(stack, count, Math::addExact));
			} catch (ArithmeticException overflow) {
				throw new IllegalArgumentException("remote expansion result arithmetic overflow", overflow);
			}
		}
		ArrayList<StackKey> exactStacks = new ArrayList<>(manifest.keySet());
		stages.forEach(stage -> exactStacks.addAll(stage.manifest().keySet()));
		exactStackSnapshot.requireExactly(exactStacks);
		if (entries > WritableBookContent.PAGE_EDIT_LENGTH
			|| !Map.copyOf(combined).equals(manifest)
			|| !RemoteExpansionDigest.expansion(stages, manifest, exactStackSnapshot)
				.equals(revisionState.expandedManifestFingerprint()))
			throw new IllegalArgumentException("result does not match its expansion revision");
		RemoteExpansionDigest.requireExpansionFits(stages, manifest, exactStackSnapshot);
	}

	public static RemoteExpansionResult compute(RemoteExpansionRequest request,
		RemoteExpansionRevision acknowledged) {
		request = Objects.requireNonNull(request, "request");
		acknowledged = Objects.requireNonNull(acknowledged, "acknowledged");
		if (!acknowledged.matches(request))
			throw new IllegalArgumentException("acknowledged revision does not match expansion request");
		return new RemoteExpansionResult(acknowledged, request.expandedStages(),
			request.expandedManifest(), request.exactStackSnapshot());
	}

	public boolean matches(RemoteExpansionRequest request, RemoteExpansionRevision revision) {
		return revisionState.equals(revision) && revisionState.matches(request)
			&& equals(compute(request, revision));
	}

	public ChildExecutionRevision childRevision() { return revisionState.childRevision(); }
	public String fingerprint() {
		return revisionState.fingerprint() + "|" + revisionState.expandedManifestFingerprint();
	}

	public record ExpandedStage(int index, CreateRouteKey route, Map<StackKey, Long> manifest)
		implements RemoteExpansionDigest.RemoteExpansionStage {
		public ExpandedStage {
			if (index < 0) throw new IllegalArgumentException("negative expanded stage");
			route = Objects.requireNonNull(route, "route");
			manifest = RemoteExpansionRequest.positiveManifest(manifest);
		}
	}
}
