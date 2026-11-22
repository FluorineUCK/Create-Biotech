package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Thread-confined durable computer owner, independent of worlds and production tasks.
 * Only completely encodable states are published. Restore failures and unsaveable slices
 * remain suspended until limits change or explicit read replaces the owned save data.
 * Availability/unload never changes the persisted debugger pause or replays an event.
 */
public final class ComputerBlockHost {
	public enum Code { INVALID_SAVE, RESOURCE_LIMIT, HARDWARE_MISMATCH, UNAVAILABLE, NO_PROGRAM }
	public record Diagnostic(Code code, String message) {}
	public static final class HostException extends IllegalStateException {
		private final Code code;
		private HostException(Diagnostic diagnostic) { super(diagnostic.message()); code = diagnostic.code(); }
		public Code code() { return code; }
	}
	private static final Diagnostic UNAVAILABLE = new Diagnostic(Code.UNAVAILABLE, "Computer has no available physical owner");
	private final Runnable markChanged;
	private final com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice serial;
	private final java.util.function.Consumer<String> rendezvous;
	private Tag durable;
	private ComputerRuntime.Snapshot published;
	private ComputerRuntime runtime;
	public void onLaunchRefused(java.util.function.BiConsumer<String, String> listener) {
		launchNotice = Objects.requireNonNull(listener);
		if (sequential != null) sequential.onLaunchRefused(listener);
	}
	private java.util.function.BiConsumer<String, String> launchNotice = (code, handler) -> {};
	private FlowBlockHost sequential;
	private FlowControl eventControl = FlowControl.INITIAL;
	private ComputerHostLimits limits;
	private Diagnostic failure;
	private Diagnostic notice;
	private boolean available;
	private boolean availabilityKnown;
	private boolean activityOpen;
	private boolean advancingActivity;
	private long activityTick, grantedWork, spentWork;

	public ComputerBlockHost(Runnable markChanged) { this(markChanged, pin -> {}); }
	/** Post-commit diagnostic notification; contains only the local pin, never transferred data. */
	public ComputerBlockHost(Runnable markChanged, java.util.function.Consumer<String> rendezvous) {
		this(markChanged, rendezvous, null);
	}
	public ComputerBlockHost(Runnable markChanged, java.util.function.Consumer<String> rendezvous,
		com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice serial) {
		this.markChanged = Objects.requireNonNull(markChanged); this.rendezvous = Objects.requireNonNull(rendezvous);
		this.serial = serial;
	}
	public Optional<FlowBlockHost.Saved> flowSnapshot() { return sequential == null ? Optional.empty() : sequential.snapshot(); }
    	public Optional<FlowBlockHost.Question> terminalQuestion() { return sequential == null ? Optional.empty() : sequential.question(); }
	public void answerTerminal(String token, String text, ComputerHostLimits limits, boolean available) {
		requireAvailable(limits, available);
		if (sequential == null) throw new IllegalArgumentException("No installed Scratch program");
		sequential.submitAnswer(token, text);
	}
    /** Bounded source inspection for the editor; not a validated runtime or maintenance capability. */
    Optional<FlowBlockHost.Source> flowSource() { return sequential == null ? Optional.empty() : sequential.source(); }
	public boolean sequentialMode() { return sequential != null; }
	/** Observed maintenance capability, not permission to execute or replace the installed program. */
	public boolean canResetResourceFailure() { return sequential != null && sequential.canResetResourceFailure(); }
	/** Trusted local event/task admission; a UUID is a local handle, never remote authority. */
	public UUID startFlowTask(ModuleDefinition.Key entry, DataValue.RecordValue arguments, ComputerHostLimits limits, boolean available) {
		requireAvailable(limits, available);
		if (sequential == null) throw new HostException(new Diagnostic(Code.NO_PROGRAM, "No installed flow"));
		return sequential.startTask(entry, arguments);
	}
    public void updateTerminalKeys(UUID source, long bits) {
        if (sequential != null) sequential.updateTerminalKeys(source, bits);
    }
    String admitFisherEvent(String entry, ComputerHostLimits limits, boolean available) {
        return prepare(limits, available) && sequential != null ? sequential.fisherEvent(entry) : "unavailable";
    }
    public String pressTerminalKey(String key,ComputerHostLimits limits,boolean available) {
        requireAvailable(limits,available);return sequential==null?"no_listener":sequential.pressTerminalKey(key);
    }
	public Optional<FlowRuntime.Checkpoint> flowTask(UUID task) {
		return flowSnapshot().flatMap(s -> s.tasks().contexts().stream().filter(t -> t.flow().equals(task)).findFirst());
	}
	/** A completed result stays charged until explicitly claimed. No live cancellation or receipt deletion. */
	public FlowRuntime.Checkpoint claimFlowTask(UUID task, ComputerHostLimits limits, boolean available) {
		requireAvailable(limits, available);
		if (sequential == null) throw new HostException(new Diagnostic(Code.NO_PROGRAM, "No installed flow"));
		return sequential.claimTask(task);
	}
	/** Trusted connection coordinator only; callers must validate a real, explicitly configured wire. */
	public int reclaimCompletedXBus(java.util.function.Predicate<XBusReceipt> completed) {
		return sequential == null ? 0 : sequential.reclaimCompletedXBus(completed);
	}
	/** Trusted persistent-history maintenance, not a program instruction or permission to exchange. */
	public int maintainXBusReceipts(Object witness, java.util.function.Predicate<XBusReceipt> completed,
		ComputerHostLimits limits, boolean available) {
		if (sequential == null) return 0;
		prepare(limits, available);
		return sequential.maintainXBusReceipts(witness, completed);
	}
	/** Trusted connection coordinator only; callers must validate a real, explicitly configured wire. */
	public XBusExchange.Status exchange(String pin, ComputerHostLimits limits, boolean available,
		ComputerBlockHost peer, String peerPin, ComputerHostLimits peerLimits, boolean peerAvailable) {
		Objects.requireNonNull(peer);
		if (!prepare(limits, available) || !peer.prepare(peerLimits, peerAvailable)) return XBusExchange.Status.UNAVAILABLE;
		if (sequential == null || peer.sequential == null) return XBusExchange.Status.NOT_READY;
		var result = sequential.exchange(pin, peer.sequential, peerPin);
		if (result == XBusExchange.Status.EXCHANGED) { notifyRendezvous(pin); peer.notifyRendezvous(peerPin); }
		return result;
	}
	private void notifyRendezvous(String pin) {
		try { rendezvous.accept(pin); }
		catch (RuntimeException | StackOverflowError ignored) { /* Diagnostics must not fail an already committed transfer. */ }
	}
	/** Trusted coordinator only. Each binding retains that Computer's own hardware and availability. */
	public record XBusBinding(ComputerBlockHost host, String pin, ComputerHostLimits limits, boolean available) {
		public XBusBinding { Objects.requireNonNull(host); Objects.requireNonNull(pin); Objects.requireNonNull(limits); }
	}
	public static XBusExchange.Status exchangeGroup(java.util.List<XBusBinding> bindings, XBusBinding selectedWriter) {
		return prepareExchangeGroup(bindings, selectedWriter).commit();
	}
	public static final class XBusGroupExchange {
		private final FlowBlockHost.GroupExchange prepared;
		private final java.util.Map<ComputerBlockHost, FlowBlockHost> owners;
		private final java.util.List<XBusBinding> bindings;
		private XBusGroupExchange(FlowBlockHost.GroupExchange prepared, java.util.List<XBusBinding> bindings) {
			this.prepared = prepared; this.bindings = java.util.List.copyOf(bindings); owners = new java.util.IdentityHashMap<>();
			bindings.forEach(b -> owners.put(b.host(), b.host().sequential));
		}
		private static XBusGroupExchange rejected(XBusExchange.Status status) {
			return new XBusGroupExchange(FlowBlockHost.GroupExchange.rejected(status), java.util.List.of());
		}
		public XBusExchange.Status status() { return prepared.status(); }
		public Optional<XBusReceipt> receipt() { return prepared.receipt(); }
		public XBusExchange.Status commit() {
			for (var owner : owners.entrySet()) if (owner.getKey().sequential != owner.getValue()) return XBusExchange.Status.STALE;
			var result = prepared.commit();
			if (result == XBusExchange.Status.EXCHANGED) for (var binding : bindings.stream().distinct().toList())
				if (prepared.completes(new FlowBlockHost.Binding(owners.get(binding.host()), binding.pin())))
					binding.host().notifyRendezvous(binding.pin());
			return result;
		}
	}
	public static XBusGroupExchange prepareExchangeGroup(java.util.List<XBusBinding> bindings, XBusBinding selectedWriter) {
		return prepareGroup(bindings, selectedWriter, false);
	}
	/** Trusted whole-component observation; preserves every sender and data reader. */
	public static XBusGroupExchange prepareReadableGroup(java.util.List<XBusBinding> bindings, XBusBinding selectedWriter) {
		return prepareGroup(bindings, selectedWriter, true);
	}
	private static XBusGroupExchange prepareGroup(java.util.List<XBusBinding> bindings, XBusBinding selectedWriter, boolean probesOnly) {
		bindings = java.util.List.copyOf(bindings);
		if (!bindings.contains(selectedWriter)) return XBusGroupExchange.rejected(XBusExchange.Status.NOT_FOUND);
		var seen = new java.util.IdentityHashMap<ComputerBlockHost, ComputerHostLimits>();
		for (var binding : bindings) {
			var old = seen.putIfAbsent(binding.host(), binding.limits());
			if (old != null && !old.equals(binding.limits())) return XBusGroupExchange.rejected(XBusExchange.Status.STALE);
			if (!binding.host().prepare(binding.limits(), binding.available())) return XBusGroupExchange.rejected(XBusExchange.Status.UNAVAILABLE);
			if (binding.host().sequential == null) return XBusGroupExchange.rejected(XBusExchange.Status.NOT_READY);
			if (!binding.limits().pins().hasXBus(binding.pin())) return XBusGroupExchange.rejected(XBusExchange.Status.PORT_MISMATCH);
		}
		var pins = bindings.stream().map(b -> new FlowBlockHost.Binding(b.host().sequential, b.pin())).toList();
		var writer = new FlowBlockHost.Binding(selectedWriter.host().sequential, selectedWriter.pin());
		var result = probesOnly ? FlowBlockHost.prepareReadableGroup(pins, writer) : FlowBlockHost.prepareExchangeGroup(pins, writer);
		return new XBusGroupExchange(result, bindings);
	}
    /** Trusted network barrier: exact retired operations only, with independent owner budgets. */
	public static XBusGroupExchange prepareCancellation(java.util.List<XBusBinding> bindings, XBusCancellation witness) {
		for (var binding : bindings) {
			if (!binding.host().prepare(binding.limits(), binding.available())) return XBusGroupExchange.rejected(XBusExchange.Status.UNAVAILABLE);
			if (binding.host().sequential == null) return XBusGroupExchange.rejected(XBusExchange.Status.NOT_READY);
			if (!binding.limits().pins().hasXBus(binding.pin())) return XBusGroupExchange.rejected(XBusExchange.Status.PORT_MISMATCH);
		}
		return new XBusGroupExchange(FlowBlockHost.prepareCancellation(bindings.stream().map(b -> new FlowBlockHost.Binding(b.host().sequential, b.pin())).toList(), witness), bindings);
	}
	/** Trusted all-party network barrier only; PAUSED means this local operation durably finished with failure. */
	public XBusExchange.Status rejectPausedXBus(String pin, ComputerHostLimits limits) {
		if (!prepare(limits, true)) return XBusExchange.Status.UNAVAILABLE;
		return sequential == null ? XBusExchange.Status.NOT_READY : sequential.rejectPausedXBus(pin);
	}
	/** Program-facing installation; preparation never grants authority over current target budgets. */
	public ComputerRuntime.InstallResult installProgram(PreparedProgram program, ComputerRuntime.MemoryPolicy policy,
		ComputerHostLimits limits, boolean available) {
		Objects.requireNonNull(program); Objects.requireNonNull(policy);
		if (program.installationPolicy() == PreparedProgram.InstallationPolicy.RESET_PAUSED
			&& policy != ComputerRuntime.MemoryPolicy.RESET)
			throw new IllegalArgumentException("Installing a sequential program requires an explicit reset; it starts paused");
		program.validateTarget(limits);
		if (program.installationPolicy() == PreparedProgram.InstallationPolicy.LEGACY_EVENT)
			return install(program.document().archive(), policy, limits, available);
		installFlow(program.control(), program.document().flow().orElseThrow().initial(), limits, available);
		return ComputerRuntime.InstallResult.APPLIED;
	}
	/** @deprecated Internal legacy IR compatibility seam; extensions should prepare a document and installProgram. */
	@Deprecated
	public void installFlow(FlowProgram program, DataValue.RecordValue inputs, ComputerHostLimits limits, boolean available) {
		requireMaintenance(limits, available);
		if (sequential != null ? !sequential.replaceable() : legacyWorkPending())
			throw new ComputationException(ComputationException.Code.BUSY, "flow", "Finish or explicitly reset in-flight work before changing program mode");
		UUID id = sequential != null ? sequential.snapshot().orElseThrow().state().computer() : runtime != null ? runtime.id() : UUID.randomUUID();
		var previous = sequential == null ? eventControl : sequential.snapshot().orElseThrow().control();
		var control = sequential == null && previous.pristine() ? FlowControl.INITIAL : previous.nextGeneration();
		var candidate = FlowBlockHost.installed(markChanged, id, program, inputs, limits, control, serial,
			sequential == null ? java.util.List.of() : sequential.snapshot().orElseThrow().cancellations());
		if (runtime != null) runtime.unload();
        candidate.onLaunchRefused(launchNotice);
		sequential = candidate; eventControl = FlowControl.INITIAL; runtime = null; published = null; durable = null; failure = null; notice = null;
		markChanged.run();
	}
	private boolean legacyWorkPending() {
		return published != null && (published.machine().status() == ComputationMachine.Status.RUNNING || published.pending().isPresent()
			|| !published.mailbox().queued().isEmpty() || !published.rpc().pending().isEmpty() || !published.rpc().staged().isEmpty()
			|| published.periodic().filter(PeriodicSchedule::enabled).isPresent() || runtime.outbound().isPresent());
	}

	/** Retain even unrecognized tags verbatim; decoding is deferred until the owner is available. */
	public void read(@Nullable Tag saved) {
		if (runtime != null) runtime.unload();
		if (sequential != null) sequential.unload();
		sequential = FlowBlockNbtCodec.claims(saved) ? new FlowBlockHost(markChanged, (CompoundTag) saved, serial) : null;
        if (sequential != null) sequential.onLaunchRefused(launchNotice);
		durable = sequential != null || saved == null ? null : saved.copy();
		runtime = null; published = null; eventControl = FlowControl.INITIAL; limits = null; failure = null; notice = null;
		available = false; availabilityKnown = false;
	}

	/** The block save callback is deliberately only a defensive copy, never compilation/encoding. */
	public @Nullable Tag write() { return sequential != null ? sequential.write() : durable == null ? null : durable.copy(); }
	public Optional<ComputerRuntime.Snapshot> snapshot() { return Optional.ofNullable(published); }
	public java.util.Set<String> xbusStatusPins() { return sequential == null ? java.util.Set.of() : sequential.xbusStatusPins(); }
	public java.util.List<com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowDevices.Use> roundSignalReads() {
		return sequential == null ? java.util.List.of() : sequential.roundSignalReads();
	}
	public Optional<Diagnostic> diagnostic() {
		if (sequential != null && !(availabilityKnown && !available)) return sequential.diagnostic();
		return Optional.ofNullable(availabilityKnown && !available ? UNAVAILABLE : failure != null ? failure : notice);
	}
	public boolean loaded() { return sequential != null ? sequential.loaded() : available && failure == null && runtime != null && runtime.loaded(); }
	/** Restore/revalidate without advancing execution, so sibling metadata can be checked before a transition. */
	boolean synchronize(ComputerHostLimits limits, boolean available) { return prepare(limits, available); }

    /** Account an external local RAM reservation before accepting any file bytes. */
    boolean fitsMemory(ComputerHostLimits reduced) {
        if (sequential != null) return sequential.fitsMemory(reduced);
        if (published == null) return durable == null;
        try {
            var hardware = eventHardware(reduced, eventControl);
            var state = published.machine();
            if (state.status() == ComputationMachine.Status.IDLE) {
                var usage = published.program().compile(hardware).usage();
                if (state.memory().storageUnits() > hardware.memoryUnits() - usage.constantUnits() - usage.initialMemoryUnits())
                    return false;
            }
            if (state.arenaUnits() > hardware.memoryUnits()) return false;
            ComputerRuntime.restore(published, hardware, reduced.mailbox());
            return true;
        } catch (ComputationException | ArchiveException unavailable) {
            return false;
        }
    }

	public void unload() {
		available = false; availabilityKnown = true;
		if (runtime != null) runtime.unload();
		if (sequential != null) sequential.unload();
	}

	public void tick(ComputerHostLimits limits, boolean available) {
		tick(limits, available, limits.pins().unconnectedSignals());
	}
	/** Trusted world coordinator entrypoints; they expose no member RAM or program mutation. */
	public boolean beginFlowTick(long tick, ComputerHostLimits limits, boolean available) {
		if (advancingActivity || !prepare(limits, available) || sequential == null || !openActivity(tick)) return false;
		advancingActivity = true;
		try {
			if (sequential.beginActivityTick()) return true;
			exhaustActivity(); return false;
		} finally { advancingActivity = false; }
	}
	public long advanceFlowRound(long tick, java.util.Map<String, Integer> inputs, ComputerHostLimits limits, boolean available) {
		if (advancingActivity || !prepare(limits, available) || sequential == null || !activityOpen || activityTick != tick || remainingWork() == 0) return 0;
		advancingActivity = true;
		try {
			long spent = sequential.advanceRound(inputs);
			spentWork += spent;
			if (!sequential.loaded()) exhaustActivity();
			return spent;
		} catch (RuntimeException failure) { exhaustActivity(); throw failure; }
		finally { advancingActivity = false; }
	}
	private boolean openActivity(long tick) {
		if (activityOpen && activityTick == tick) return false;
		activityOpen = true; activityTick = tick;
		grantedWork = limits.hardware().workPerSlice(); spentWork = 0;
		return true;
	}
	private long remainingWork() {
		// Hardware changes may tighten this activity, never allocate a second allowance.
		grantedWork = Math.min(grantedWork, limits.hardware().workPerSlice());
		return Math.max(0, grantedWork - spentWork);
	}
	private void exhaustActivity() { spentWork = Math.max(spentWork, grantedWork); }
	/** Standalone lifecycle seam: each call represents a new activity tick. World owners use the timed overload. */
	public void tick(ComputerHostLimits limits, boolean available, java.util.Map<String, Integer> signals) {
		tick(activityOpen ? activityTick + 1 : 0, limits, available, signals);
	}
	/** The physical owner supplies one activity identity and a frozen input sample; unavailable pins are not zero. */
	public void tick(long tick, ComputerHostLimits limits, boolean available, java.util.Map<String, Integer> signals) {
		if (advancingActivity || !prepare(limits, available) || sequential == null && runtime == null || !openActivity(tick)) return;
		advancingActivity = true;
		try {
			if (sequential != null) {
				spentWork += sequential.tick(remainingWork(), signals);
				if (!sequential.loaded()) exhaustActivity();
			} else runEventActivity();
		} catch (RuntimeException failure) { exhaustActivity(); throw failure; }
		finally { advancingActivity = false; }
	}
	private void runEventActivity() {
		var state = published.machine();
		boolean pendingAtBoundary = published.pending().isPresent() && state.status() != ComputationMachine.Status.RUNNING;
		boolean timer = published.periodic().map(PeriodicSchedule::enabled).orElse(false);
		boolean queued = !published.mailbox().queued().isEmpty() && state.status() != ComputationMachine.Status.FAILED;
		if (!pendingAtBoundary && (state.paused() || state.status() != ComputationMachine.Status.RUNNING && !timer && !queued)) return;
		try { mutate(() -> {
			if (!runtime.beginActivityTick()) { exhaustActivity(); return null; }
			long before = runtime.report().workUnits();
			runtime.runSlice(remainingWork());
			spentWork += runtime.report().workUnits() - before;
			return null;
		}); }
		catch (HostException ignored) { exhaustActivity(); /* The durable checkpoint and diagnostic are already retained. */ }
		catch (ComputationException error) { exhaustActivity(); suspend(code(error), error.getMessage()); }
	}

	public ComputerRuntime.InstallResult install(ProgramArchive archive, ComputerRuntime.MemoryPolicy policy,
		ComputerHostLimits limits, boolean available) {
		Objects.requireNonNull(archive); Objects.requireNonNull(policy);
        FlowPins.validateLegacyInstructionSets(archive, limits);
		requireMaintenance(limits, available);
		if (sequential != null && (!sequential.replaceable() || policy != ComputerRuntime.MemoryPolicy.RESET))
			throw new ComputationException(ComputationException.Code.BUSY, "flow", "Switching from a flow requires a safe boundary and explicit RESET policy");
		if (sequential != null && !sequential.snapshot().orElseThrow().cancellations().isEmpty())
			throw new ComputationException(ComputationException.Code.BUSY, "flow", "Resolve retained XBus cancellations before switching to the legacy event owner");
		if (runtime != null) return mutate(() -> runtime.requestInstall(archive, policy));
		var control = sequential == null ? eventControl : sequential.snapshot().orElseThrow().control();
		ComputerRuntime candidate;
		try {
			candidate = new ComputerRuntime(sequential == null ? UUID.randomUUID() : sequential.snapshot().orElseThrow().state().computer(), archive, eventHardware(limits, control), limits.mailbox());
		} catch (ComputationException error) {
			notice = new Diagnostic(code(error), error.getMessage()); throw error;
		}
		try {
			var snapshot = candidate.snapshot();
			var encoded = EventHostNbtCodec.encode(snapshot, control, limits.archive());
			if (sequential != null) sequential.unload();
			sequential = null; eventControl = control; runtime = candidate; durable = encoded; published = snapshot; failure = null; notice = null;
		} catch (ArchiveException | ComputationException error) {
			suspend(saveFailureCode(error), error.getMessage()); throw new HostException(failure);
		}
		markChanged.run();
		return ComputerRuntime.InstallResult.APPLIED;
	}

	public void begin(DataValue.RecordValue inputs, ComputerHostLimits limits, boolean available) {
		requireProgram(limits, available); mutate(() -> { runtime.begin(inputs); return null; });
	}
	/** Speculative UI admission: expected rejection never invokes the owner's fail-stop mutator. */
	public void beginLocal(String entry, DataValue.RecordValue inputs, ComputerHostLimits limits, boolean available) {
		requireProgram(limits, available);
		var candidate = ComputerRuntime.restore(published, eventHardware(limits, eventControl), limits.mailbox()); candidate.load();
		candidate.beginLocal(entry, inputs);
		var snapshot = candidate.snapshot(); var encoded = EventHostNbtCodec.encode(snapshot, eventControl, limits.archive());
		runtime = candidate; published = snapshot; durable = encoded; notice = null; markChanged.run();
	}
	public void step(ComputerHostLimits limits, boolean available) {
		step(limits, available, limits.pins().unconnectedSignals());
	}
	public void step(ComputerHostLimits limits, boolean available, java.util.Map<String, Integer> signals) {
		if (sequential != null) { requireAvailable(limits, available); changeFlow(() -> sequential.step(signals)); return; }
		requireProgram(limits, available); mutate(() -> { runtime.step(); return null; });
	}
	public void setPaused(boolean paused, ComputerHostLimits limits, boolean available) {
		if (sequential != null) { requireAvailable(limits, available); changeFlow(() -> sequential.setPaused(paused)); return; }
		requireProgram(limits, available); mutate(() -> { runtime.setPaused(paused); return null; });
	}
	public void reset(ComputerHostLimits limits, boolean available) {
		if (sequential != null && !prepare(limits, available) && sequential.canResetResourceFailure()) {
			changeFlow(sequential::resetResourceFailure); return;
		}
		if (sequential != null) { requireMaintenance(limits, available); changeFlow(sequential::reset); return; }
		requireProgram(limits, available); mutate(() -> { runtime.reset(); return null; });
	}
	public void configurePeriodic(long intervalTicks, DataValue.RecordValue inputs, ComputerHostLimits limits, boolean available) {
		requireProgram(limits, available); mutate(() -> { runtime.configurePeriodic(intervalTicks, inputs); return null; });
	}
	public void disablePeriodic(ComputerHostLimits limits, boolean available) {
		requireProgram(limits, available); mutate(() -> { runtime.disablePeriodic(); return null; });
	}
	public void clearPeriodic(ComputerHostLimits limits, boolean available) {
		requireProgram(limits, available); mutate(() -> { runtime.clearPeriodic(); return null; });
	}
	/** An authorized observation may restore the owner, but never advances its timer or event. */
	public ComputerRuntime.Snapshot observe(ComputerHostLimits limits, boolean available) {
		requireProgram(limits, available); return published;
	}
	/** Transport polling only sees durable committed output and never advances execution or a timer. */
	public Optional<OutboundMessage> outbound(ComputerHostLimits limits, boolean available) {
		if (!prepare(limits, available) || runtime == null || runtime.paused()) return Optional.empty();
		return runtime.outbound();
	}
	/** Persist the accepted prefix before reporting success to a transport. No remote callback runs here. */
	public boolean acknowledge(UUID eventId, int ordinal, ComputerHostLimits limits, boolean available) {
		requireProgram(limits, available);
		if (runtime.paused()) return false;
		return mutate(() -> runtime.acknowledge(eventId, ordinal));
	}
	/** Durable admission without suspending valid owned execution when external input does not fit. */
	public AdmissionStatus receive(MessageEnvelope envelope, ComputerHostLimits limits, boolean available) {
		if (!prepare(limits, available)) return AdmissionStatus.UNAVAILABLE;
		if (sequential != null) return AdmissionStatus.UNSUPPORTED;
		if (runtime == null) return AdmissionStatus.NO_PROGRAM;
		// Admission is speculative, unlike an owner's execution slice. Never apply mutate's fail-stop
		// policy to rejected external data: the existing event and accepted queue must still drain.
		var candidate = ComputerRuntime.restore(published, eventHardware(limits, eventControl), limits.mailbox());
		candidate.load();
		var result = candidate.receive(envelope);
		if (result != AdmissionStatus.ACCEPTED) return result;
		var snapshot = candidate.snapshot();
		CompoundTag encoded;
		try { encoded = EventHostNbtCodec.encode(snapshot, eventControl, limits.archive()); }
		catch (ArchiveException error) {
			return error.code() == ArchiveException.Code.LIMIT_EXCEEDED ? AdmissionStatus.ARCHIVE_LIMIT : AdmissionStatus.UNAVAILABLE;
		}
		runtime = candidate; published = snapshot; durable = encoded; notice = null;
		markChanged.run();
		return AdmissionStatus.ACCEPTED;
	}

	/** Opt-in rendezvous admission. Publish an encodable receiver frame before acknowledging its identity. */
	public ReceptionStatus receiveReady(UUID expectedReceiver, MessageEnvelope envelope, ComputerHostLimits limits, boolean available) {
		Objects.requireNonNull(expectedReceiver); Objects.requireNonNull(envelope);
		if (!prepare(limits, available)) return ReceptionStatus.UNAVAILABLE;
		if (sequential != null) return ReceptionStatus.UNSUPPORTED;
		if (runtime == null) return ReceptionStatus.NO_PROGRAM;
		if (!runtime.id().equals(expectedReceiver)) return ReceptionStatus.TARGET_CHANGED;
		var candidate = ComputerRuntime.restore(published, eventHardware(limits, eventControl), limits.mailbox());
		candidate.load();
		var result = candidate.receiveReady(envelope);
		if (result != ReceptionStatus.RECEIVED) return result;
		var snapshot = candidate.snapshot();
		if (snapshot.equals(published)) return result;
		CompoundTag encoded;
		try { encoded = EventHostNbtCodec.encode(snapshot, eventControl, limits.archive()); }
		catch (ArchiveException error) {
			return error.code() == ArchiveException.Code.LIMIT_EXCEEDED ? ReceptionStatus.ARCHIVE_LIMIT : ReceptionStatus.UNAVAILABLE;
		}
		runtime = candidate; published = snapshot; durable = encoded; notice = null;
		markChanged.run();
		return ReceptionStatus.RECEIVED;
	}

	private void requireProgram(ComputerHostLimits limits, boolean available) {
		requireAvailable(limits, available);
		if (runtime == null) {
			notice = new Diagnostic(Code.NO_PROGRAM, sequential == null ? "No program is installed" : "This operation requires an event program, not a sequential flow"); throw new HostException(notice);
		}
	}
	private void requireAvailable(ComputerHostLimits limits, boolean available) {
		if (!prepare(limits, available)) throw new HostException(diagnostic().orElseThrow());
	}
	/** Missing pins do not prevent an explicit reset/repair of an otherwise valid save. Never used to execute or communicate. */
	private void requireMaintenance(ComputerHostLimits limits, boolean available) {
		if (!prepare(limits, available) && !(available && sequential != null && sequential.hardwareMismatch()))
			throw new HostException(diagnostic().orElseThrow());
	}

	/** Revalidation happens exactly once per supplied limits value, including failed attempts. */
	private boolean prepare(ComputerHostLimits requested, boolean available) {
		Objects.requireNonNull(requested);
		if (activityOpen) grantedWork = Math.min(grantedWork, requested.hardware().workPerSlice());
		this.available = available; availabilityKnown = true;
		if (sequential != null) { limits = requested; return sequential.synchronize(requested, available); }
		if (!available) { if (runtime != null) runtime.unload(); return false; }
		if (!requested.equals(limits)) {
			if (runtime != null) runtime.unload();
			runtime = null; limits = requested; failure = null; notice = null;
		}
		if (failure != null) return false;
		if (runtime != null) { runtime.load(); return true; }
		if (durable == null) return true;
		try {
			if (!(durable instanceof CompoundTag compound)) {
				suspend(Code.INVALID_SAVE, "Computer archive must be an NBT compound"); return false;
			}
			var envelope = EventHostNbtCodec.decode(compound, limits.archive()); var saved = envelope.snapshot(); eventControl = envelope.control();
            FlowPins.validateLegacyInstructionSets(saved.program(), limits);
            saved.pending().ifPresent(pending -> FlowPins.validateLegacyInstructionSets(pending.program(), limits));
			if (saved.machine().arenaUnits() > eventHardware(limits, eventControl).memoryUnits() || idleMemoryExceedsCapacity(saved)) {
				suspend(Code.RESOURCE_LIMIT, "Saved execution exceeds current memory capacity"); return false;
			}
			var restored = ComputerRuntime.restore(saved, eventHardware(limits, eventControl), limits.mailbox());
			restored.load(); runtime = restored; published = saved;
			return true;
		} catch (FlowPins.Unavailable error) { suspend(Code.HARDWARE_MISMATCH, error.getMessage()); }
        catch (ArchiveException error) { suspend(code(error), error.getMessage()); }
		catch (ComputationException error) {
			// A previously validated exact checkpoint can fail revalidation only because hardware changed.
			var reason = published != null && error.code() == ComputationException.Code.INVALID_CHECKPOINT
				? Code.RESOURCE_LIMIT : code(error);
			suspend(reason, error.getMessage());
		} catch (IllegalArgumentException error) { suspend(Code.INVALID_SAVE, error.getMessage()); }
		return false;
	}

	/** Idle checkpoints have arena=0, but preserved RAM is still charged in addition to initial data. */
	private boolean idleMemoryExceedsCapacity(ComputerRuntime.Snapshot saved) {
		if (saved.machine().status() != ComputationMachine.Status.IDLE) return false;
		var hardware = eventHardware(limits, eventControl);
		var usage = saved.program().compile(hardware).usage();
		// The compiler has already established that constants + initial memory fit, so subtraction is safe.
		long remaining = hardware.memoryUnits() - usage.constantUnits() - usage.initialMemoryUnits();
		return saved.machine().memory().storageUnits() > remaining;
	}

	/** Commit the save before exposing either results or the owner's dirty callback. */
	private <T> T mutate(Supplier<T> operation) {
		var before = published;
		T result = null;
		ComputationException rejected = null;
		try { result = operation.get(); }
		catch (ComputationException error) { rejected = error; }
		boolean changed = false;
		try {
			var after = runtime.snapshot();
			if (!after.equals(before)) {
				var encoded = EventHostNbtCodec.encode(after, eventControl, limits.archive());
				durable = encoded; published = after; notice = null;
				changed = true;
			}
		} catch (ArchiveException | ComputationException error) {
			// Slow failure path: discard speculative output and rebuild the last durable continuation.
			runtime.unload();
			runtime = ComputerRuntime.restore(EventHostNbtCodec.decode((CompoundTag) durable, limits.archive()).snapshot(), eventHardware(limits, eventControl), limits.mailbox());
			suspend(saveFailureCode(error), error.getMessage());
			if (rejected != null) throw rejected;
			throw new HostException(failure);
		}
		if (changed) markChanged.run();
		if (rejected != null) {
			notice = new Diagnostic(code(rejected), rejected.getMessage()); throw rejected;
		}
		return result;
	}

	private void suspend(Code code, String message) {
		failure = new Diagnostic(code, message);
		if (runtime != null) runtime.unload();
	}
	private static ResourceLimits eventHardware(ComputerHostLimits limits, FlowControl control) {
		long memory = limits.hardware().memoryUnits() - control.storageUnits();
		if (memory < 0) throw new ComputationException(ComputationException.Code.MEMORY_LIMIT, "flow", "Retained protocol history exceeds memory capacity");
		return limits.hardware().withMemoryUnits(memory);
	}
	private void changeFlow(Runnable change) {
		try { change.run(); }
		catch (ArchiveException | ComputationException error) {
			if (sequential.diagnostic().isEmpty()) throw error;
			throw new HostException(sequential.diagnostic().orElseThrow());
		}
	}
	private static Code code(ArchiveException error) {
		return error.code() == ArchiveException.Code.LIMIT_EXCEEDED ? Code.RESOURCE_LIMIT : Code.INVALID_SAVE;
	}
	private static Code saveFailureCode(RuntimeException error) {
		return error instanceof ArchiveException archive ? code(archive) : code((ComputationException) error);
	}
	private static Code code(ComputationException error) {
		return switch (error.code()) {
			case PROGRAM_LIMIT, MEMORY_LIMIT -> Code.RESOURCE_LIMIT;
			default -> Code.INVALID_SAVE;
		};
	}
}
