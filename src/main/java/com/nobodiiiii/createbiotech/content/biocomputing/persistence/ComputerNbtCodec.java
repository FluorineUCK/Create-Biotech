package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PeriodicSchedule;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxState;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RpcState;
import java.util.*;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;

/** Complete, versioned Computer save data. Hardware limits and loaded-world state are deliberately absent. */
public final class ComputerNbtCodec {
	private ComputerNbtCodec() {}
	/** Reuse the existing pure-machine frame format, without introducing another evaluator/archive implementation. */
	static DataValue pureMachine(MachineCheckpoint checkpoint) { return machine(checkpoint); }
	/** Flow schemas select exact embedded-frame capabilities; older snapshots cannot smuggle future frames. */
	static MachineCheckpoint readPureMachine(DataValue value, boolean distinct) { return readPureMachine(value, distinct ? 9 : 8); }
	static MachineCheckpoint readPureMachine(DataValue value, long flowVersion) { return readMachine(value, flowVersion >= 10 ? 12 : flowVersion >= 9 ? 11 : 10); }
	public static CompoundTag encode(ComputerRuntime.Snapshot snapshot, ArchiveLimits limits) {
		return ValueNbtCodec.encode(value(snapshot), limits);
	}
	static DataValue value(ComputerRuntime.Snapshot snapshot) {
		boolean equality = ProgramArchiveCodec.hasValueEquality(snapshot.program())
            || snapshot.pending().map(pending -> ProgramArchiveCodec.hasValueEquality(pending.program())).orElse(false)
            || snapshot.machine().frames().stream().anyMatch(MachineCheckpoint.ValueEqualityState.class::isInstance);
        boolean distinct = ProgramArchiveCodec.hasDistinct(snapshot.program())
            || snapshot.pending().map(pending -> ProgramArchiveCodec.hasDistinct(pending.program())).orElse(false)
            || snapshot.machine().frames().stream().anyMatch(MachineCheckpoint.DistinctState.class::isInstance);
        return obj("version", equality ? 12 : distinct ? 11 : 10, "id", snapshot.id().toString(), "program", ProgramArchiveCodec.encode(snapshot.program()),
			"machine", machine(snapshot.machine()), "pending", optional(snapshot.pending(), pending ->
				obj("program", ProgramArchiveCodec.encode(pending.program()), "policy", pending.policy().name())),
			"installationDiagnostic", optional(snapshot.installationDiagnostic(), ComputerNbtCodec::diagnostic),
			"periodic", optional(snapshot.periodic(), ComputerNbtCodec::periodic),
			"delivery", optional(snapshot.delivery(), delivery -> obj("event", delivery.eventId().toString(), "acknowledged", delivery.acknowledged(), "sequenceBase", delivery.sequenceBase())),
			"mailbox", MailboxArchiveCodec.encode(snapshot.mailbox()), "nextSequence", snapshot.nextSequence(),
			"activeEntry", optional(snapshot.activeEntry(), DataValue.TextValue::new), "rpc", RpcArchiveCodec.encode(snapshot.rpc()));
	}
	public static ComputerRuntime.Snapshot decode(CompoundTag tag, ArchiveLimits limits) {
		return readValue(ValueNbtCodec.decode(tag, limits));
	}
	static ComputerRuntime.Snapshot readValue(DataValue data) {
		try {
			long version = number(at(data, "version"));
			if (version < 1 || version > 12) throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported archive schema version");
			var program = ProgramArchiveCodec.decode(at(data, "program"));
            var pending = readOptional(at(data, "pending"), value -> new ComputerRuntime.PendingInstallation(
                ProgramArchiveCodec.decode(at(value, "program")), enumeration(ComputerRuntime.MemoryPolicy.class, at(value, "policy"))));
            if (version < 12 && (ProgramArchiveCodec.hasValueEquality(program) || pending.map(p -> ProgramArchiveCodec.hasValueEquality(p.program())).orElse(false)))
                throw ArchiveException.invalid("Value equality requires Computer archive schema 12");
            if (version < 11 && (ProgramArchiveCodec.hasDistinct(program) || pending.map(p -> ProgramArchiveCodec.hasDistinct(p.program())).orElse(false)))
                throw ArchiveException.invalid("Distinct requires Computer archive schema 11");
			Optional<ComputerRuntime.DeliveryState> delivery = version < 3 ? Optional.empty() : readOptional(at(data, "delivery"), value ->
				new ComputerRuntime.DeliveryState(UUID.fromString(string(at(value, "event"))), index(at(value, "acknowledged")),
					version < 4 ? 0 : number(at(value, "sequenceBase"))));
			long nextSequence = version < 4 ? delivery.map(value -> (long) program.program().sends().size()).orElse(0L) : number(at(data, "nextSequence"));
			return new ComputerRuntime.Snapshot(UUID.fromString(string(at(data, "id"))), program,
				readMachine(at(data, "machine"), version), pending,
				readOptional(at(data, "installationDiagnostic"), ComputerNbtCodec::readDiagnostic),
				version == 1 ? Optional.empty() : readOptional(at(data, "periodic"), ComputerNbtCodec::readPeriodic),
				delivery, version < 4 ? MailboxState.empty() : MailboxArchiveCodec.decode(at(data, "mailbox")), nextSequence,
				version < 5 ? Optional.empty() : readOptional(at(data, "activeEntry"), ArchiveData::string),
				version < 6 ? RpcState.empty() : RpcArchiveCodec.decode(at(data, "rpc")));
		} catch (ComputationException | IllegalArgumentException invalid) {
			if (invalid instanceof ArchiveException archive) throw archive;
			throw ArchiveException.invalid("Invalid computer archive: " + invalid.getMessage());
		}
	}
	private static DataValue periodic(PeriodicSchedule schedule) {
		return obj("interval_ticks", schedule.intervalTicks(), "remaining_ticks", schedule.remainingTicks(),
			"enabled", schedule.enabled(), "inputs", schedule.inputs());
	}
	private static PeriodicSchedule readPeriodic(DataValue value) {
		return new PeriodicSchedule(number(at(value, "interval_ticks")), number(at(value, "remaining_ticks")),
			bool(at(value, "enabled")), record(at(value, "inputs")));
	}
	private static DataValue machine(MachineCheckpoint state) {
		return obj("memory", state.memory(), "status", state.status().name(), "paused", state.paused(),
			"work", state.workUnits(), "arena", state.arenaUnits(), "output", optional(state.output(), value -> value),
			"diagnostic", optional(state.diagnostic(), ComputerNbtCodec::diagnostic), "frames", sequence(state.frames(), ComputerNbtCodec::frame));
	}
	private static MachineCheckpoint readMachine(DataValue value, long version) {
		return new MachineCheckpoint(record(at(value, "memory")), enumeration(ComputationMachine.Status.class, at(value, "status")),
			bool(at(value, "paused")), number(at(value, "work")), number(at(value, "arena")),
			readOptional(at(value, "output"), ArchiveData::record), readOptional(at(value, "diagnostic"), ComputerNbtCodec::readDiagnostic),
			list(at(value, "frames")).stream().map(frame -> readFrame(frame, version)).toList());
	}
	private static DataValue frame(MachineCheckpoint.FrameState frame) {
		return switch (frame) {
			case MachineCheckpoint.GraphState graph -> obj("kind", "graph", "module", optional(graph.module(), GraphArchiveCodec::key),
				"cursor", graph.cursor(), "inputs", graph.inputs(), "values", graph.values());
			case MachineCheckpoint.LoopState loop -> obj("kind", "loop", "index", loop.index(), "results", loop.results(), "returned", optional(loop.returned(), value -> value));
			case MachineCheckpoint.SortState sort -> obj("kind", "sort", "index", sort.index(), "keys", sort.keys(),
				"returned", optional(sort.returned(), value -> value), "progress", optional(sort.progress(), value -> value));
			case MachineCheckpoint.FoldState fold -> obj("kind", "fold", "index", fold.index(), "accumulator", fold.accumulator(),
				"returned", optional(fold.returned(), value -> value));
			case MachineCheckpoint.ValueEqualityState equality -> obj("kind", "value_equals", "progress", equality.progress());
            case MachineCheckpoint.DistinctState distinct -> obj("kind", "distinct", "progress", distinct.progress());
            case MachineCheckpoint.TextState text -> obj("kind","text","progress",text.progress());
			case MachineCheckpoint.ListState list -> obj("kind", "list", "index", list.index());
            case MachineCheckpoint.CommitState commit -> obj("kind", "commit", "cursor", commit.cursor(), "result", commit.result());
		};
	}
	private static MachineCheckpoint.FrameState readFrame(DataValue value, long version) {
		return switch (string(at(value, "kind"))) {
			case "graph" -> new MachineCheckpoint.GraphState(readOptional(at(value, "module"), GraphArchiveCodec::readKey), index(at(value, "cursor")), record(at(value, "inputs")), record(at(value, "values")));
			case "loop" -> new MachineCheckpoint.LoopState(index(at(value, "index")), new DataValue.ListValue(list(at(value, "results"))), readOptional(at(value, "returned"), ArchiveData::record));
			case "sort" -> {
				if (version < 9) throw ArchiveException.invalid("Sort frames are unavailable in this archive schema");
				yield new MachineCheckpoint.SortState(index(at(value, "index")), new DataValue.ListValue(list(at(value, "keys"))),
					readOptional(at(value, "returned"), ArchiveData::record), readOptional(at(value, "progress"), ArchiveData::record));
			}
			case "fold" -> {
				if (version < 7) throw ArchiveException.invalid("Fold frames are unavailable in this archive schema");
				yield new MachineCheckpoint.FoldState(index(at(value, "index")), at(value, "accumulator"),
					readOptional(at(value, "returned"), ArchiveData::record));
			}
			case "list" -> {
				if (version < 10) throw ArchiveException.invalid("List frames are unavailable in this archive schema");
				yield new MachineCheckpoint.ListState(index(at(value, "index")));
			}
			case "value_equals" -> {
                if (version < 12) throw ArchiveException.invalid("Value equality frames are unavailable in this archive schema");
                if (!record(value).fields().keySet().equals(Set.of("kind", "progress"))) throw ArchiveException.invalid("Invalid value equality frame fields");
                yield new MachineCheckpoint.ValueEqualityState(record(at(value, "progress")));
            }
            case "distinct" -> {
                if (version < 11) throw ArchiveException.invalid("Distinct frames are unavailable in this archive schema");
                yield new MachineCheckpoint.DistinctState(record(at(value, "progress")));
            }
            case "text" -> {
                if(version<8)throw ArchiveException.invalid("Text frames are unavailable in this archive schema");
                yield new MachineCheckpoint.TextState(record(at(value,"progress")));
            }
            case "commit" -> new MachineCheckpoint.CommitState(index(at(value, "cursor")), record(at(value, "result")));
			default -> throw ArchiveException.invalid("Unknown execution frame kind");
		};
	}
	private static DataValue diagnostic(ComputationMachine.Diagnostic diagnostic) {
		return obj("code", diagnostic.code().name(), "location", diagnostic.location(), "message", diagnostic.message());
	}
	private static ComputationMachine.Diagnostic readDiagnostic(DataValue value) {
		return new ComputationMachine.Diagnostic(enumeration(ComputationException.Code.class, at(value, "code")), string(at(value, "location")), string(at(value, "message")));
	}
	private static <T> DataValue optional(Optional<T> value, Function<T, DataValue> encode) { return value.map(encode).orElse(DataValue.NullValue.INSTANCE); }
	private static <T> Optional<T> readOptional(DataValue value, Function<DataValue, T> decode) {
		return value == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(decode.apply(value));
	}
}
