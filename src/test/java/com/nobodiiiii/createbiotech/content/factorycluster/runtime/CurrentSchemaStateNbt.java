package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionPhase;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionLedger;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionOrigin;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.PendingProductionAdmission;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ChildExecutionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionStackSnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternEditData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageError;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternErrorReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.UnsupportedPatternEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PageInspection;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibrarySearchWave;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibrarySearchCoordinator;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.OrdinaryProviderRegistry;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.DefaultRoutePolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** Strict reflection codec for bounded records in the sole current factory schema. */
public final class CurrentSchemaStateNbt {
	/** Version 6 records explicit Panel / Stock Keeper command provenance. */
	private static final int VERSION = 6;
	private static final int LEGACY_VERSION = 1;
	private static final int FIRST_COMPATIBLE_VERSION = 2;
	private static final int PRIOR_VERSION = 4;
	private static final int MAX_DEPTH = 64;
	private static final int MAX_TEXT = 1 << 20;
	private static final Set<String> ROOT_KEYS = Set.of("Version", "Type", "Value");
	/** Historical schema identities, not permission to reflect over the new application package. */
	private static final String LEGACY_SOURCE_LOCATION = "com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress";
	private static final Map<Class<?>, String> RELOCATED_APPLICATION_TYPES = Map.ofEntries(
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress.class, LEGACY_SOURCE_LOCATION),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory.State.class, "com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectory$State"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry.class, "com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectoryEntry"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.StartResult.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$StartResult"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.OutputEvent.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$OutputEvent"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.DispatchInstruction.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$DispatchInstruction"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.DispatchDecision.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$DispatchDecision"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.AgentReceiptAck.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$AgentReceiptAck"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.SealOutcome.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$SealOutcome"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.ReadinessEvidence.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$ReadinessEvidence"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.FailureState.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$FailureState"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.ProcessLease.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$ProcessLease"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.Snapshot.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$Snapshot"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.PendingOutput.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$PendingOutput"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.ReceiverDestination.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$ReceiverDestination"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.LateWarehouseMaterial.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$LateWarehouseMaterial"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.Milestone.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$Milestone"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.OutputObservation.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$OutputObservation"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.FailureReason.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$FailureReason"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.LeasePurpose.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputContract$LeasePurpose"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.OutputDispatchLedger"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger.DispatchedEvent.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.OutputDispatchLedger$DispatchedEvent"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger.SealFact.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.OutputDispatchLedger$SealFact"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger.CompletionBoundary.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.OutputDispatchLedger$CompletionBoundary"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger.BoundaryReason.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.OutputDispatchLedger$BoundaryReason"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputPolicy"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy.RequiredOutput.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputPolicy$RequiredOutput"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy.OutputReceiver.Bare.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputPolicy$OutputReceiver$Bare"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy.OutputReceiver.Agent.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputPolicy$OutputReceiver$Agent"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy.BareReceiptProof.Network.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputPolicy$BareReceiptProof$Network"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy.BareReceiptProof.Attached.class, "com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentOutputPolicy$BareReceiptProof$Attached"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.SafeCreateRetryProof.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$SafeCreateRetry"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.TargetFence.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$TargetFence"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.PanelRow.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$PanelRow"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.LibraryQuery.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$LibraryQuery"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.RadioSend.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$RadioSend"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.StockSampleRequest.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$StockSampleRequest"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.AgentTicketOffer.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$AgentTicketOffer"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.CreateDispatch.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$CreateDispatch"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.OutputPackageRequest.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$OutputPackageRequest"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.PanelSnapshotPublication.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$PanelSnapshotPublication"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldIntent$OrderedIngredient"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ProductionRun"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun.RunSequence.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ProductionRun$RunSequence"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FrozenProductionPlan"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan.FrozenStage.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FrozenProductionPlan$FrozenStage"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan.RemoteExpansionInstallation.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FrozenProductionPlan$RemoteExpansionInstallation"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationPhase.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RemoteComputationPhase"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationBeginStatus.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RemoteComputationBeginStatus"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationBeginResult.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RemoteComputationBeginResult"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationJobPhase.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RemoteComputationJobPhase"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationJob.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RemoteComputationJob"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteOfferAcceptance.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RemoteOfferAcceptance"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationTerminalOutcome.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RemoteComputationTerminalOutcome"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RetiredRemoteComputation.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RetiredRemoteComputation"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationLifecycleState.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RemoteComputationLifecycleState"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationExchange.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$RemoteComputationExchange"),

		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Job.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$LocalComputationJob"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Phase.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$LocalComputationPhase"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Lifecycle.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$LocalComputationLifecycleState"),
		Map.entry(GoalObservationState.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$GoalObservationState"),
		Map.entry(PendingProductionAdmission.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$PendingProductionAdmission"),
		Map.entry(ProductionAdmissionPhase.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$ProductionAdmissionPhase"),
		Map.entry(ProductionAdmissionLedger.State.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.ProductionAdmissionLedger$State"),
		Map.entry(ProductionAdmissionLedger.Failure.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.ProductionAdmissionLedger$Failure"),
		Map.entry(ProductionAdmissionOrigin.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.ProductionAdmissionOrigin"),
		Map.entry(ProductionAdmissionOrigin.Kind.class, "com.nobodiiiii.createbiotech.content.factorycluster.runtime.ProductionAdmissionOrigin$Kind"),
		Map.entry(com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial.class,
			"com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerShellMaterial"),
		Map.entry(ProcessRecord.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ProcessRecord"),
		Map.entry(ProcessKind.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ProcessKind"),
		Map.entry(ProcessState.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ProcessState"),
		Map.entry(WaitReason.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.WaitReason"),
		Map.entry(ChildExecutionRevision.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ChildExecutionRevision"),
		Map.entry(InputMode.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.InputMode"),
		Map.entry(ProcessRecord.ChildRevisionStatus.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ProcessRecord$ChildRevisionStatus"),
		Map.entry(ProcessRecord.RevisionAcceptance.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ProcessRecord$RevisionAcceptance"),
		Map.entry(ChildExecutionRevision.Acknowledgement.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.ChildExecutionRevision$Acknowledgement"),
		Map.entry(CoordinationAssociation.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.CoordinationAssociation"),
		Map.entry(ProductionResourceProfile.class, "com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerProfile"),
		Map.entry(ProductionProfession.class, "com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerProfession"),
		Map.entry(ProductionCapability.class, "com.nobodiiiii.createbiotech.content.factorycluster.computer.ProcessCapability"),
		Map.entry(LibrarySearchWave.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibrarySearchWave"),
		Map.entry(LibrarySearchCoordinator.Status.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibrarySearchCoordinator$Status"),
		Map.entry(RouteCandidate.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.RouteCandidate"),
		Map.entry(RouteCandidate.ProviderKind.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.RouteCandidate$ProviderKind"),
		Map.entry(RouteCandidate.Availability.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.RouteCandidate$Availability"),
		Map.entry(RouteCandidate.Blocker.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.RouteCandidate$Blocker"),
		Map.entry(DefaultRoutePolicy.RouteIdentity.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.DefaultRoutePolicy$RouteIdentity"),
		Map.entry(DefaultRoutePolicy.CursorState.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.DefaultRoutePolicy$CursorState"),
		Map.entry(DefaultRoutePolicy.Decision.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.DefaultRoutePolicy$Decision"),
		Map.entry(RouteSelectionScope.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.RouteSelectionScope"),
		Map.entry(RouteSelectionRequest.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.RouteSelectionRequest"),
		Map.entry(SelectedProviderDescriptor.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.SelectedProviderDescriptor"),
		Map.entry(ProductionRouteTable.ProviderSource.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.ProductionRouteTable$ProviderSource"),
		Map.entry(OrdinaryProviderRegistry.Registration.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.OrdinaryProviderRegistry$Registration"),
		Map.entry(CommunicationFrequency.class, "com.nobodiiiii.createbiotech.content.factorycluster.communication.CommunicationFrequency"),
		Map.entry(LibraryRegistration.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibraryRegistration"),
		Map.entry(LibraryRegistration.LoadState.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibraryRegistration$LoadState"),
		Map.entry(LibraryRegistration.Reachability.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibraryRegistration$Reachability"),
		Map.entry(PatternQuery.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternQuery"),
		Map.entry(PatternReply.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternReply"),
		Map.entry(PatternReply.Status.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternReply$Status"),
		Map.entry(ProductionRecipeCatalog.class, "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibraryCatalogSnapshot"),
		Map.entry(StackKey.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.StackKey"),
		Map.entry(PatternIngredient.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternIngredient"),
		Map.entry(PatternOutput.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternOutput"),
		Map.entry(PatternEditData.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternEditData"),
		Map.entry(PatternKind.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternKind"),
		Map.entry(PatternStage.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternStage"),
		Map.entry(SequencedPatternBody.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.SequencedPatternBody"),
		Map.entry(StandardPatternBody.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.StandardPatternBody"),
		Map.entry(PatternRecord.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternRecord"),
		Map.entry(PatternPageKey.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternPageKey"),
		Map.entry(PatternPageError.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternPageError"),
		Map.entry(PatternErrorReason.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternErrorReason"),
		Map.entry(UnsupportedPatternEnvelope.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.UnsupportedPatternEnvelope"),
		Map.entry(ParseResult.Blank.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.ParseResult$Blank"),
		Map.entry(ParseResult.Valid.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.ParseResult$Valid"),
		Map.entry(ParseResult.Invalid.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.ParseResult$Invalid"),
		Map.entry(ParseResult.Unsupported.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.ParseResult$Unsupported"),
		Map.entry(PageInspection.class, "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PageInspection"));

	private CurrentSchemaStateNbt() {}

	public static CompoundTag save(Object value, HolderLookup.Provider registries) {
		Objects.requireNonNull(value, "value");
		CompoundTag root = new CompoundTag();
		root.putInt("Version", VERSION);
		root.putString("Type", storedTypeName(value.getClass()));
		root.put("Value", encode(value, registries, 0));
		return root;
	}

	public static <T> Optional<T> load(CompoundTag root, HolderLookup.Provider registries,
		Class<T> expected) {
		if (root == null || registries == null || expected == null) return Optional.empty();
		try {
			if (!root.getAllKeys().equals(ROOT_KEYS) || !root.contains("Version", Tag.TAG_INT)
				|| !supportedVersion(root.getInt("Version")) || !root.contains("Type", Tag.TAG_STRING)
				|| !root.getString("Type").equals(storedTypeName(expected))
				|| !(root.get("Value") instanceof CompoundTag value)) return Optional.empty();
			CompoundTag decodedValue = value.copy();
			if (root.getInt("Version") == LEGACY_VERSION) migrateLegacyV1(decodedValue, 0);
			if (root.getInt("Version") < 6) migrateAdmissionOrigins(decodedValue, registries, 0);
			return Optional.of(expected.cast(decode(decodedValue, expected, registries, 0)));
		} catch (ReflectiveOperationException | RuntimeException invalid) {
			return Optional.empty();
		}
	}

	private static CompoundTag encode(Object value, HolderLookup.Provider registries, int depth) {
		if (depth > MAX_DEPTH) throw new IllegalArgumentException("State nesting exceeds bound");
		CompoundTag tag = new CompoundTag();
		if (value == null) { tag.putString("Kind", "null"); return tag; }
		if (value instanceof Boolean item) return scalar("boolean", "Boolean", item);
		if (value instanceof Byte item) return scalar("byte", "Byte", item);
		if (value instanceof Short item) return scalar("short", "Short", item);
		if (value instanceof Integer item) return scalar("int", "Int", item);
		if (value instanceof Long item) return scalar("long", "Long", item);
		if (value instanceof Float item) return scalar("float", "Float", item);
		if (value instanceof Double item) return scalar("double", "Double", item);
		if (value instanceof String item) {
			boundedText(item); tag.putString("Kind", "string"); tag.putString("Value", item); return tag;
		}
		if (value instanceof CommunicationFrequency item) {
			tag.putString("Kind", "communication_frequency");
			tag.putString("Value", item.value()); return tag;
		}
		if (value instanceof byte[] item) {
			boundedBinary(item); tag.putString("Kind", "byte_array");
			tag.putByteArray("Value", item.clone()); return tag;
		}
		if (value instanceof UUID item) {
			tag.putString("Kind", "uuid"); tag.putUUID("Value", item); return tag;
		}
		if (value instanceof net.minecraft.resources.ResourceLocation item) {
			tag.putString("Kind", "resource_location"); tag.putString("Value", item.toString()); return tag;
		}
		if (value instanceof net.minecraft.resources.ResourceKey<?> item) {
			tag.putString("Kind", "resource_key"); tag.putString("Registry", item.registry().toString());
			tag.putString("Location", item.location().toString()); return tag;
		}
		if (value instanceof net.minecraft.core.BlockPos item) {
			tag.putString("Kind", "block_pos"); tag.putLong("Value", item.asLong()); return tag;
		}
		if (value instanceof Enum<?> item) {
			tag.putString("Kind", "enum"); tag.putString("Type", storedTypeName(item.getDeclaringClass()));
			tag.putString("Value", item.name()); return tag;
		}
		if (value instanceof Optional<?> item) {
			tag.putString("Kind", "optional"); tag.putBoolean("Present", item.isPresent());
			if (item.isPresent()) tag.put("Value", encode(item.orElseThrow(), registries, depth + 1));
			return tag;
		}
		if (value instanceof OptionalLong item) {
			tag.putString("Kind", "optional_long"); tag.putBoolean("Present", item.isPresent());
			if (item.isPresent()) tag.putLong("Value", item.orElseThrow());
			return tag;
		}
		if (value instanceof OptionalInt item) {
			tag.putString("Kind", "optional_int"); tag.putBoolean("Present", item.isPresent());
			if (item.isPresent()) tag.putInt("Value", item.orElseThrow());
			return tag;
		}
		if (value instanceof StackKey item) {
			tag.putString("Kind", "stack_key"); tag.put("Value", item.stack().saveOptional(registries));
			return tag;
		}
		if (value instanceof ItemStack item) {
			tag.putString("Kind", "item_stack"); tag.put("Value", item.saveOptional(registries));
			return tag;
		}
		if (value instanceof DataComponentPredicate item) {
			tag.putString("Kind", "component_predicate");
			tag.put("Value", encodeComponentPredicate(item, registries));
			return tag;
		}
		if (value instanceof NativePackageObservation item) {
			tag.putString("Kind", "native_observation");
			tag.put("Route", encode(item.route(), registries, depth + 1));
			tag.putBoolean("HasOrder", item.hasOrderData()); tag.putInt("Order", item.orderId());
			tag.putInt("Link", item.linkIndex()); tag.putInt("Fragment", item.fragmentIndex());
			tag.putBoolean("FinalLink", item.isFinalLink()); tag.putBoolean("Final", item.isFinal());
			tag.put("Context", encode(item.orderContext(), registries, depth + 1));
			tag.put("Stacks", encode(item.acceptedStacks(), registries, depth + 1)); return tag;
		}
		if (value instanceof RemoteExpansionStackSnapshot snapshot)
			snapshot.verify(registries);
		if (value instanceof Map<?, ?> item) {
			boundedSize(item.size()); tag.putString("Kind", "map"); ListTag entries = new ListTag();
			for (Map.Entry<?, ?> entry : item.entrySet()) {
				CompoundTag child = new CompoundTag();
				child.put("Key", encode(entry.getKey(), registries, depth + 1));
				child.put("Value", encode(entry.getValue(), registries, depth + 1)); entries.add(child);
			}
			entries.sort(Comparator.comparing((Tag entry) ->
				canonical(((CompoundTag) entry).get("Key"))).thenComparing(entry ->
				canonical(((CompoundTag) entry).get("Value"))));
			tag.put("Entries", entries); return tag;
		}
		if (value instanceof Set<?> item)
			return collection("set", item, registries, depth);
		if (value instanceof List<?> item)
			return collection("list", item, registries, depth);
		Class<?> type = value.getClass();
		if (isCapability(type)) {
			tag.putString("Kind", "capability"); tag.putString("Type", type.getName());
			for (Field field : instanceFields(type)) {
				try { field.setAccessible(true); tag.put(field.getName(), encode(field.get(value),
					registries, depth + 1)); }
				catch (ReflectiveOperationException failure) {
					throw new IllegalArgumentException("Could not read current-schema capability", failure);
				}
			}
			return tag;
		}
		if (!type.isRecord() || !allowed(type))
			throw new IllegalArgumentException("Unsupported current-schema type " + type.getName());
		tag.putString("Kind", "record"); tag.putString("Type", storedTypeName(type));
		for (RecordComponent component : type.getRecordComponents()) {
			try {
				component.getAccessor().setAccessible(true);
				tag.put(component.getName(), encode(component.getAccessor().invoke(value), registries,
					depth + 1));
			} catch (ReflectiveOperationException failure) {
				throw new IllegalArgumentException("Could not read current-schema record", failure);
			}
		}
		return tag;
	}

	private static Object decode(CompoundTag tag, Type expected, HolderLookup.Provider registries,
		int depth) throws ReflectiveOperationException {
		if (depth > MAX_DEPTH) throw new IllegalArgumentException("State nesting exceeds bound");
		String kind = string(tag, "Kind");
		validateShape(tag, kind);
		if (kind.equals("null")) return null;
		Class<?> raw = raw(expected);
		return switch (kind) {
			case "boolean" -> tag.getBoolean("Value");
			case "byte" -> tag.getByte("Value");
			case "short" -> tag.getShort("Value");
			case "int" -> tag.getInt("Value");
			case "long" -> tag.getLong("Value");
			case "float" -> tag.getFloat("Value");
			case "double" -> tag.getDouble("Value");
			case "string" -> { String value = string(tag, "Value"); boundedText(value); yield value; }
			case "communication_frequency" -> {
				if (raw != CommunicationFrequency.class)
					throw new IllegalArgumentException("Communication-frequency type mismatch");
				yield CommunicationFrequency.of(string(tag, "Value"));
			}
			case "byte_array" -> {
				if (raw != byte[].class) throw new IllegalArgumentException("Byte-array type mismatch");
				byte[] value = tag.getByteArray("Value"); boundedBinary(value); yield value;
			}
			case "uuid" -> { if (!tag.hasUUID("Value")) throw new IllegalArgumentException(); yield tag.getUUID("Value"); }
			case "resource_location" -> net.minecraft.resources.ResourceLocation.parse(string(tag, "Value"));
			case "resource_key" -> resourceKey(tag);
			case "block_pos" -> net.minecraft.core.BlockPos.of(tag.getLong("Value"));
			case "enum" -> decodeEnum(tag, raw);
			case "optional" -> decodeOptional(tag, expected, registries, depth);
			case "optional_long" -> tag.getBoolean("Present")
				? OptionalLong.of(tag.getLong("Value")) : OptionalLong.empty();
			case "optional_int" -> tag.getBoolean("Present")
				? OptionalInt.of(tag.getInt("Value")) : OptionalInt.empty();
			case "stack_key" -> new StackKey(nonEmptyStack(tag, registries));
			case "item_stack" -> ItemStack.parseOptional(registries, compound(tag, "Value"));
			case "component_predicate" -> decodeComponentPredicate(tag, raw, registries);
			case "native_observation" -> decodeNativeObservation(tag, registries, depth);
			case "list" -> decodeCollection(tag, expected, registries, depth, false);
			case "set" -> decodeCollection(tag, expected, registries, depth, true);
			case "map" -> decodeMap(tag, expected, registries, depth);
			case "record" -> decodeRecord(tag, raw, registries, depth);
			case "capability" -> decodeCapability(tag, raw, registries, depth);
			default -> throw new IllegalArgumentException("Unknown current-schema kind");
		};
	}

	private static Object decodeCapability(CompoundTag tag, Class<?> expected,
		HolderLookup.Provider registries, int depth) throws ReflectiveOperationException {
		Class<?> actual = Class.forName(string(tag, "Type"));
		if (actual != expected || !isCapability(actual))
			throw new IllegalArgumentException("Foreign capability type");
		List<Field> fields = instanceFields(actual);
		Set<String> keys = new LinkedHashSet<>(Set.of("Kind", "Type"));
		fields.forEach(field -> keys.add(field.getName()));
		if (!tag.getAllKeys().equals(keys)) throw new IllegalArgumentException("Unexpected capability keys");
		Class<?>[] types = new Class<?>[fields.size()]; Object[] values = new Object[fields.size()];
		for (int index = 0; index < fields.size(); index++) {
			Field field = fields.get(index); types[index] = field.getType();
			values[index] = decode(compound(tag, field.getName()), field.getGenericType(),
				registries, depth + 1);
		}
		Constructor<?> constructor = actual.getDeclaredConstructor(types);
		constructor.setAccessible(true); return constructor.newInstance(values);
	}

	private static boolean isCapability(Class<?> type) {
		return type.getName().equals("com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway$CommitReceipt")
			|| type.getName().equals("com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway$CommitReceipt");
	}

	private static List<Field> instanceFields(Class<?> type) {
		return java.util.Arrays.stream(type.getDeclaredFields())
			.filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
			.toList();
	}

	private static Object decodeRecord(CompoundTag tag, Class<?> expected,
		HolderLookup.Provider registries, int depth) throws ReflectiveOperationException {
		Class<?> actual = storedSchemaType(string(tag, "Type"), expected);
		if (!allowed(actual) || !expected.isAssignableFrom(actual) || !actual.isRecord())
			throw new IllegalArgumentException("Foreign record type");
		RecordComponent[] components = actual.getRecordComponents();
		Set<String> keys = new LinkedHashSet<>(Set.of("Kind", "Type"));
		for (RecordComponent component : components) keys.add(component.getName());
		if (!tag.getAllKeys().equals(keys)) throw new IllegalArgumentException("Unexpected record keys");
		Class<?>[] types = new Class<?>[components.length]; Object[] values = new Object[components.length];
		for (int index = 0; index < components.length; index++) {
			types[index] = components[index].getType();
			values[index] = decode(compound(tag, components[index].getName()),
				components[index].getGenericType(), registries, depth + 1);
		}
		Constructor<?> constructor = actual.getDeclaredConstructor(types);
		constructor.setAccessible(true);
		Object value = constructor.newInstance(values);
		if (value instanceof RemoteExpansionStackSnapshot snapshot)
			snapshot.verify(registries);
		return value;
	}

	private static boolean legacyRadioEnvelopeShape(CompoundTag tag) {
		return tag.getAllKeys().equals(Set.of("Kind", "Type", "messageId", "sourceClusterId",
			"sourceMemberId", "targetClusterId", "targetMemberId", "frequency", "hopSequence",
			"sourceGeneration", "targetGeneration", "payloadFingerprint", "payload", "subnetTrace",
			"boundaryForwardMarker"));
	}

	/** Only the exact old Panel record shape migrates; missing current fields remain corruption. */
	private static void migrateAdmissionOrigins(Tag value, HolderLookup.Provider registries, int depth) {
		if (depth > MAX_DEPTH) throw new IllegalArgumentException("State nesting exceeds bound");
		if (value instanceof CompoundTag tag) {
			if ("record".equals(tag.getString("Kind")) && storedTypeName(PendingProductionAdmission.class).equals(tag.getString("Type"))) {
				Set<String> expected = new LinkedHashSet<>(Set.of("Kind", "Type"));
				for (var component : PendingProductionAdmission.class.getRecordComponents())
					if (!component.getName().equals("origin")) expected.add(component.getName());
				expected.addAll(Set.of("panelId", "panelAddress", "menuSessionId", "actorAddress"));
				if (!tag.getAllKeys().equals(expected)) throw new IllegalArgumentException("Invalid legacy admission");
				CompoundTag origin = new CompoundTag();
				origin.putString("Kind", "record"); origin.putString("Type", storedTypeName(ProductionAdmissionOrigin.class));
				origin.put("kind", encode(ProductionAdmissionOrigin.Kind.PANEL, registries, depth + 1));
				origin.put("sourceId", compound(tag, "panelId").copy());
				origin.put("sourceAddress", compound(tag, "panelAddress").copy());
				origin.put("requestId", compound(tag, "menuSessionId").copy());
				origin.put("actorAddress", compound(tag, "actorAddress").copy());
				origin.put("requestedItems", encode(0L, registries, depth + 1));
				for (String field : List.of("panelId", "panelAddress", "menuSessionId", "actorAddress")) tag.remove(field);
				tag.put("origin", origin);
			}
			for (String key : Set.copyOf(tag.getAllKeys())) migrateAdmissionOrigins(tag.get(key), registries, depth + 1);
		} else if (value instanceof ListTag list) for (Tag child : list) migrateAdmissionOrigins(child, registries, depth + 1);
	}

	/**
	 * Explicit v1-to-v2 migration: only a null singleton marker can become an ordinary unmarked
	 * envelope. Any v1 boundary marker fails closed instead of becoming durable authorization.
	 */
	private static void migrateLegacyV1(Tag tag, int depth) {
		if (depth > MAX_DEPTH) throw new IllegalArgumentException("State nesting exceeds bound");
		if (tag instanceof CompoundTag compound) {
			if (compound.contains("Kind", Tag.TAG_STRING) && compound.getString("Kind").equals("record")
				&& compound.contains("Type", Tag.TAG_STRING)
				&& compound.getString("Type").equals(RadioEnvelope.class.getName())) {
				if (!legacyRadioEnvelopeShape(compound))
					throw new IllegalArgumentException("Version 1 RadioEnvelope must use its exact legacy shape");
				CompoundTag marker = compound(compound, "boundaryForwardMarker");
				if (!marker.getAllKeys().equals(Set.of("Kind")) || !string(marker, "Kind").equals("null"))
					throw new IllegalArgumentException("Legacy boundary marker cannot authorize a transition");
				compound.remove("boundaryForwardMarker");
				compound.put("boundaryTransition", marker.copy());
			}
			for (String key : List.copyOf(compound.getAllKeys()))
				migrateLegacyV1(Objects.requireNonNull(compound.get(key)), depth + 1);
		} else if (tag instanceof ListTag list)
			for (Tag child : list) migrateLegacyV1(child, depth + 1);
	}

	private static Object decodeOptional(CompoundTag tag, Type expected,
		HolderLookup.Provider registries, int depth) throws ReflectiveOperationException {
		if (!(expected instanceof ParameterizedType parameterized)
			|| raw(expected) != Optional.class) throw new IllegalArgumentException("Raw Optional");
		boolean present = tag.getBoolean("Present");
		return present ? Optional.ofNullable(decode(compound(tag, "Value"),
			parameterized.getActualTypeArguments()[0], registries, depth + 1)) : Optional.empty();
	}

	private static Object decodeCollection(CompoundTag tag, Type expected,
		HolderLookup.Provider registries, int depth, boolean set) throws ReflectiveOperationException {
		if (!(expected instanceof ParameterizedType parameterized)) throw new IllegalArgumentException("Raw collection");
		ListTag values = list(tag, "Values"); boundedSize(values.size());
		ArrayList<Object> decoded = new ArrayList<>(); Type element = parameterized.getActualTypeArguments()[0];
		for (Tag value : values) decoded.add(decode((CompoundTag) value, element, registries, depth + 1));
		return set ? Set.copyOf(decoded) : List.copyOf(decoded);
	}

	private static Object decodeMap(CompoundTag tag, Type expected,
		HolderLookup.Provider registries, int depth) throws ReflectiveOperationException {
		if (!(expected instanceof ParameterizedType parameterized)) throw new IllegalArgumentException("Raw map");
		ListTag entries = list(tag, "Entries"); boundedSize(entries.size());
		LinkedHashMap<Object, Object> decoded = new LinkedHashMap<>();
		for (Tag rawEntry : entries) {
			CompoundTag entry = (CompoundTag) rawEntry;
			if (!entry.getAllKeys().equals(Set.of("Key", "Value"))) throw new IllegalArgumentException();
			Object key = decode(compound(entry, "Key"), parameterized.getActualTypeArguments()[0], registries, depth + 1);
			Object value = decode(compound(entry, "Value"), parameterized.getActualTypeArguments()[1], registries, depth + 1);
			if (decoded.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate map key");
		}
		return Map.copyOf(decoded);
	}

	private static NativePackageObservation decodeNativeObservation(CompoundTag tag,
		HolderLookup.Provider registries, int depth) throws ReflectiveOperationException {
		@SuppressWarnings("unchecked")
		Optional<com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger.OrderContext>
			context = (Optional<com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger.OrderContext>)
			decode(compound(tag, "Context"),
				new TypeToken<Optional<com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger.OrderContext>>() {}.type,
				registries, depth + 1);
		@SuppressWarnings("unchecked")
		Map<StackKey, Long> stacks = (Map<StackKey, Long>) decode(compound(tag, "Stacks"),
			new TypeToken<Map<StackKey, Long>>() {}.type, registries, depth + 1);
		return new NativePackageObservation(
			(com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey) decode(
				compound(tag, "Route"), com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey.class,
				registries, depth + 1), tag.getBoolean("HasOrder"), tag.getInt("Order"),
			tag.getInt("Link"), tag.getInt("Fragment"), tag.getBoolean("FinalLink"),
			tag.getBoolean("Final"),
			context.orElse(null), stacks);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object decodeEnum(CompoundTag tag, Class<?> expected) throws ClassNotFoundException {
		Class<?> actual = storedSchemaType(string(tag, "Type"), expected);
		if (actual != expected || !actual.isEnum() || !allowed(actual)) throw new IllegalArgumentException();
		return Enum.valueOf((Class) actual, string(tag, "Value"));
	}

	private static CompoundTag collection(String kind, Iterable<?> values,
		HolderLookup.Provider registries, int depth) {
		CompoundTag tag = new CompoundTag(); tag.putString("Kind", kind); ListTag list = new ListTag();
		for (Object value : values) { boundedSize(list.size() + 1); list.add(encode(value, registries, depth + 1)); }
		if (kind.equals("set")) list.sort(Comparator.comparing(CurrentSchemaStateNbt::canonical));
		tag.put("Values", list); return tag;
	}

	private static void validateShape(CompoundTag tag, String kind) {
		switch (kind) {
			case "null" -> exact(tag, Set.of("Kind"));
			case "boolean" -> { exact(tag, Set.of("Kind", "Value")); booleanValue(tag, "Value"); }
			case "byte" -> { exact(tag, Set.of("Kind", "Value")); require(tag, "Value", Tag.TAG_BYTE); }
			case "short" -> { exact(tag, Set.of("Kind", "Value")); require(tag, "Value", Tag.TAG_SHORT); }
			case "int" -> { exact(tag, Set.of("Kind", "Value")); require(tag, "Value", Tag.TAG_INT); }
			case "long", "block_pos" -> { exact(tag, Set.of("Kind", "Value")); require(tag, "Value", Tag.TAG_LONG); }
			case "float" -> { exact(tag, Set.of("Kind", "Value")); require(tag, "Value", Tag.TAG_FLOAT); }
			case "double" -> { exact(tag, Set.of("Kind", "Value")); require(tag, "Value", Tag.TAG_DOUBLE); }
			case "string", "resource_location", "communication_frequency" -> {
				exact(tag, Set.of("Kind", "Value")); string(tag, "Value");
			}
			case "byte_array" -> {
				exact(tag, Set.of("Kind", "Value")); require(tag, "Value", Tag.TAG_BYTE_ARRAY);
				boundedBinary(tag.getByteArray("Value"));
			}
			case "uuid" -> {
				exact(tag, Set.of("Kind", "Value"));
				if (!tag.hasUUID("Value")) throw new IllegalArgumentException("Invalid UUID");
			}
			case "resource_key" -> {
				exact(tag, Set.of("Kind", "Registry", "Location"));
				string(tag, "Registry"); string(tag, "Location");
			}
			case "enum" -> {
				exact(tag, Set.of("Kind", "Type", "Value"));
				string(tag, "Type"); string(tag, "Value");
			}
			case "optional" -> {
				boolean present = booleanValue(tag, "Present");
				exact(tag, present ? Set.of("Kind", "Present", "Value")
					: Set.of("Kind", "Present"));
				if (present) compound(tag, "Value");
			}
			case "optional_long" -> {
				boolean present = booleanValue(tag, "Present");
				exact(tag, present ? Set.of("Kind", "Present", "Value")
					: Set.of("Kind", "Present"));
				if (present) require(tag, "Value", Tag.TAG_LONG);
			}
			case "optional_int" -> {
				boolean present = booleanValue(tag, "Present");
				exact(tag, present ? Set.of("Kind", "Present", "Value")
					: Set.of("Kind", "Present"));
				if (present) require(tag, "Value", Tag.TAG_INT);
			}
			case "stack_key", "item_stack", "component_predicate" -> {
				exact(tag, Set.of("Kind", "Value")); compound(tag, "Value");
			}
			case "native_observation" -> {
				exact(tag, Set.of("Kind", "Route", "HasOrder", "Order", "Link", "Fragment",
					"FinalLink", "Final", "Context", "Stacks"));
				compound(tag, "Route"); compound(tag, "Context"); compound(tag, "Stacks");
				booleanValue(tag, "HasOrder"); booleanValue(tag, "FinalLink");
				booleanValue(tag, "Final"); require(tag, "Order", Tag.TAG_INT);
				require(tag, "Link", Tag.TAG_INT); require(tag, "Fragment", Tag.TAG_INT);
			}
			case "list", "set" -> { exact(tag, Set.of("Kind", "Values")); list(tag, "Values"); }
			case "map" -> { exact(tag, Set.of("Kind", "Entries")); list(tag, "Entries"); }
			case "record", "capability" -> {}
			default -> throw new IllegalArgumentException("Unknown current-schema kind");
		}
	}

	private static String canonical(Tag tag) {
		StringBuilder result = new StringBuilder(); canonical(tag, result); return result.toString();
	}

	private static void canonical(Tag tag, StringBuilder result) {
		result.append(tag.getId()).append(':');
		if (tag instanceof CompoundTag compound) {
			compound.getAllKeys().stream().sorted().forEach(key -> {
				result.append(key.length()).append(':').append(key).append('=');
				canonical(Objects.requireNonNull(compound.get(key)), result);
			});
			return;
		}
		if (tag instanceof ListTag list) {
			result.append(list.size()).append('[');
			for (Tag child : list) canonical(child, result);
			result.append(']'); return;
		}
		String value = tag.getAsString();
		result.append(value.length()).append(':').append(value);
	}

	private static CompoundTag scalar(String kind, String nbtType, Number value) {
		CompoundTag tag = new CompoundTag(); tag.putString("Kind", kind);
		switch (nbtType) {
			case "Byte" -> tag.putByte("Value", value.byteValue()); case "Short" -> tag.putShort("Value", value.shortValue());
			case "Int" -> tag.putInt("Value", value.intValue()); case "Long" -> tag.putLong("Value", value.longValue());
			case "Float" -> tag.putFloat("Value", value.floatValue()); case "Double" -> tag.putDouble("Value", value.doubleValue());
			default -> throw new IllegalArgumentException();
		}
		return tag;
	}

	private static CompoundTag scalar(String kind, String nbtType, Boolean value) {
		CompoundTag tag = new CompoundTag(); tag.putString("Kind", kind); tag.putBoolean("Value", value); return tag;
	}

	private static ItemStack nonEmptyStack(CompoundTag tag, HolderLookup.Provider registries) {
		ItemStack stack = ItemStack.parseOptional(registries, compound(tag, "Value"));
		if (stack.isEmpty()) throw new IllegalArgumentException("Empty StackKey"); return stack;
	}

	private static CompoundTag encodeComponentPredicate(DataComponentPredicate predicate,
		HolderLookup.Provider registries) {
		Tag encoded = DataComponentPredicate.CODEC.encodeStart(
			registries.createSerializationContext(NbtOps.INSTANCE), predicate).result()
			.orElseThrow(() -> new IllegalArgumentException("Invalid component predicate"));
		if (!(encoded instanceof CompoundTag compound))
			throw new IllegalArgumentException("Invalid component predicate shape");
		return compound;
	}

	private static DataComponentPredicate decodeComponentPredicate(CompoundTag tag, Class<?> expected,
		HolderLookup.Provider registries) {
		if (expected != DataComponentPredicate.class)
			throw new IllegalArgumentException("Component predicate type mismatch");
		CompoundTag encoded = compound(tag, "Value");
		DataComponentPredicate predicate = DataComponentPredicate.CODEC.parse(
			registries.createSerializationContext(NbtOps.INSTANCE), encoded).result()
			.orElseThrow(() -> new IllegalArgumentException("Invalid component predicate"));
		if (!encoded.equals(encodeComponentPredicate(predicate, registries)))
			throw new IllegalArgumentException("Non-canonical component predicate");
		return predicate;
	}

	private static Class<?> raw(Type type) {
		if (type instanceof Class<?> raw) return raw;
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) return raw;
		throw new IllegalArgumentException("Unsupported generic type " + type.getTypeName());
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static net.minecraft.resources.ResourceKey<?> resourceKey(CompoundTag tag) {
		var registry = net.minecraft.resources.ResourceKey.createRegistryKey(
			net.minecraft.resources.ResourceLocation.parse(string(tag, "Registry")));
		return net.minecraft.resources.ResourceKey.create((net.minecraft.resources.ResourceKey) registry,
			net.minecraft.resources.ResourceLocation.parse(string(tag, "Location")));
	}

	private static String storedTypeName(Class<?> type) {
		if (type == RecipeSourceLocation.class) return LEGACY_SOURCE_LOCATION;
		return RELOCATED_APPLICATION_TYPES.getOrDefault(type, type.getName());
	}

	private static Class<?> storedSchemaType(String name, Class<?> expected) throws ClassNotFoundException {
		// Only a recipe location field changes representation; unrelated old addresses stay world-facing.
		if (expected == RecipeSourceLocation.class && LEGACY_SOURCE_LOCATION.equals(name))
			return RecipeSourceLocation.class;
		for (var entry : RELOCATED_APPLICATION_TYPES.entrySet())
			if (entry.getValue().equals(name)) return entry.getKey();
		Class<?> type = Class.forName(name);
		if (type == RecipeSourceLocation.class || RELOCATED_APPLICATION_TYPES.containsKey(type))
			throw new IllegalArgumentException("Relocated application type must retain its historical schema identity");
		return type;
	}

	private static boolean allowed(Class<?> type) {
		String name = type.getName();
		return name.startsWith("com.nobodiiiii.createbiotech.content.factorycluster.")
			|| type == RecipeSourceLocation.class || RELOCATED_APPLICATION_TYPES.containsKey(type)
			|| type == net.minecraft.core.Direction.class
			|| type == UUID.class || type == String.class || type.isPrimitive()
			|| Number.class.isAssignableFrom(type) || type == Boolean.class;
	}
	private static boolean supportedVersion(int version) {
		return version == VERSION || version == 5 || version == PRIOR_VERSION
			|| version == FIRST_COMPATIBLE_VERSION || version == LEGACY_VERSION;
	}

	private static CompoundTag compound(CompoundTag tag, String key) {
		Tag value = tag.get(key); if (!(value instanceof CompoundTag compound)) throw new IllegalArgumentException("Missing compound " + key); return compound;
	}
	private static ListTag list(CompoundTag tag, String key) {
		Tag value = tag.get(key); if (!(value instanceof ListTag list) || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Invalid list " + key); return list;
	}
	private static String string(CompoundTag tag, String key) {
		if (!tag.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("Missing string " + key);
		String value = tag.getString(key); boundedText(value); return value;
	}
	private static void exact(CompoundTag tag, Set<String> keys) {
		if (!tag.getAllKeys().equals(keys)) throw new IllegalArgumentException("Unexpected current-schema keys");
	}
	private static void require(CompoundTag tag, String key, int type) {
		if (!tag.contains(key, type)) throw new IllegalArgumentException("Invalid current-schema value " + key);
	}
	private static boolean booleanValue(CompoundTag tag, String key) {
		require(tag, key, Tag.TAG_BYTE); byte value = tag.getByte(key);
		if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid boolean " + key);
		return value != 0;
	}
	private static void boundedText(String value) { if (value.length() > MAX_TEXT) throw new IllegalArgumentException("Text exceeds bound"); }
	private static void boundedBinary(byte[] value) { if (value.length > RemoteExpansionRequest.MAX_SERIALIZED_BYTES) throw new IllegalArgumentException("Binary value exceeds bound"); }
	private static void boundedSize(int size) { if (size < 0 || size > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES) throw new IllegalArgumentException("Collection exceeds bound"); }

	private abstract static class TypeToken<T> {
		final Type type = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
	}
}
