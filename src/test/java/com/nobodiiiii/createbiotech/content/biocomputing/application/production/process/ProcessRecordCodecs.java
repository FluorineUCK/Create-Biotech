package com.nobodiiiii.createbiotech.content.biocomputing.application.production.process;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfileCodecs;
import java.util.*;
import net.minecraft.nbt.*;

/** Strict historical process/child format, separate from the pure production state and generic RAM. */
public final class ProcessRecordCodecs {
    private static final int MAX_ENUM_NAME_LENGTH = 4096;
    private ProcessRecordCodecs() {}

    public static Optional<ProcessRecord> load(CompoundTag tag) {
        if (tag == null) return Optional.empty();
        try { return Optional.of(decode(tag)); }
        catch (RuntimeException invalid) { return Optional.empty(); }
    }

	public static CompoundTag save(ProcessRecord process) {
		CompoundTag tag = new CompoundTag(); tag.putUUID("ProcessId", process.processId()); tag.putUUID("GoalId", process.goalId());
		tag.putUUID("ComputerId", process.owningComputerId()); tag.putInt("Slot", process.owningSlot()); tag.put("Profile", ProductionResourceProfileCodecs.save(process.owningComputer()));
		tag.putString("Kind", process.kind().name()); tag.putString("State", process.state().name());
		if (process.waitReason() != null) tag.putString("WaitReason", process.waitReason().name()); tag.putLong("Generation", process.generation());
		ListTag children = new ListTag(); process.childRevisionStates().values().stream()
			.sorted(Comparator.comparing(status -> status.revision().childProcessId())).forEach(status -> children.add(saveChild(status)));
		tag.put("Children", children); return tag;
	}

	private static ProcessRecord decode(CompoundTag tag) {
		Set<String> keys = new HashSet<>(Set.of("ProcessId", "GoalId", "ComputerId", "Slot", "Profile", "Kind", "State", "Generation", "Children"));
		if (tag.contains("WaitReason")) keys.add("WaitReason"); exact(tag, keys);
		ProductionResourceProfile profile = ProductionResourceProfileCodecs.load(compound(tag, "Profile")).orElseThrow();
		ProcessState state = enumeration(ProcessState.class, string(tag, "State"));
		WaitReason wait = tag.contains("WaitReason") ? enumeration(WaitReason.class, string(tag, "WaitReason")) : null;
		LinkedHashMap<UUID, ProcessRecord.ChildRevisionStatus> children = new LinkedHashMap<>();
		for (Tag raw : compoundList(tag, "Children", ProcessRecord.MAX_CHILD_REVISIONS)) {
			ProcessRecord.ChildRevisionStatus child = loadChild((CompoundTag) raw);
			if (children.putIfAbsent(child.revision().childProcessId(), child) != null) throw new IllegalArgumentException("duplicate child");
		}
		return new ProcessRecord(uuid(tag, "ProcessId"), uuid(tag, "GoalId"), uuid(tag, "ComputerId"),
			integer(tag, "Slot"), profile, enumeration(ProcessKind.class, string(tag, "Kind")), state, wait,
			nonNegative(longValue(tag, "Generation")), children);
	}

	private static CompoundTag saveChild(ProcessRecord.ChildRevisionStatus status) {
		ChildExecutionRevision child = status.revision(); CompoundTag tag = new CompoundTag(); tag.putUUID("ProcessId", child.childProcessId());
		tag.putLong("Generation", child.childGeneration()); tag.putLong("Revision", child.revision()); tag.putString("PreviousMode", child.previousMode().name());
		tag.putString("EffectiveMode", child.effectiveMode().name()); tag.putLong("Total", child.totalPromise()); tag.putLong("Feasible", child.immediatelyFeasible());
		tag.putLong("Remaining", child.remaining()); tag.putBoolean("Acknowledged", status.acknowledgementDurable()); return tag;
	}

	private static ProcessRecord.ChildRevisionStatus loadChild(CompoundTag tag) {
		exact(tag, Set.of("ProcessId", "Generation", "Revision", "PreviousMode", "EffectiveMode", "Total", "Feasible", "Remaining", "Acknowledged"));
		return new ProcessRecord.ChildRevisionStatus(new ChildExecutionRevision(uuid(tag, "ProcessId"),
			nonNegative(longValue(tag, "Generation")), nonNegative(longValue(tag, "Revision")),
			enumeration(InputMode.class, string(tag, "PreviousMode")), enumeration(InputMode.class, string(tag, "EffectiveMode")),
			positive(longValue(tag, "Total")), nonNegative(longValue(tag, "Feasible")), nonNegative(longValue(tag, "Remaining"))), bool(tag, "Acknowledged"));
	}

	private static ListTag compoundList(CompoundTag tag, String key, int cap) { Tag raw = tag.get(key); if (!(raw instanceof ListTag list) || list.size() > cap || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid list " + key); return list; }
	private static CompoundTag compound(CompoundTag tag, String key) { Tag raw = tag.get(key); if (!(raw instanceof CompoundTag child)) throw new IllegalArgumentException("invalid compound " + key); return child; }
	private static UUID uuid(CompoundTag tag, String key) { if (!tag.hasUUID(key)) throw new IllegalArgumentException("invalid UUID " + key); return tag.getUUID(key); }
	private static String string(CompoundTag tag, String key) { if (!tag.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("invalid string " + key); String value = tag.getString(key); if (value.length() > MAX_ENUM_NAME_LENGTH) throw new IllegalArgumentException("string exceeds bound"); return value; }
	private static int integer(CompoundTag tag, String key) { if (!tag.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("invalid int " + key); return tag.getInt(key); }
	private static long longValue(CompoundTag tag, String key) { if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException("invalid long " + key); return tag.getLong(key); }
	private static boolean bool(CompoundTag tag, String key) { if (!tag.contains(key, Tag.TAG_BYTE) || tag.getByte(key) != 0 && tag.getByte(key) != 1) throw new IllegalArgumentException("invalid boolean " + key); return tag.getBoolean(key); }
	private static void exact(CompoundTag tag, Set<String> keys) { if (!tag.getAllKeys().equals(keys)) throw new IllegalArgumentException("unexpected keys"); }
	private static long nonNegative(long value) { if (value < 0) throw new IllegalArgumentException("negative value"); return value; }
	private static int nonNegative(int value) { if (value < 0) throw new IllegalArgumentException("negative value"); return value; }
	private static long positive(long value) { if (value <= 0) throw new IllegalArgumentException("non-positive value"); return value; }
	private static <E extends Enum<E>> E enumeration(Class<E> type, String value) { return Enum.valueOf(type, value); }
}
