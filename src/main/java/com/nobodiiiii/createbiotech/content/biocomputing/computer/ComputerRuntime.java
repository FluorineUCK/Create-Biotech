package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;

/** One computer's lifecycle, independent of production tasks, worlds and other computers. Thread-confined. */
public final class ComputerRuntime {
	public enum MemoryPolicy { PRESERVE, RESET }
	public enum InstallResult { APPLIED, QUEUED }
	public record PendingInstallation(ProgramArchive program, MemoryPolicy policy) {
		public PendingInstallation { Objects.requireNonNull(program); Objects.requireNonNull(policy); }
	}
	/** Prefix cursor over an event's send bindings, not a second copy of the output payloads. */
	public record DeliveryState(UUID eventId, int acknowledged, long sequenceBase) {
		public DeliveryState {
			Objects.requireNonNull(eventId);
			if (acknowledged < 0 || sequenceBase < 0) throw new IllegalArgumentException("Negative delivery cursor or sequence");
		}
		public DeliveryState(UUID eventId, int acknowledged) { this(eventId, acknowledged, 0); }
	}
	public record Snapshot(UUID id, ProgramArchive program, MachineCheckpoint machine,
		Optional<PendingInstallation> pending, Optional<ComputationMachine.Diagnostic> installationDiagnostic,
		Optional<PeriodicSchedule> periodic, Optional<DeliveryState> delivery, MailboxState mailbox, long nextSequence,
		Optional<String> activeEntry, RpcState rpc) {
		public Snapshot {
			Objects.requireNonNull(id); Objects.requireNonNull(program); Objects.requireNonNull(machine);
			Objects.requireNonNull(pending); Objects.requireNonNull(installationDiagnostic); Objects.requireNonNull(periodic);
			Objects.requireNonNull(delivery);
			Objects.requireNonNull(mailbox);
			Objects.requireNonNull(activeEntry);
			Objects.requireNonNull(rpc);
			if (nextSequence < 0) throw new IllegalArgumentException("Negative next sequence");
		}
		public Snapshot(UUID id, ProgramArchive program, MachineCheckpoint machine,
			Optional<PendingInstallation> pending, Optional<ComputationMachine.Diagnostic> installationDiagnostic,
			Optional<PeriodicSchedule> periodic, Optional<DeliveryState> delivery, MailboxState mailbox, long nextSequence,
			Optional<String> activeEntry) {
			this(id, program, machine, pending, installationDiagnostic, periodic, delivery, mailbox, nextSequence, activeEntry, RpcState.empty());
		}
		public Snapshot(UUID id, ProgramArchive program, MachineCheckpoint machine,
			Optional<PendingInstallation> pending, Optional<ComputationMachine.Diagnostic> installationDiagnostic,
			Optional<PeriodicSchedule> periodic, Optional<DeliveryState> delivery, MailboxState mailbox, long nextSequence) {
			this(id, program, machine, pending, installationDiagnostic, periodic, delivery, mailbox, nextSequence, Optional.empty());
		}
		public Snapshot(UUID id, ProgramArchive program, MachineCheckpoint machine,
			Optional<PendingInstallation> pending, Optional<ComputationMachine.Diagnostic> installationDiagnostic,
			Optional<PeriodicSchedule> periodic, Optional<DeliveryState> delivery) {
			this(id, program, machine, pending, installationDiagnostic, periodic, delivery, MailboxState.empty(),
				delivery.map(value -> Math.addExact(value.sequenceBase(), program.program().sends().size())).orElse(0L));
		}
		public Snapshot(UUID id, ProgramArchive program, MachineCheckpoint machine,
			Optional<PendingInstallation> pending, Optional<ComputationMachine.Diagnostic> installationDiagnostic,
			Optional<PeriodicSchedule> periodic) {
			this(id, program, machine, pending, installationDiagnostic, periodic, Optional.empty());
		}
		public Snapshot(UUID id, ProgramArchive program, MachineCheckpoint machine,
			Optional<PendingInstallation> pending, Optional<ComputationMachine.Diagnostic> installationDiagnostic) {
			this(id, program, machine, pending, installationDiagnostic, Optional.empty());
		}
	}
	private record Candidate(ProgramCompiler.InstalledProgram compiled, MemoryPolicy policy) {}
	private final UUID id;
	private final ResourceLimits hardware;
	private final MailboxLimits mailboxLimits;
	private ProgramArchive program;
	private ComputationMachine machine;
	private Candidate pending;
	private ComputationMachine.Diagnostic installationDiagnostic;
	private PeriodicSchedule periodic;
	private DeliveryState delivery;
	private MailboxState mailbox = MailboxState.empty();
	private long nextSequence;
	private String activeEntry;
	private RpcState rpc = RpcState.empty();
	private boolean loaded = true;

	public ComputerRuntime(UUID id, ProgramArchive program, ResourceLimits hardware) {
		this(id, program, hardware, MailboxLimits.DEFAULT);
	}
	public ComputerRuntime(UUID id, ProgramArchive program, ResourceLimits hardware, MailboxLimits mailboxLimits) {
		this.id = Objects.requireNonNull(id);
		this.hardware = Objects.requireNonNull(hardware);
		this.mailboxLimits = Objects.requireNonNull(mailboxLimits);
		var compiled = program.compile(hardware);
		this.program = ProgramArchive.from(compiled);
		machine = new ComputationMachine(compiled);
	}
	public UUID id() { return id; }
	public boolean loaded() { return loaded; }
	public boolean paused() { return machine.paused(); }
	public void setPaused(boolean paused) { machine.setPaused(paused); }
	public DataValue.RecordValue memory() { return machine.memory(); }
	public ComputationMachine.Report report() { return machine.report(); }
	/** Inert inspection, including while paused/unloaded. The physical host separately gates transport. */
	public Optional<OutboundMessage> outbound() {
		if (!hasOutbound()) return Optional.empty();
		var send = activeDefinition().sends().get(delivery.acknowledged());
		var output = machine.report().output().orElseThrow();
		var correlation = new RequestCorrelation(id, delivery.eventId(), delivery.acknowledged());
		OutboundProtocol protocol = switch (send.dispatch()) {
			case MESSAGE -> OutboundProtocol.Message.INSTANCE;
			case REQUEST -> new OutboundProtocol.Request(correlation);
			case REPLY -> new OutboundProtocol.Reply(rpc.request().orElseThrow().correlation());
		};
		String endpoint = send.dispatch() == ProgramDefinition.Dispatch.REPLY
			? rpc.request().orElseThrow().returnEndpoint() : ((DataValue.TextValue) output.field(send.endpointOutput())).value();
		return Optional.of(new OutboundMessage(delivery.eventId(), delivery.acknowledged(),
			endpoint, (DataValue.RecordValue) output.field(send.payloadOutput()), delivery.sequenceBase() + delivery.acknowledged(), protocol));
	}
	/** Advance only the exact current message; stale, duplicate or out-of-order acknowledgements are inert. */
	public boolean acknowledge(UUID eventId, int ordinal) {
		requireLoaded(); Objects.requireNonNull(eventId);
		if (!hasOutbound() || !delivery.eventId().equals(eventId) || delivery.acknowledged() != ordinal) return false;
		delivery = new DeliveryState(eventId, nextEnabledOrdinal(ordinal + 1), delivery.sequenceBase());
		return true;
	}
	private boolean hasOutbound() {
		return delivery != null && machine.report().status() == ComputationMachine.Status.SUCCEEDED
			&& delivery.acknowledged() < activeDefinition().sends().size();
	}
	private int nextEnabledOrdinal(int ordinal) {
		var sends = activeDefinition().sends();
		var output = machine.report().output().orElseThrow();
		while (ordinal < sends.size() && !sends.get(ordinal).enabled(output)) ordinal++;
		return ordinal;
	}
	/** Admission only copies values into owned storage; the receiver's tick/step starts execution. */
	public AdmissionStatus receive(MessageEnvelope envelope) {
		Objects.requireNonNull(envelope);
		if (!loaded) return AdmissionStatus.UNAVAILABLE;
		var resolution = resolve(envelope);
		if (resolution.status() != AdmissionStatus.ACCEPTED) return resolution.status();
		var admission = mailbox.admit(envelope, mailboxLimits);
		if (admission.status() != AdmissionStatus.ACCEPTED) return admission.status();
		if (pending != null || report().status() == ComputationMachine.Status.FAILED) return AdmissionStatus.BUSY;
		if (!inputType(resolution.entry()).accepts(callbackInputs(envelope.payload(), resolution.reply()))) return AdmissionStatus.TYPE_MISMATCH;
		try {
			var replacement = rebindMailbox(admission.state());
			machine = replacement; mailbox = admission.state();
			return AdmissionStatus.ACCEPTED;
		} catch (ComputationException error) {
			if (error.code() == ComputationException.Code.MEMORY_LIMIT) return AdmissionStatus.MEMORY_LIMIT;
			throw error;
		}
	}
	private record Resolution(AdmissionStatus status, String entry, Optional<RequestContext> request, Optional<ReplyContext> reply) {}
	/** Opt-in direct handoff; legacy receive retains buffered admission semantics. */
	public ReceptionStatus receiveReady(MessageEnvelope envelope) {
		Objects.requireNonNull(envelope);
		if (!loaded) return ReceptionStatus.UNAVAILABLE;
		var receipt = mailbox.receipts().get(envelope.source());
		if (receipt != null) {
			if (envelope.sequence() < receipt.sequence()) return ReceptionStatus.STALE;
			if (envelope.sequence() == receipt.sequence()) {
				if (!receipt.eventId().equals(envelope.eventId()) || receipt.ordinal() != envelope.ordinal())
					return ReceptionStatus.IDENTITY_CONFLICT;
				return receipt.directReception() ? ReceptionStatus.RECEIVED : ReceptionStatus.LEGACY_DELIVERY;
			}
		}
		if (machine.paused()) return ReceptionStatus.PAUSED;
		if (report().status() == ComputationMachine.Status.FAILED) return ReceptionStatus.FAILED;
		if (pending != null || report().status() == ComputationMachine.Status.RUNNING || hasOutbound() || !mailbox.queued().isEmpty())
			return ReceptionStatus.WAITING_FOR_RECEIVER;
		if (envelope.protocol() instanceof MessageProtocol.Reply) return ReceptionStatus.UNSUPPORTED;
		String entry = envelope.protocol() instanceof MessageProtocol.Request ? "request" : "message";
		if (!program.program().entries().containsKey(entry)) return ReceptionStatus.UNSUPPORTED;
		var resolution = resolve(envelope);
		if (resolution.status() != AdmissionStatus.ACCEPTED) return ReceptionStatus.UNSUPPORTED;
		if (!inputType(entry).accepts(envelope.payload())) return ReceptionStatus.TYPE_MISMATCH;
		var admission = mailbox.admitDirect(envelope, mailboxLimits);
		if (admission.status() == AdmissionStatus.SOURCE_LIMIT) return ReceptionStatus.SOURCE_LIMIT;
		if (admission.status() != AdmissionStatus.ACCEPTED) return ReceptionStatus.WAITING_FOR_RECEIVER;
		try {
			var prepared = prepareEvent(envelope.payload(), entry, admission.state(), resolution.request(), Optional.empty());
			if (prepared.machine().report().status() == ComputationMachine.Status.FAILED)
				return prepared.machine().report().diagnostic().orElseThrow().code() == ComputationException.Code.MEMORY_LIMIT
					? ReceptionStatus.MEMORY_LIMIT : ReceptionStatus.FAILED;
			applyEvent(prepared); mailbox = admission.state();
			return ReceptionStatus.RECEIVED;
		} catch (ComputationException error) {
			return error.code() == ComputationException.Code.MEMORY_LIMIT ? ReceptionStatus.MEMORY_LIMIT : ReceptionStatus.FAILED;
		}
	}
	private Resolution resolve(MessageEnvelope envelope) {
		String entry = entryFor("message");
		Optional<RequestContext> request = Optional.empty();
		Optional<ReplyContext> reply = Optional.empty();
		AdmissionStatus status = AdmissionStatus.ACCEPTED;
		if (envelope.protocol() instanceof MessageProtocol.Request incoming) {
			entry = "request"; request = Optional.of(incoming.context());
			if (!program.program().entries().containsKey(entry)) status = AdmissionStatus.UNSUPPORTED;
		} else if (envelope.protocol() instanceof MessageProtocol.Reply incoming) {
			if (!incoming.correlation().requester().equals(id)) status = AdmissionStatus.UNSUPPORTED;
			else {
				reply = rpc.matching(incoming.correlation());
				if (reply.isEmpty()) status = AdmissionStatus.STALE_REPLY;
				else {
					var waiting = reply.orElseThrow().pending();
					entry = waiting.replyEntry();
					request = waiting.continuation().map(RequestContinuation::origin);
					if (waiting.continuation().filter(c -> envelope.payload().fields().containsKey(c.input())).isPresent())
						status = AdmissionStatus.TYPE_MISMATCH;
				}
			}
		}
		if (status == AdmissionStatus.ACCEPTED && request.isEmpty()
			&& program.program().entry(entry).sends().stream().anyMatch(ProgramDefinition.Send::requiresRequest))
			status = AdmissionStatus.UNSUPPORTED;
		return new Resolution(status, entry, request, reply);
	}
	private static DataValue.RecordValue callbackInputs(DataValue.RecordValue payload, Optional<ReplyContext> reply) {
		var capture = reply.flatMap(context -> context.pending().continuation());
		if (capture.isEmpty()) return payload;
		var fields = new LinkedHashMap<>(payload.fields());
		var continuation = capture.orElseThrow();
		if (fields.putIfAbsent(continuation.input(), continuation.captured().orElseThrow()) != null)
			throw error(ComputationException.Code.TYPE_MISMATCH, "Reply payload collides with continuation input");
		return new DataValue.RecordValue(fields);
	}
	private ComputationMachine rebindMailbox(MailboxState state) {
		var limits = limitsFor(periodic == null ? null : periodic.inputs(), state);
		var compiled = compileActive(limits);
		checkBudget(compiled, machine.checkpoint(), limits);
		return ComputationMachine.restore(compiled, machine.checkpoint());
	}
	private void startQueued() {
		var status = report().status();
		if (mailbox.queued().isEmpty() || hasOutbound() || status == ComputationMachine.Status.RUNNING || status == ComputationMachine.Status.FAILED) return;
		while (!mailbox.queued().isEmpty()) {
			var envelope = mailbox.queued().getFirst();
			var resolution = resolve(envelope);
			var next = mailbox.withoutHead();
			if (resolution.status() == AdmissionStatus.STALE_REPLY) { mailbox = next; continue; }
			beginEvent(callbackInputs(envelope.payload(), resolution.reply()), resolution.entry(), next, resolution.request(), resolution.reply());
			mailbox = next;
			return;
		}
	}
	public Optional<ComputationMachine.Diagnostic> installationDiagnostic() { return Optional.ofNullable(installationDiagnostic); }
	public void unload() { loaded = false; }
	public void load() { loaded = true; }
	public void configurePeriodic(long intervalTicks, DataValue.RecordValue inputs) {
		requireLoaded(); Objects.requireNonNull(inputs);
		if (machine.report().status() == ComputationMachine.Status.RUNNING || pending != null)
			throw error(ComputationException.Code.BUSY, "Periodic configuration requires an event boundary");
		var schedule = new PeriodicSchedule(intervalTicks, intervalTicks, true, inputs);
		var limits = limitsFor(inputs);
		var compiled = compileActive(limits);
		var timer = compiled.select(entryFor("timer"));
		if (!new DataType.RecordType(timer.root().graph().inputs()).accepts(inputs))
			throw error(ComputationException.Code.TYPE_MISMATCH, "Periodic inputs do not match the installed program");
		ComputationMachine restored;
		checkBudget(compiled, machine.checkpoint(), limits);
		restored = ComputationMachine.restore(compiled, machine.checkpoint());
		var validation = ComputationMachine.withMemory(timer, restored.memory());
		validation.begin(inputs);
		if (validation.report().status() == ComputationMachine.Status.FAILED) {
			var diagnostic = validation.report().diagnostic().orElseThrow();
			throw error(diagnostic.code(), diagnostic.message());
		}
		machine = restored;
		periodic = schedule;
	}
	public void disablePeriodic() { requireLoaded(); if (periodic != null) periodic = periodic.withEnabled(false); }
	public void clearPeriodic() {
		requireLoaded();
		if (periodic == null) return;
		var limits = limitsFor(null, mailbox);
		var compiled = compileActive(limits);
		var replacement = ComputationMachine.restore(compiled, machine.checkpoint());
		Candidate replacementPending = pending == null ? null
			: new Candidate(ProgramArchive.from(pending.compiled()).compile(limits), pending.policy());
		machine = replacement; pending = replacementPending; periodic = null;
	}

	/** The old event's completion remains observable until the next entry into this computer. */
	public InstallResult requestInstall(ProgramArchive archive, MemoryPolicy policy) {
		requireLoaded(); Objects.requireNonNull(policy);
		if (pending != null) throw error(ComputationException.Code.BUSY, "An installation is already queued");
		if (!mailbox.queued().isEmpty()) throw error(ComputationException.Code.BUSY, "Received messages are awaiting execution");
		try {
			boolean queued = report().status() == ComputationMachine.Status.RUNNING || hasOutbound();
			var limits = queued ? effectiveLimits() : limitsFor(periodic == null ? null : periodic.inputs(), mailbox, RpcState.empty());
			var candidate = new Candidate(archive.compile(limits), policy);
			if (queued) {
				pending = candidate; installationDiagnostic = null;
				if (periodic != null) periodic = periodic.withEnabled(false);
				return InstallResult.QUEUED;
			}
			apply(candidate);
			if (periodic != null) periodic = periodic.withEnabled(false);
			return InstallResult.APPLIED;
		} catch (ComputationException error) {
			installationDiagnostic = diagnostic(error); throw error;
		}
	}
	public void cancelPendingInstall() { requireLoaded(); pending = null; }
	public void begin(DataValue.RecordValue inputs) {
		requireLoaded();
		if (hasOutbound()) throw error(ComputationException.Code.BUSY, "Committed messages are awaiting delivery");
		if (!mailbox.queued().isEmpty()) throw error(ComputationException.Code.BUSY, "Received messages are awaiting execution");
		if (!applyPending()) throw new ComputationException(installationDiagnostic.code(), installationDiagnostic.location(), installationDiagnostic.message());
		beginEvent(inputs, null, mailbox);
		if (machine.report().status() == ComputationMachine.Status.FAILED && periodic != null)
			periodic = periodic.withEnabled(false);
	}
	/** Starts one explicit installed entry; rejected UI attempts leave the current continuation untouched. */
	public void beginLocal(String entry, DataValue.RecordValue inputs) {
		requireLoaded(); Objects.requireNonNull(entry); Objects.requireNonNull(inputs);
		if (entry.isBlank() || !program.program().entries().containsKey(entry))
			throw new IllegalArgumentException("Unknown local event entry: " + entry);
		if (machine.paused()) throw error(ComputationException.Code.BUSY, "Paused computers reject terminal events");
		if (pending != null) throw error(ComputationException.Code.BUSY, "A program installation is pending");
		if (hasOutbound()) throw error(ComputationException.Code.BUSY, "Committed messages are awaiting delivery");
		if (!mailbox.queued().isEmpty()) throw error(ComputationException.Code.BUSY, "Received messages are awaiting execution");
		if (machine.report().status() == ComputationMachine.Status.RUNNING)
			throw error(ComputationException.Code.BUSY, "An event is already running");
		if (!inputType(entry).accepts(inputs)) throw error(ComputationException.Code.TYPE_MISMATCH, "Local event inputs do not match the named entry");
		beginEvent(inputs, entry, mailbox);
	}
	public ComputationMachine.Report tick() {
		return beginActivityTick() ? runSlice(hardware.workPerSlice()) : report();
	}
	/** Lifecycle only: called once per activity tick by the owning scheduler, not once per work slice.
	 * Returns false if unavailable or a pending installation failed; no instructions are executed. */
	public boolean beginActivityTick() {
		if (!loaded || !applyPending()) return false;
		if (!machine.paused()) startQueued();
		if (!machine.paused() && periodic != null && periodic.enabled()) {
			var status = machine.report().status();
			if (status == ComputationMachine.Status.FAILED) periodic = periodic.withEnabled(false);
			else if (periodic.remainingTicks() > 0) periodic = periodic.withRemaining(periodic.remainingTicks() - 1);
			if (periodic.enabled() && periodic.remainingTicks() == 0
				&& !hasOutbound() && (status == ComputationMachine.Status.IDLE || status == ComputationMachine.Status.SUCCEEDED)) {
				beginEvent(periodic.inputs(), entryFor("timer"), mailbox);
				if (machine.report().status() != ComputationMachine.Status.FAILED)
					periodic = periodic.withRemaining(periodic.intervalTicks());
			}
		}
		return true;
	}
	/** Executes only the active event, with the same atomic commit as tick(). The owner must allocate
	 * a shared budget across calls; this does not advance timers, dequeue events or apply installations. */
	public ComputationMachine.Report runSlice(long workAllowance) {
		if (workAllowance < 0) throw new IllegalArgumentException("Negative work allowance");
		if (loaded && workAllowance > 0) {
			var oldMemory = machine.memory();
			boolean wasRunning = report().status() == ComputationMachine.Status.RUNNING;
			machine.runSlice(workAllowance);
			completeEvent(oldMemory, wasRunning);
			if (machine.report().status() == ComputationMachine.Status.FAILED && periodic != null)
				periodic = periodic.withEnabled(false);
		}
		return report();
	}
	public ComputationMachine.Report step() {
		if (loaded && applyPending()) {
			startQueued();
			var oldMemory = machine.memory();
			boolean wasRunning = report().status() == ComputationMachine.Status.RUNNING;
			machine.step();
			completeEvent(oldMemory, wasRunning);
			if (machine.report().status() == ComputationMachine.Status.FAILED && periodic != null) periodic = periodic.withEnabled(false);
		}
		return report();
	}
	/** Explicit reset cancels queued installation as well as the active event, but preserves debug pause. */
	public void reset() {
		requireLoaded();
		var cleared = mailbox.clearQueue();
		var replacement = new ComputationMachine(program.compile(limitsFor(periodic == null ? null : periodic.inputs(), cleared, RpcState.empty())));
		replacement.setPaused(machine.paused());
		pending = null; installationDiagnostic = null; machine = replacement; delivery = null; mailbox = cleared; activeEntry = null; rpc = RpcState.empty();
		if (periodic != null) periodic = periodic.withRemaining(periodic.intervalTicks());
	}

	public Snapshot snapshot() {
		return new Snapshot(id, program, machine.checkpoint(), pending == null ? Optional.empty()
			: Optional.of(new PendingInstallation(ProgramArchive.from(pending.compiled()), pending.policy())), installationDiagnostic(), Optional.ofNullable(periodic), Optional.ofNullable(delivery), mailbox, nextSequence, Optional.ofNullable(activeEntry), rpc);
	}
	/** Restore is inert. The owning loaded block must call load(); debugger pause is separate and retained. */
	public static ComputerRuntime restore(Snapshot saved, ResourceLimits hardware) {
		return restore(saved, hardware, MailboxLimits.DEFAULT);
	}
	public static ComputerRuntime restore(Snapshot saved, ResourceLimits hardware, MailboxLimits mailboxLimits) {
		var runtime = new ComputerRuntime(saved.id(), saved.program(), hardware, mailboxLimits);
		runtime.activeEntry = saved.activeEntry().orElse(null);
		try { runtime.activeDefinition(); }
		catch (IllegalArgumentException unknown) { throw error(ComputationException.Code.INVALID_CHECKPOINT, "Unknown saved event entry"); }
		runtime.periodic = saved.periodic().orElse(null);
		runtime.mailbox = saved.mailbox(); runtime.nextSequence = saved.nextSequence(); runtime.rpc = saved.rpc();
		runtime.validateRpc(saved.machine(), saved.delivery().orElse(null));
		if (runtime.mailbox.queued().size() > mailboxLimits.messages() || runtime.mailbox.receipts().size() > mailboxLimits.sources())
			throw error(ComputationException.Code.MEMORY_LIMIT, "Saved mailbox exceeds current limits");
		if (runtime.mailbox.queued().stream().anyMatch(message -> {
			var resolution = runtime.resolve(message);
			return resolution.status() != AdmissionStatus.STALE_REPLY && (resolution.status() != AdmissionStatus.ACCEPTED
				|| !runtime.inputType(resolution.entry()).accepts(callbackInputs(message.payload(), resolution.reply())));
		})
			|| !runtime.mailbox.queued().isEmpty() && saved.pending().isPresent())
			throw error(ComputationException.Code.INVALID_CHECKPOINT, "Queued messages do not match the saved program");
		var limits = runtime.effectiveLimits();
		var compiled = runtime.compileActive(limits);
		if (runtime.periodic != null && runtime.periodic.enabled()
			&& !runtime.inputType(runtime.entryFor("timer")).accepts(runtime.periodic.inputs()))
			throw error(ComputationException.Code.TYPE_MISMATCH, "Saved periodic inputs do not match the installed program");
		checkBudget(compiled, saved.machine(), limits);
		runtime.machine = ComputationMachine.restore(compiled, saved.machine());
		runtime.delivery = saved.delivery().orElse(null);
		runtime.validateDelivery();
		saved.pending().ifPresent(candidate -> runtime.pending = new Candidate(candidate.program().compile(limits), candidate.policy()));
		saved.installationDiagnostic().ifPresent(diagnostic -> {
			if (diagnostic.code() == null || diagnostic.location() == null || diagnostic.message() == null)
				throw error(ComputationException.Code.INVALID_CHECKPOINT, "Invalid installation diagnostic");
			runtime.installationDiagnostic = diagnostic;
		});
		runtime.loaded = false;
		return runtime;
	}
	private void beginEvent(DataValue.RecordValue inputs, String entry, MailboxState queue) {
		beginEvent(inputs, entry, queue, Optional.empty(), Optional.empty());
	}
	private void beginEvent(DataValue.RecordValue inputs, String entry, MailboxState queue,
		Optional<RequestContext> request, Optional<ReplyContext> reply) {
		applyEvent(prepareEvent(inputs, entry, queue, request, reply));
	}
	private record PreparedEvent(ComputationMachine machine, String entry, RpcState rpc, DeliveryState delivery, long nextSequence) {}
	private void applyEvent(PreparedEvent prepared) {
		machine = prepared.machine(); activeEntry = prepared.entry(); rpc = prepared.rpc();
		delivery = prepared.delivery(); nextSequence = prepared.nextSequence();
	}
	private PreparedEvent prepareEvent(DataValue.RecordValue inputs, String entry, MailboxState queue,
		Optional<RequestContext> request, Optional<ReplyContext> reply) {
		if (machine.report().status() == ComputationMachine.Status.RUNNING)
			throw error(ComputationException.Code.BUSY, "An event is already running");
		var definition = program.program().entry(entry);
		if (request.isEmpty() && definition.sends().stream().anyMatch(ProgramDefinition.Send::requiresRequest))
			throw error(ComputationException.Code.INVALID_GRAPH, "Reply or continuation requires an active incoming request");
		UUID event = UUID.randomUUID();
		var staged = new LinkedHashMap<String, RpcState.Pending>();
		for (int ordinal = 0; ordinal < definition.sends().size(); ordinal++) {
			var send = definition.sends().get(ordinal);
			if (send.dispatch() == ProgramDefinition.Dispatch.REQUEST)
				staged.put(send.requestSlot(), pendingFor(send, event, ordinal, request, Optional.empty()));
		}
		var futureRpc = rpc.begin(request, reply, staged);
		var compiled = program.compile(limitsFor(periodic == null ? null : periodic.inputs(), queue, futureRpc)).select(entry);
		long reserved;
		try { reserved = Math.addExact(nextSequence, compiled.sends().size()); }
		catch (ArithmeticException exhausted) { throw error(ComputationException.Code.ARITHMETIC_OVERFLOW, "Message sequence exhausted"); }
		// Select a validated graph, not another Computer: only this event's continuation is replaced.
		// Do not publish the selection, RAM or identity until begin has accepted the new event.
		var target = ComputationMachine.withMemory(compiled, machine.memory());
		target.setPaused(machine.paused());
		target.begin(inputs);
		var nextRpc = target.report().status() == ComputationMachine.Status.FAILED ? futureRpc.discardStaged() : futureRpc;
		var nextDelivery = target.report().status() == ComputationMachine.Status.RUNNING && !compiled.sends().isEmpty()
			? new DeliveryState(event, 0, nextSequence) : null;
		return new PreparedEvent(target, entry, nextRpc, nextDelivery, nextDelivery == null ? nextSequence : reserved);
	}
	private String entryFor(String event) { return program.program().entries().containsKey(event) ? event : null; }
	private ProgramDefinition.Entry activeDefinition() { return program.program().entry(activeEntry); }
	private DataType.RecordType inputType(String entry) { return new DataType.RecordType(program.program().entry(entry).graph().inputs()); }
	private ProgramCompiler.InstalledProgram compileActive(ResourceLimits limits) { return program.compile(limits).select(activeEntry); }
	private RpcState.Pending pendingFor(ProgramDefinition.Send send, UUID event, int ordinal,
		Optional<RequestContext> request, Optional<DataValue.RecordValue> output) {
		return new RpcState.Pending(new RequestCorrelation(id, event, ordinal), send.replyEntry(),
			send.continuation().map(binding -> new RequestContinuation(request.orElseThrow(), binding.captureInput(),
				output.map(value -> (DataValue.RecordValue) value.field(binding.captureOutput())))));
	}
	private void completeEvent(DataValue.RecordValue oldMemory, boolean wasRunning) {
		if (machine.report().status() == ComputationMachine.Status.FAILED) { delivery = null; rpc = rpc.discardStaged(); }
		else if (wasRunning && machine.report().status() == ComputationMachine.Status.SUCCEEDED) {
			// Pure evaluation is private until both RAM and captured request metadata fit.
			// On failure restore pre-commit RAM, retain older waits, and publish no outbox.
			var completed = machine.checkpoint();
			try {
				var outputs = completed.output().orElseThrow();
				var captured = new LinkedHashMap<String, RpcState.Pending>();
				validateSelectedReturns(outputs, ComputationException.Code.WRITE_CONFLICT);
				for (var send : activeDefinition().sends()) {
					if (!send.enabled(outputs)) continue;
					if (send.dispatch() != ProgramDefinition.Dispatch.REQUEST) continue;
					var waiting = rpc.staged().get(send.requestSlot());
					if (send.continuation().isPresent())
						waiting = waiting.capture((DataValue.RecordValue) outputs.field(send.continuation().orElseThrow().captureOutput()));
					captured.put(send.requestSlot(), waiting);
				}
				var committed = rpc.commit(captured);
				var limits = limitsFor(periodic == null ? null : periodic.inputs(), mailbox, committed);
				var compiled = compileActive(limits);
				checkBudget(compiled, completed, limits);
				var replacement = ComputationMachine.restore(compiled, completed);
				machine = replacement; rpc = committed;
				if (delivery != null) delivery = new DeliveryState(delivery.eventId(),
					nextEnabledOrdinal(delivery.acknowledged()), delivery.sequenceBase());
			} catch (ComputationException failure) {
				var previous = rpc.discardStaged();
				var compiled = compileActive(limitsFor(periodic == null ? null : periodic.inputs(), mailbox, previous));
				var failed = new MachineCheckpoint(oldMemory, ComputationMachine.Status.FAILED, completed.paused(),
					completed.workUnits(), completed.arenaUnits(), Optional.empty(), Optional.of(diagnostic(failure)), List.of());
				machine = ComputationMachine.restore(compiled, failed); rpc = previous; delivery = null;
			}
		}
	}
	private void validateSelectedReturns(DataValue.RecordValue outputs, ComputationException.Code failureCode) {
		int returns = 0;
		for (var send : activeDefinition().sends())
			if (send.requiresRequest() && send.enabled(outputs) && ++returns > 1)
				throw error(failureCode, "Multiple selected replies or continuations for one original request");
	}
	private void validateDelivery() {
		var status = machine.report().status();
		int sends = activeDefinition().sends().size();
		boolean required = sends > 0 && (status == ComputationMachine.Status.RUNNING || status == ComputationMachine.Status.SUCCEEDED);
		if (required != (delivery != null) || delivery != null && (delivery.acknowledged() > sends
			|| status == ComputationMachine.Status.RUNNING && delivery.acknowledged() != 0
			|| status == ComputationMachine.Status.SUCCEEDED && delivery.acknowledged() != nextEnabledOrdinal(delivery.acknowledged())
			|| delivery.sequenceBase() > nextSequence || nextSequence - delivery.sequenceBase() != sends))
			throw error(ComputationException.Code.INVALID_CHECKPOINT, "Delivery cursor does not match the saved event");
	}
	/** Validate metadata against the complete installed program before resolving queued callbacks. */
	private void validateRpc(MachineCheckpoint checkpoint, DeliveryState savedDelivery) {
		var status = checkpoint.status();
		if (status == ComputationMachine.Status.SUCCEEDED) {
			try { validateSelectedReturns(checkpoint.output().orElseThrow(), ComputationException.Code.INVALID_CHECKPOINT); }
			catch (NoSuchElementException | ComputationException invalid) {
				throw error(ComputationException.Code.INVALID_CHECKPOINT, "Invalid selected return effects in saved event");
			}
		}
		for (var value : rpc.pending().values()) validatePending(value);
		for (var value : rpc.staged().values()) validatePending(value);
		if (status == ComputationMachine.Status.IDLE && (!rpc.staged().isEmpty() || rpc.request().isPresent() || rpc.reply().isPresent()))
			throw error(ComputationException.Code.INVALID_CHECKPOINT, "Idle computer has active request metadata");
		if (rpc.request().isPresent() && rpc.reply().isEmpty() && !"request".equals(activeEntry))
			throw error(ComputationException.Code.INVALID_CHECKPOINT, "Request context does not select the request handler");
		rpc.reply().ifPresent(context -> {
			validatePending(context.pending());
			if (!context.pending().replyEntry().equals(activeEntry)
				|| status == ComputationMachine.Status.RUNNING && !context.pending().equals(rpc.pending().get(context.slot()))
				|| status == ComputationMachine.Status.SUCCEEDED && context.pending().equals(rpc.pending().get(context.slot())))
				throw error(ComputationException.Code.INVALID_CHECKPOINT, "Reply context does not match the active callback");
		});
		if (status != ComputationMachine.Status.IDLE && rpc.request().isEmpty()
			&& activeDefinition().sends().stream().anyMatch(ProgramDefinition.Send::requiresRequest))
			throw error(ComputationException.Code.INVALID_CHECKPOINT, "Reply effect has no active request context");
		if (status != ComputationMachine.Status.RUNNING && !rpc.staged().isEmpty())
			throw error(ComputationException.Code.INVALID_CHECKPOINT, "Terminal computer retains staged requests");
		if (status == ComputationMachine.Status.RUNNING || status == ComputationMachine.Status.SUCCEEDED) {
			var expected = new LinkedHashMap<String, RpcState.Pending>();
			for (int ordinal = 0; ordinal < activeDefinition().sends().size(); ordinal++) {
				var send = activeDefinition().sends().get(ordinal);
				if (send.dispatch() != ProgramDefinition.Dispatch.REQUEST) continue;
				if (savedDelivery == null) throw error(ComputationException.Code.INVALID_CHECKPOINT, "Request has no delivery identity");
				try {
					if (status == ComputationMachine.Status.SUCCEEDED && !send.enabled(checkpoint.output().orElseThrow())) continue;
					expected.put(send.requestSlot(), pendingFor(send, savedDelivery.eventId(), ordinal, rpc.request(),
						status == ComputationMachine.Status.SUCCEEDED ? checkpoint.output() : Optional.empty()));
				} catch (NoSuchElementException | ClassCastException | ComputationException invalid) {
					throw error(ComputationException.Code.INVALID_CHECKPOINT, "Invalid saved continuation capture");
				}
			}
			if (status == ComputationMachine.Status.RUNNING ? !rpc.staged().equals(expected)
				: expected.entrySet().stream().anyMatch(entry -> !entry.getValue().equals(rpc.pending().get(entry.getKey()))))
				throw error(ComputationException.Code.INVALID_CHECKPOINT, "Request metadata does not match the active event declarations");
		}
	}
	private void validatePending(RpcState.Pending value) {
		if (!value.correlation().requester().equals(id) || !program.program().entries().containsKey(value.replyEntry()))
			throw error(ComputationException.Code.INVALID_CHECKPOINT, "Pending request has a foreign owner or unknown handler");
		value.continuation().ifPresent(continuation -> {
			var expected = program.program().entry(value.replyEntry()).graph().inputs().get(continuation.input());
			if (expected == null || continuation.captured().filter(capture -> !expected.accepts(capture)).isPresent())
				throw error(ComputationException.Code.INVALID_CHECKPOINT, "Continuation capture does not fit the callback input");
		});
	}
	private ResourceLimits effectiveLimits() { return limitsFor(periodic == null ? null : periodic.inputs(), mailbox); }
	private ResourceLimits limitsFor(DataValue.RecordValue inputs) {
		return limitsFor(inputs, mailbox);
	}
	private ResourceLimits limitsFor(DataValue.RecordValue inputs, MailboxState state) {
		return limitsFor(inputs, state, rpc);
	}
	private ResourceLimits limitsFor(DataValue.RecordValue inputs, MailboxState state, RpcState rpcState) {
		try {
			long available = Math.subtractExact(Math.subtractExact(Math.subtractExact(hardware.memoryUnits(), inputs == null ? 0 : inputs.storageUnits()), state.storageUnits()), rpcState.storageUnits());
			if (available < 0) throw error(ComputationException.Code.MEMORY_LIMIT, "Saved inputs and mailbox exceed memory capacity");
			return hardware.withMemoryUnits(available);
		} catch (ArithmeticException overflow) { throw error(ComputationException.Code.MEMORY_LIMIT, "Reserved input size exceeds supported range"); }
	}
	private static void checkBudget(ProgramCompiler.InstalledProgram compiled, MachineCheckpoint checkpoint, ResourceLimits limits) {
		try {
			long required = checkpoint.arenaUnits();
			if (checkpoint.status() == ComputationMachine.Status.IDLE)
				required = Math.addExact(Math.addExact(compiled.usage().constantUnits(), compiled.usage().initialMemoryUnits()),
					checkpoint.memory().storageUnits());
			if (required > limits.memoryUnits())
				throw error(ComputationException.Code.MEMORY_LIMIT, "Saved computation exceeds memory capacity after periodic input reservation");
		} catch (ArithmeticException overflow) {
			throw error(ComputationException.Code.MEMORY_LIMIT, "Saved computation size exceeds supported range");
		}
	}
	private boolean applyPending() {
		if (pending == null || report().status() == ComputationMachine.Status.RUNNING || hasOutbound()) return true;
		var candidate = pending;
		pending = null;
		try { apply(candidate); return true; }
		catch (ComputationException error) { installationDiagnostic = diagnostic(error); return false; }
	}
	private void apply(Candidate candidate) {
		candidate = new Candidate(ProgramArchive.from(candidate.compiled()).compile(limitsFor(periodic == null ? null : periodic.inputs(), mailbox, RpcState.empty())), candidate.policy());
		var applied = candidate;
		var values = new LinkedHashMap<String, DataValue>();
		candidate.compiled().cells().forEach((name, cell) -> {
			DataValue value = applied.policy() == MemoryPolicy.PRESERVE
				? memory().fields().getOrDefault(name, cell.initial()) : cell.initial();
			if (!cell.type().accepts(value)) throw error(ComputationException.Code.TYPE_MISMATCH, "Stored value is incompatible with cell: " + name);
			values.put(name, value);
		});
		var replacement = ComputationMachine.withMemory(candidate.compiled(), new DataValue.RecordValue(values));
		replacement.setPaused(machine.paused());
		var replacementProgram = ProgramArchive.from(candidate.compiled());
		// Everything that may reject the candidate happens before replacing either owned value.
		program = replacementProgram; machine = replacement; installationDiagnostic = null; delivery = null; activeEntry = null; rpc = RpcState.empty();
	}
	private void requireLoaded() { if (!loaded) throw error(ComputationException.Code.BUSY, "Computer is unloaded"); }
	private static ComputationException error(ComputationException.Code code, String message) { return new ComputationException(code, "installation", message); }
	private static ComputationMachine.Diagnostic diagnostic(ComputationException error) {
		return new ComputationMachine.Diagnostic(error.code(), error.location(), error.getMessage());
	}
}
