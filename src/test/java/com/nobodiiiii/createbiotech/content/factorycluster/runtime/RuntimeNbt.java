package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecordCodecs;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ChildExecutionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfileCodecs;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCursorCodecs;

import java.util.*;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PriorityAssociationCodecs;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;

/** Strict codec for the sole current OrchestrationRuntime schema. */
public final class RuntimeNbt {
	private static final int VERSION = 1;
	public static final int MAX_MEMBERS = ClusterEngineState.MAX_MEMBERS;
	private static final Set<String> ROOT_KEYS = Set.of("Version", "ClusterId", "EpochId", "Generation",
		"NextSubmissionSequence", "NextRouteSelectionSequence", "MemberCursor", "ProcessCursor",
		"Goals", "Processes", "Members",
		"PendingIntents", "UncertainIntents", "GoalScan", "RouteCursor", "CoordinationAssociations",
		"PriorityLeaseAssociations");

	private RuntimeNbt() {}

	public static CompoundTag save(ClusterEngineState state) {
		return save(state, RegistryAccess.EMPTY);
	}

	public static CompoundTag save(ClusterEngineState state, HolderLookup.Provider registries) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(registries, "registries");
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION); tag.putUUID("ClusterId", state.clusterId()); tag.putUUID("EpochId", state.epochId());
		tag.putLong("Generation", state.generation()); tag.putLong("NextSubmissionSequence", state.nextSubmissionSequence());
		tag.putLong("NextRouteSelectionSequence", state.nextRouteSelectionSequence());
		tag.putInt("MemberCursor", state.memberCursor()); tag.putInt("ProcessCursor", state.processCursor());
		CompoundTag scan = new CompoundTag(); scan.putInt("Offset", state.goalScanOffset());
		scan.putBoolean("HasCandidate", state.goalScanCandidate() != null);
		if (state.goalScanCandidate() != null) scan.putUUID("Candidate", state.goalScanCandidate());
		tag.put("GoalScan", scan);
		ListTag goals = new ListTag();
		state.goals().values().stream().sorted(Comparator.comparingLong(goal -> state.submissionSequences().get(goal.goalId())))
			.forEach(goal -> goals.add(saveGoal(goal, state.submissionSequences().get(goal.goalId()), state.requestedKinds().get(goal.goalId()))));
		tag.put("Goals", goals);
		ListTag processes = new ListTag(); state.processes().values()
			.forEach(process -> processes.add(ProcessRecordCodecs.save(process))); tag.put("Processes", processes);
		ListTag members = new ListTag(); state.members().stream().sorted(Comparator.comparing(ClusterEngineState.MemberSnapshot::computerId))
			.forEach(member -> members.add(saveMember(member))); tag.put("Members", members);
		ListTag intents = new ListTag(); state.pendingIntents().values().stream().sorted(Comparator.comparing(ProductionEffect::intentId))
			.forEach(intent -> intents.add(saveIntent(intent, registries))); tag.put("PendingIntents", intents);
		ListTag uncertain = new ListTag(); state.uncertainIntentIds().stream().sorted().forEach(intentId -> {
			CompoundTag child = new CompoundTag(); child.putUUID("IntentId", intentId); uncertain.add(child);
		}); tag.put("UncertainIntents", uncertain);
		tag.put("RouteCursor", RouteCursorCodecs.save(state.routeCursor()));
		tag.put("CoordinationAssociations", PriorityAssociationCodecs.save(state.coordinationAssociations().values()));
		ListTag priorityAssociations = new ListTag();
		state.priorityLeaseAssociations().values().stream()
			.sorted(Comparator.comparing(PriorityLeaseAssociation::processId))
			.map(RuntimeNbt::savePriorityLeaseAssociation).forEach(priorityAssociations::add);
		tag.put("PriorityLeaseAssociations", priorityAssociations);
		return tag;
	}

	public static Optional<ClusterEngineState> load(CompoundTag tag) {
		return load(tag, RegistryAccess.EMPTY);
	}

	public static Optional<ClusterEngineState> load(CompoundTag tag,
		HolderLookup.Provider registries) {
		Objects.requireNonNull(registries, "registries");
		if (tag == null) return Optional.empty();
		try {
			exact(tag, ROOT_KEYS);
			if (integer(tag, "Version") != VERSION) return Optional.empty();
			UUID cluster = uuid(tag, "ClusterId"), epoch = uuid(tag, "EpochId");
			long generation = nonNegative(longValue(tag, "Generation"));
			long nextSequence = nonNegative(longValue(tag, "NextSubmissionSequence"));
			long nextRouteSelectionSequence = nonNegative(longValue(tag, "NextRouteSelectionSequence"));
			int memberCursor = nonNegative(integer(tag, "MemberCursor"));
			int processCursor = nonNegative(integer(tag, "ProcessCursor"));
			CompoundTag scan = compound(tag, "GoalScan");
			Set<String> scanKeys = new HashSet<>(Set.of("Offset", "HasCandidate"));
			boolean hasCandidate = bool(scan, "HasCandidate");
			if (hasCandidate) scanKeys.add("Candidate"); exact(scan, scanKeys);
			int goalScanOffset = nonNegative(integer(scan, "Offset"));
			UUID goalScanCandidate = hasCandidate ? uuid(scan, "Candidate") : null;
			ListTag goalTags = compoundList(tag, "Goals", ClusterEngineState.MAX_GOALS);
			LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>();
			LinkedHashMap<UUID, Long> sequences = new LinkedHashMap<>();
			LinkedHashMap<UUID, ProcessKind> kinds = new LinkedHashMap<>();
			for (Tag raw : goalTags) {
				DecodedGoal decoded = loadGoal((CompoundTag) raw, cluster);
				if (goals.putIfAbsent(decoded.goal.goalId(), decoded.goal) != null) throw new IllegalArgumentException("duplicate Goal");
				sequences.put(decoded.goal.goalId(), decoded.sequence); kinds.put(decoded.goal.goalId(), decoded.kind);
			}
			ListTag processTags = compoundList(tag, "Processes", ClusterEngineState.MAX_PROCESSES);
			LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>();
			for (Tag raw : processTags) { ProcessRecord process = ProcessRecordCodecs.load((CompoundTag) raw).orElseThrow(); if (processes.putIfAbsent(process.processId(), process) != null) throw new IllegalArgumentException("duplicate process"); }
			ListTag memberTags = compoundList(tag, "Members", MAX_MEMBERS);
			ArrayList<ClusterEngineState.MemberSnapshot> members = new ArrayList<>();
			for (Tag raw : memberTags) members.add(loadMember((CompoundTag) raw));
			ListTag intentTags = compoundList(tag, "PendingIntents", ClusterEngineState.MAX_PENDING_INTENTS);
			LinkedHashMap<UUID, ProductionEffect> intents = new LinkedHashMap<>();
			for (Tag raw : intentTags) { ProductionEffect intent = loadIntent((CompoundTag) raw, registries); if (!intent.clusterId().equals(cluster) || intents.putIfAbsent(intent.intentId(), intent) != null) throw new IllegalArgumentException("invalid intent owner"); }
			LinkedHashSet<UUID> uncertain = new LinkedHashSet<>();
			for (Tag raw : compoundList(tag, "UncertainIntents", ClusterEngineState.MAX_PENDING_INTENTS)) {
				CompoundTag child = (CompoundTag) raw; exact(child, Set.of("IntentId"));
				if (!uncertain.add(uuid(child, "IntentId"))) throw new IllegalArgumentException("duplicate uncertain intent");
			}
			var routeCursor = RouteCursorCodecs.load(compound(tag, "RouteCursor")).orElseThrow();
			Map<UUID, CoordinationAssociation> associations = PriorityAssociationCodecs.load(
				compoundList(tag, "CoordinationAssociations", PatternPriorityAuthority.MAX_LEASES)).orElseThrow();
			Map<UUID, PriorityLeaseAssociation> priorityAssociations = new LinkedHashMap<>();
			for (Tag raw : compoundList(tag, "PriorityLeaseAssociations", ClusterEngineState.MAX_PROCESSES)) {
				PriorityLeaseAssociation association = loadPriorityLeaseAssociation((CompoundTag) raw);
				if (priorityAssociations.putIfAbsent(association.processId(), association) != null)
					throw new IllegalArgumentException("duplicate priority lease association");
			}
			return Optional.of(new ClusterEngineState(cluster, epoch, generation, nextSequence,
				nextRouteSelectionSequence, memberCursor, processCursor, goals, sequences, kinds,
				processes, members, intents, uncertain,
				goalScanOffset, goalScanCandidate, routeCursor, associations, priorityAssociations));
		} catch (RuntimeException invalid) { return Optional.empty(); }
	}

	private static CompoundTag savePriorityLeaseAssociation(PriorityLeaseAssociation association) {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("ProcessId", association.processId());
		tag.putUUID("RequestId", association.requestId()); tag.putUUID("RunId", association.runId());
		tag.putLong("Fence", association.fence()); tag.putUUID("SourceClusterId", association.sourceClusterId());
		tag.putUUID("AgentId", association.agentId());
		tag.putUUID("AgentOwnerClusterId", association.agentOwnerClusterId());
		tag.putUUID("RouteNetwork", association.targetRoute().logisticsNetworkId());
		tag.putString("RouteAddress", association.targetRoute().address());
		tag.putUUID("PatternId", association.patternId()); tag.putString("Mode", association.mode().name());
		tag.putLong("Multiplier", association.multiplier());
		tag.putLong("EnqueueSequence", association.enqueueSequence());
		tag.putInt("RequestedPriority", association.requestedPriority());
		tag.putLong("SourceMaterialSnapshotGeneration", association.sourceMaterialSnapshotGeneration());
		return tag;
	}

	private static PriorityLeaseAssociation loadPriorityLeaseAssociation(CompoundTag tag) {
		exact(tag, Set.of("ProcessId", "RequestId", "RunId", "Fence", "SourceClusterId", "AgentId",
			"AgentOwnerClusterId", "RouteNetwork", "RouteAddress", "PatternId", "Mode", "Multiplier",
			"EnqueueSequence", "RequestedPriority", "SourceMaterialSnapshotGeneration"));
		return new PriorityLeaseAssociation(uuid(tag, "ProcessId"), uuid(tag, "RequestId"),
			uuid(tag, "RunId"), nonNegative(longValue(tag, "Fence")), uuid(tag, "SourceClusterId"),
			uuid(tag, "AgentId"), uuid(tag, "AgentOwnerClusterId"),
			new CreateRouteKey(uuid(tag, "RouteNetwork"), string(tag, "RouteAddress")),
			uuid(tag, "PatternId"), enumeration(InputMode.class, string(tag, "Mode")),
			positive(longValue(tag, "Multiplier")), nonNegative(longValue(tag, "EnqueueSequence")),
			integer(tag, "RequestedPriority"),
			nonNegative(longValue(tag, "SourceMaterialSnapshotGeneration")));
	}

	private static CompoundTag saveGoal(GoalRecord goal, long sequence, ProcessKind kind) {
		CompoundTag tag = new CompoundTag(); tag.putUUID("GoalId", goal.goalId()); tag.putUUID("PatternId", goal.submission().patternId());
		tag.putLong("Multiplier", goal.submission().multiplier()); tag.putString("InputMode", goal.submission().inputMode().name());
		tag.putUUID("RouteNetwork", goal.deliveryRoute().logisticsNetworkId()); tag.putString("RouteAddress", goal.deliveryRoute().address());
		tag.putLong("PolicyGeneration", goal.policy().generation()); tag.putInt("Priority", goal.policy().priority());
		tag.putBoolean("DirectRequiredOutputs", goal.policy().directRequiredOutputs()); tag.putString("State", goal.state().name());
		if (goal.waitReason() != null) tag.putString("WaitReason", goal.waitReason().name());
		tag.putLong("SubmissionSequence", sequence); tag.putString("ProcessKind", kind.name()); return tag;
	}

	private static DecodedGoal loadGoal(CompoundTag tag, UUID cluster) {
		Set<String> keys = new HashSet<>(Set.of("GoalId", "PatternId", "Multiplier", "InputMode", "RouteNetwork",
			"RouteAddress", "PolicyGeneration", "Priority", "DirectRequiredOutputs", "State", "SubmissionSequence", "ProcessKind"));
		if (tag.contains("WaitReason")) keys.add("WaitReason"); exact(tag, keys);
		UUID goalId = uuid(tag, "GoalId"); GoalState state = enumeration(GoalState.class, string(tag, "State"));
		WaitReason wait = tag.contains("WaitReason") ? enumeration(WaitReason.class, string(tag, "WaitReason")) : null;
		GoalRecord goal = new GoalRecord(goalId, cluster, new GoalSubmission(goalId, uuid(tag, "PatternId"),
			positive(longValue(tag, "Multiplier")), enumeration(InputMode.class, string(tag, "InputMode"))),
			new CreateRouteKey(uuid(tag, "RouteNetwork"), string(tag, "RouteAddress")),
			new PolicySnapshot(nonNegative(longValue(tag, "PolicyGeneration")), integer(tag, "Priority"), bool(tag, "DirectRequiredOutputs")), state, wait);
		return new DecodedGoal(goal, nonNegative(longValue(tag, "SubmissionSequence")), enumeration(ProcessKind.class, string(tag, "ProcessKind")));
	}

	private static CompoundTag saveMember(ClusterEngineState.MemberSnapshot member) {
		CompoundTag tag = new CompoundTag(); tag.putUUID("ComputerId", member.computerId()); tag.put("Profile", ProductionResourceProfileCodecs.save(member.profile()));
		tag.putBoolean("Loaded", member.loaded()); tag.putLong("RouteGeneration", member.routeGeneration()); tag.putLong("Fence", member.fence()); return tag;
	}
	private static ClusterEngineState.MemberSnapshot loadMember(CompoundTag tag) {
		exact(tag, Set.of("ComputerId", "Profile", "Loaded", "RouteGeneration", "Fence"));
		return new ClusterEngineState.MemberSnapshot(uuid(tag, "ComputerId"), ProductionResourceProfileCodecs.load(compound(tag, "Profile")).orElseThrow(),
			bool(tag, "Loaded"), nonNegative(longValue(tag, "RouteGeneration")), nonNegative(longValue(tag, "Fence")));
	}

	private static CompoundTag saveIntent(ProductionEffect intent,
		HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag(); tag.putString("Type", intent.getClass().getSimpleName()); tag.putUUID("IntentId", intent.intentId());
		tag.putUUID("ClusterId", intent.clusterId()); tag.putLong("Generation", intent.generation()); tag.putUUID("EpochId", intent.fence().epochId());
		tag.putUUID("TargetId", intent.fence().targetId()); tag.putLong("RouteGeneration", intent.fence().routeGeneration()); tag.putLong("ValidationFence", intent.fence().validationFence());
		switch (intent) {
			case ProductionEffect.LibraryQuery value -> { tag.putUUID("QueryId", value.queryId()); tag.putUUID("PatternId", value.patternId()); }
			case ProductionEffect.RadioSend value -> { tag.putUUID("MessageId", value.messageId()); tag.putString("Payload", value.payload()); }
			case ProductionEffect.StockSampleRequest value -> { tag.putUUID("RequestId", value.requestId()); putRoute(tag, value.route()); }
			case ProductionEffect.AgentTicketOffer value -> { tag.putUUID("ProcessId", value.processId()); tag.putUUID("AgentId", value.agentId()); }
			case ProductionEffect.CreateDispatch value -> { tag.putUUID("ProcessId", value.processId()); tag.putUUID("RunId", value.runId()); putRoute(tag, value.route()); ListTag list = new ListTag(); value.ingredients().forEach(item -> { CompoundTag child = new CompoundTag(); child.put("Stack", item.stack().stack().saveOptional(registries)); child.putLong("Amount", item.amount()); list.add(child); }); tag.put("Ingredients", list); }
			case ProductionEffect.OutputPackageRequest value -> { tag.putUUID("RunId", value.runId()); putRoute(tag, value.destination()); tag.put("ExactOutput", value.exactOutput().stack().saveOptional(registries)); }
			case ProductionEffect.PanelSnapshotPublication value -> { ListTag list = new ListTag(); value.rows().forEach(row -> { CompoundTag child = new CompoundTag(); child.putUUID("GoalId", row.goalId()); child.putInt("Priority", row.priority()); child.putString("State", row.state()); list.add(child); }); tag.put("Rows", list); }
		}
		return tag;
	}

	private static ProductionEffect loadIntent(CompoundTag tag,
		HolderLookup.Provider registries) {
		String type = string(tag, "Type"); Set<String> keys = new HashSet<>(Set.of("Type", "IntentId", "ClusterId", "Generation", "EpochId", "TargetId", "RouteGeneration", "ValidationFence"));
		Collections.addAll(keys, switch (type) {
			case "LibraryQuery" -> new String[]{"QueryId", "PatternId"}; case "RadioSend" -> new String[]{"MessageId", "Payload"};
			case "StockSampleRequest" -> new String[]{"RequestId", "RouteNetwork", "RouteAddress"};
			case "AgentTicketOffer" -> new String[]{"ProcessId", "AgentId"};
			case "CreateDispatch" -> new String[]{"ProcessId", "RunId", "RouteNetwork", "RouteAddress", "Ingredients"};
			case "OutputPackageRequest" -> new String[]{"RunId", "RouteNetwork", "RouteAddress", "ExactOutput"};
			case "PanelSnapshotPublication" -> new String[]{"Rows"}; default -> throw new IllegalArgumentException("unknown intent"); }); exact(tag, keys);
		UUID intentId = uuid(tag, "IntentId"), clusterId = uuid(tag, "ClusterId"); long generation = nonNegative(longValue(tag, "Generation"));
		ProductionEffect.TargetFence fence = new ProductionEffect.TargetFence(uuid(tag, "EpochId"), uuid(tag, "TargetId"),
			nonNegative(longValue(tag, "RouteGeneration")), nonNegative(longValue(tag, "ValidationFence")));
		return switch (type) {
			case "LibraryQuery" -> new ProductionEffect.LibraryQuery(intentId, clusterId, generation, fence, uuid(tag, "QueryId"), uuid(tag, "PatternId"));
			case "RadioSend" -> new ProductionEffect.RadioSend(intentId, clusterId, generation, fence, uuid(tag, "MessageId"), string(tag, "Payload"));
			case "StockSampleRequest" -> new ProductionEffect.StockSampleRequest(intentId, clusterId, generation, fence, uuid(tag, "RequestId"), route(tag));
			case "AgentTicketOffer" -> new ProductionEffect.AgentTicketOffer(intentId, clusterId, generation, fence, uuid(tag, "ProcessId"), uuid(tag, "AgentId"));
			case "CreateDispatch" -> new ProductionEffect.CreateDispatch(intentId, clusterId, generation, fence, uuid(tag, "ProcessId"), uuid(tag, "RunId"), route(tag), ingredients(tag, registries));
			case "OutputPackageRequest" -> new ProductionEffect.OutputPackageRequest(intentId, clusterId, generation, fence, uuid(tag, "RunId"), route(tag), new StackKey(nonEmptyStack(tag, "ExactOutput", registries)));
			case "PanelSnapshotPublication" -> new ProductionEffect.PanelSnapshotPublication(intentId, clusterId, generation, fence, rows(tag));
			default -> throw new IllegalArgumentException("unknown intent");
		};
	}

	private static List<OrderedIngredient> ingredients(CompoundTag tag,
		HolderLookup.Provider registries) { ArrayList<OrderedIngredient> result = new ArrayList<>(); for (Tag raw : compoundList(tag, "Ingredients", ProductionEffect.MAX_LIST_ENTRIES)) { CompoundTag child = (CompoundTag) raw; exact(child, Set.of("Stack", "Amount")); CompoundTag encoded = compound(child, "Stack"); ItemStack stack = ItemStack.parseOptional(registries, encoded); if (stack.isEmpty()) throw new IllegalArgumentException("invalid ingredient stack"); result.add(new OrderedIngredient(new StackKey(stack), positive(longValue(child, "Amount")))); } return result; }
	private static ItemStack nonEmptyStack(CompoundTag tag, String key,
		HolderLookup.Provider registries) { ItemStack stack = ItemStack.parseOptional(registries, compound(tag, key)); if (stack.isEmpty()) throw new IllegalArgumentException("invalid exact stack " + key); return stack; }
	private static List<ProductionEffect.PanelRow> rows(CompoundTag tag) { ArrayList<ProductionEffect.PanelRow> result = new ArrayList<>(); for (Tag raw : compoundList(tag, "Rows", ProductionEffect.MAX_LIST_ENTRIES)) { CompoundTag child = (CompoundTag) raw; exact(child, Set.of("GoalId", "Priority", "State")); result.add(new ProductionEffect.PanelRow(uuid(child, "GoalId"), integer(child, "Priority"), string(child, "State"))); } return result; }
	private static void putRoute(CompoundTag tag, CreateRouteKey route) { tag.putUUID("RouteNetwork", route.logisticsNetworkId()); tag.putString("RouteAddress", route.address()); }
	private static CreateRouteKey route(CompoundTag tag) { return new CreateRouteKey(uuid(tag, "RouteNetwork"), string(tag, "RouteAddress")); }
	private static ListTag compoundList(CompoundTag tag, String key, int cap) { Tag raw = tag.get(key); if (!(raw instanceof ListTag list) || list.size() > cap || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid list " + key); return list; }
	private static CompoundTag compound(CompoundTag tag, String key) { Tag raw = tag.get(key); if (!(raw instanceof CompoundTag child)) throw new IllegalArgumentException("invalid compound " + key); return child; }
	private static UUID uuid(CompoundTag tag, String key) { if (!tag.hasUUID(key)) throw new IllegalArgumentException("invalid UUID " + key); return tag.getUUID(key); }
	private static String string(CompoundTag tag, String key) { if (!tag.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("invalid string " + key); String value = tag.getString(key); if (value.length() > WorldFact.MAX_TEXT_LENGTH) throw new IllegalArgumentException("string exceeds bound"); return value; }
	private static int integer(CompoundTag tag, String key) { if (!tag.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("invalid int " + key); return tag.getInt(key); }
	private static long longValue(CompoundTag tag, String key) { if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException("invalid long " + key); return tag.getLong(key); }
	private static boolean bool(CompoundTag tag, String key) { if (!tag.contains(key, Tag.TAG_BYTE) || tag.getByte(key) != 0 && tag.getByte(key) != 1) throw new IllegalArgumentException("invalid boolean " + key); return tag.getBoolean(key); }
	private static void exact(CompoundTag tag, Set<String> keys) { if (!tag.getAllKeys().equals(keys)) throw new IllegalArgumentException("unexpected keys"); }
	private static long nonNegative(long value) { if (value < 0) throw new IllegalArgumentException("negative value"); return value; }
	private static int nonNegative(int value) { if (value < 0) throw new IllegalArgumentException("negative value"); return value; }
	private static long positive(long value) { if (value <= 0) throw new IllegalArgumentException("non-positive value"); return value; }
	private static <E extends Enum<E>> E enumeration(Class<E> type, String value) { return Enum.valueOf(type, value); }
	private record DecodedGoal(GoalRecord goal, long sequence, ProcessKind kind) {}
}
