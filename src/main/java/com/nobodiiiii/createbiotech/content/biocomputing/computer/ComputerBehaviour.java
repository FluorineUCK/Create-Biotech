package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.CarrierHardwareConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierClock;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierPortStatus;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.RadioSerialNetwork;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.WitchReceiverFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationMachine;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaLibraryFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventLog;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.ReceptionStatus;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.CapturedResidentHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterAttachment;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterAttachmentStore;
import net.minecraft.world.item.ItemStack;

/** The physical block is a lifecycle adapter only; all generic state is owned by this module. */
public final class ComputerBehaviour extends BlockEntityBehaviour implements CommunicationEndpoint {
	public static final BehaviourType<ComputerBehaviour> TYPE = new BehaviourType<>();
	public static final String ROOT = "Biocomputing";
	public static final String EDITOR_ROOT = "BiocomputingEditor";
    public static final String DRAFT_ROOT = "BiocomputingDraft";
    private static final String ANALYZER_ROOT="BiocomputingEdgeTrace";
    private final com.nobodiiiii.createbiotech.content.biocomputing.hardware.EdgeRecorder analyzer=
        new com.nobodiiiii.createbiotech.content.biocomputing.hardware.EdgeRecorder();
    void clearInstrumentCapture(){analyzer.clear(); fisher.clear();}
    private String instrumentModel(){
        var h=hardware();
        if(FisherListener.present(h))return "fisherman";
        if(VindicatorSequencer.supports(h))return "vindicator";
        return h.ready() && h.resident().filter(r -> ILLUSIONER.equals(r.entityType())).isPresent() ? "illusioner" : "none";
    }
    private static final net.minecraft.resources.ResourceLocation ILLUSIONER=net.minecraft.resources.ResourceLocation.withDefaultNamespace("illusioner");
    public boolean samplingEdges() {
        if(!analyzer.armed())return false;
        if(peripheralAvailable() && instrumentModel().equals("illusioner"))return true;
        analyzer.gap();return false;
    }
    public void sampleEdges(long tick){
        if(!samplingEdges()){analyzer.gap();return;}
        var access=access();
        var levels=cableInputs(signalController().map(c->c.signalInputs(this)).orElseGet(()->localSignalInputs(access)));
        if(analyzer.sample(tick,levels,CBConfigs.SERVER.biocomputing.analyzerDepth.get())) markEditorChanged.run();
    }
    public DataValue.RecordValue instrumentStatus(){
        String model=instrumentModel();
        var state=switch(model){
            case "fisherman" -> fisherStatus();
            case "vindicator" -> sequencerStatus();
            case "illusioner" -> ResidentInstrumentControl.analyzerStatus(analyzer);
            default -> new DataValue.RecordValue(java.util.Map.of());
        };
        return ResidentInstrumentControl.status(model,hardware().limits().pins(),state);
    }
    public void configureInstrument(DataValue.RecordValue request){
        requireEndpointAvailable();
        switch(instrumentModel()){
            case "fisherman" -> configureFisher(request);
            case "vindicator" -> configureSequencer(request);
            case "illusioner" -> {
                ResidentInstrumentControl.configureAnalyzer(analyzer,hardware().limits().pins(),request);
                programRevision++; markEditorChanged.run();
            }
            default -> throw new IllegalArgumentException("missing_instrument");
        }
    }
    private final FisherListener fisher = new FisherListener();

    public DataValue.RecordValue fisherStatus() {
        return fisher.status(available.getAsBoolean() && FisherListener.present(hardware()));
    }
    public void configureFisher(DataValue.RecordValue request) {
        var selected = hardware();
        if (!available.getAsBoolean() || !FisherListener.present(selected)) throw new IllegalArgumentException("missing_fisherman");
        var access = access(selected);
        host.synchronize(access.limits(),access.available());
        fisher.configure(request, selected.limits().pins(), name -> host.flowSnapshot().stream().anyMatch(saved ->
            saved.program().steps().stream().anyMatch(step -> step instanceof FlowProgram.Procedure p && p.event().filter(name::equals).isPresent())));
        programRevision++; markEditorChanged.run();
    }
    private void sampleFisher(long tick, Access access) {
        if (!fisher.bound()) return;
        var saved = host.flowSnapshot();
        if (!access.available() || !FisherListener.present(hardware()) || !host.loaded() || saved.isEmpty()
                || saved.get().state().paused() || !(blockEntity.getLevel() instanceof ServerLevel level)) {
            fisher.gap(); return;
        }
        var pins = access.limits().pins();
        var signals = fisher.needsSignals() ? cableInputs(signalController().map(c -> c.signalInputs(this))
            .orElseGet(() -> localSignalInputs(access))) : java.util.Map.<String,Integer>of();
        boolean dirty = fisher.tick(tick, b -> {
            if (!b.supported(pins)) return null;
            if (b.ready()) return XBusConnectedNetwork.readyWriter(level, this, new XBusNetwork.Pin(saved.get().state().computer(),b.pin())).map(FisherListener.Writer::of).orElse(null);
            if (saved.get().state().signals().outputs().containsKey(b.pin())) return null;
            Integer signal = signals.get(b.pin()); return signal == null ? null : signal > 0;
        }, name -> host.admitFisherEvent(name, access.limits(), true));
        if (dirty) markEditorChanged.run();
    }
    private final SavedProgramDraft savedDraft = new SavedProgramDraft();
    public SavedProgramDraft.Snapshot savedDraft() { return savedDraft.snapshot(hardware().limits().archive()); }
    public boolean saveDraft(long revision, byte[] document) {
        boolean saved = savedDraft.save(revision, document, hardware().limits().archive());
        if (saved) markEditorChanged.run();
        return saved;
    }
	public static final String ENDPOINT_ROOT = "BiocomputingEndpoint";
	public static final String ATTACHMENT_ROOT = "BiocomputingClusterAttachment";
    public static final String INSTALLATION_ROOT = "BiocomputingInstallation";
    private Tag installation;
    private final VindicatorSequencer sequencer = new VindicatorSequencer();
    private long lastSequenceTick = Long.MIN_VALUE;
    private boolean sequencerChunkTicking() {
        return !(blockEntity.getLevel() instanceof ServerLevel level) || level.shouldTickBlocksAt(blockEntity.getBlockPos());
    }
    public DataValue.RecordValue sequencerStatus() {
        var h = hardware();
        boolean ready = available.getAsBoolean() && sequencerChunkTicking() && VindicatorSequencer.supports(h)
            && (!sequencer.armed() || h.limits().pins().hasSimpleIo(sequencer.pin()));
        if (ready && sequencer.armed()) {
            var access = access(h);
            ready = host.synchronize(access.limits(), access.available());
        }
        return sequencer.status(ready, sequencerPaused());
    }
    public void configureSequencer(DataValue.RecordValue command) {
        var h = hardware();
        if (!available.getAsBoolean() || !VindicatorSequencer.supports(h))
            throw new IllegalArgumentException("missing_sequencer");
        sequencer.configure(command,h.limits().pins().simpleIo());
        invalidateSignals(); programRevision++; markEditorChanged.run();
    }
    void removeSequencer() {
        if (sequencer.configured()) { sequencer.stop(); invalidateSignals(); }
    }
    private boolean sequencerPaused() {
        if (!sequencer.armed()) return false;
        return host.sequentialMode() ? host.flowSnapshot().map(s -> s.state().paused()).orElse(false)
            : host.snapshot().map(s -> s.machine().paused()).orElse(false);
    }
    private void tickSequencer(Access access) {
        if (!sequencer.armed()) return;
        if (access.available() && VindicatorSequencer.supports(hardware()) && access.limits().pins().hasSimpleIo(sequencer.pin())
            && host.synchronize(access.limits(), true) && !sequencerPaused()) {
            if (blockEntity.getLevel() instanceof ServerLevel level) {
                if (lastSequenceTick == level.getGameTime()) return;
                lastSequenceTick = level.getGameTime();
            }
            sequencer.tick();
            markEditorChanged.run();
        } else sequencer.suspend();
    }
    public static final String BREAKPOINT_ROOT = "BiocomputingBreakpoint";
    private final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentBreakpoint breakpoint =
        new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentBreakpoint();
    private static final String BREAKPOINT_BUS_ROOT="BiocomputingBreakpointBus";
    private final BreakpointBus breakpointBus;
    private static final net.minecraft.resources.ResourceLocation ZOMBIE = net.minecraft.resources.ResourceLocation.withDefaultNamespace("zombie_villager");
    private static boolean breakpointResident(HardwareSelection h) {
        return h.ready() && h.resident().filter(r -> ZOMBIE.equals(r.entityType())).isPresent();
    }
    private boolean stoppedByBreakpoint() { return breakpoint.halted() && breakpointResident(hardware()); }
    public void configureBreakpointOutput(String pin) {
        var h=hardware();
        if (!available.getAsBoolean() || !breakpointResident(h) || !(h.limits().serial().ports().containsKey(pin) || h.limits().pins().xbus().stream().findFirst().filter(pin::equals).isPresent()))
            throw new IllegalArgumentException("missing_breakpoint_output");
        breakpoint.select(pin);programRevision++;markEditorChanged.run();
    }
    public void resetBreakpoint() {
        if(!available.getAsBoolean() || !breakpointResident(hardware()))throw new IllegalArgumentException("missing_breakpoint");
        breakpointBus.rearm(hardware().limits(),breakpoint);breakpoint.reset();programRevision++;markEditorChanged.run();
    }
    public static final String SERIAL_ROOT = "BiocomputingSerial";
    private static final String RADIO_ROOT = "BiocomputingRadioTuner";
    private final com.nobodiiiii.createbiotech.content.biocomputing.network.serial.ResidentRadioTuning radio =
        new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.ResidentRadioTuning();
    public static final String SIGNAL_ROOT = "BiocomputingSignalConnections";
    private final com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections signalConnections =
        new com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections();
    private java.util.Map<String, String> signalConnectionStatus = java.util.Map.of();
    private final com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice serial;
	private final ClusterAttachmentStore clusterAttachment = new ClusterAttachmentStore();
	private final EndpointConfigurationStore endpoint = new EndpointConfigurationStore();
	private final ComputerBlockHost host;
    private final ComputerFileRam fileRam = new ComputerFileRam();
    private final ComputerFileRam transferRam = new ComputerFileRam();

    public Optional<ComputerFileRam.Lease> reserveFileRam(UUID actor, String name, int size) {
        var access = access();
        if (!access.available() || !host.synchronize(access.limits(), true)) return Optional.empty();
        return fileRam.reserve(actor, name, size, access.limits().hardware().memoryUnits(),
            capacity -> host.fitsMemory(access.limits().withMemoryUnits(capacity)));
    }
    public Optional<ComputerFileRam.File> stagedFile(UUID actor) { return fileRam.file(actor); }
    public void discardStagedFile(UUID actor) { fileRam.clear(actor); }

    /** Borrow the staged file and write the archive directly into one bounded output lease. */
    public Optional<ComputerFileRam.Lease> prepareStagedFileTransfer(UUID actor, UUID id) {
        if (transferRam.reservedBytes() != 0) return Optional.empty();
        var access = access();
        if (!access.available() || !host.synchronize(access.limits(), true)) return Optional.empty();
        var file = fileRam.file(actor).filter(value -> value.id().equals(id)).orElse(null);
        if (file == null) return Optional.empty();
        var config = CBConfigs.SERVER.biocomputing;
        boolean loaded = CBConfigs.SERVER_SPEC.isLoaded();
        int limit = (int)Math.min(config.libraryBookBytes(loaded), config.libraryDrive(loaded).bufferBytes());
        var archive = access.limits().archive();
        long scratch = SpeakerMediaLibraryFile.transferWorkspace(file.name(), file.size(), limit);
        long capacity = access.limits().hardware().memoryUnits();
        if (scratch > capacity || !host.fitsMemory(access.limits().withMemoryUnits(capacity - scratch)))
            throw new IllegalArgumentException("media_ram_unavailable");
        var encoding = fileRam.use(actor, id, raw -> SpeakerMediaLibraryFile.prepareTransfer(file.name(), raw, limit, archive))
            .orElseThrow();
        // The archive is still live while the output lease is being filled; charge both during that phase.
        var lease = transferRam.reserve(actor, file.name(), encoding.size(), capacity - scratch,
            remaining -> host.fitsMemory(access.limits().withMemoryUnits(remaining)))
            .orElseThrow(() -> new IllegalArgumentException("media_ram_unavailable"));
        try {
            if (lease.writeExact(encoding::write) && lease.seal(ignored -> true)) return Optional.of(lease);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("invalid_file_transfer", failure); // Failed exact writes abort the lease.
        }
        lease.abort();
        throw new IllegalStateException("invalid_file_transfer");
    }
    public void retireStagedFile(UUID actor, UUID id) { fileRam.clear(actor, id); }
	private final RecentEventLog recentEvents;
	private Boolean logAvailable;
	private Optional<EndpointConfiguration> logEndpoint = Optional.empty();
	private final Supplier<HardwareSelection> hardware;
	private final BooleanSupplier available;
	private final Runnable markEditorChanged;
	// Menu lifetime fence only; reopening a saved block creates a new menu nonce.
	private long programRevision;
	private long scheduledTick = Long.MIN_VALUE;
	private ProgramArchive observedProgram;
	private Optional<ComputerRuntime.PendingInstallation> observedPending = Optional.empty();
	private Tag editorDurable;
	private ProgramDocument activeDocument;
	private ProgramDocument pendingDocument;
	private CompoundTag activeEncoded, pendingEncoded;
	private boolean editorDecoded;
	private ComputerBlockHost.Diagnostic editorDiagnostic;
	private boolean editorValidated;
	private Optional<EndpointTransport.Result> transportResult = Optional.empty();
	private ComputerRuntime.DeliveryState transportContext;

	public ComputerBehaviour(SmartBlockEntity owner, Supplier<ItemStack> resident, BooleanSupplier physicalHardwareAvailable) {
		this(owner, () -> ownsLoadedServerBlock(owner) && physicalHardwareAvailable.getAsBoolean(), configuredHardware(resident));
	}
	/** Narrow lifecycle seam; local hardware/availability suppliers must be observation-only, never provider callbacks. */
	ComputerBehaviour(SmartBlockEntity owner, BooleanSupplier available, Supplier<HardwareSelection> hardware) {
		super(owner);
		this.hardware = Objects.requireNonNull(hardware);
		this.available = Objects.requireNonNull(available);
		markEditorChanged = owner::setChanged;
		recentEvents = new RecentEventLog(
			() -> owner.getLevel() == null ? 0 : owner.getLevel().getGameTime(),
			() -> CBConfigs.SERVER.biocomputing.recentEventCapacity(CBConfigs.SERVER_SPEC.isLoaded()));
        serial = new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice(
            () -> hardware().limits().serial(), this::peripheralAvailable,
            () -> CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded()), owner::setChanged,
            new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.RadioSlot(
                com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware.PORT,
                this::hasResidentRadio, radio::owned, () -> radio.armed() && radioLocationAvailable(),
                com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioConfig::retentionCeiling));
        serial.receiveGate(this::breakpointReceiveEnabled);
        breakpointBus=new BreakpointBus(this::breakpointBusChanged);
		host = new ComputerBlockHost(this::runtimeChanged, this::observeRendezvous, serial);
		host.onLaunchRefused((code, handler) -> recentEvents.append(RecentEventLog.Kind.ERROR, code, handler));
	}
	private void observeRendezvous(String pin) {
		recentEvents.append(RecentEventLog.Kind.TRANSFER, "xbus_exchanged", pin);
		// Diagnostics are post-commit. A failed/removed controller must not fail or replay the exchange.
		try {
			host.flowSnapshot().ifPresent(saved -> saved.receipts().stream().filter(receipt -> receipt.local(saved.state().computer()).stream().anyMatch(p -> p.pin().equals(pin)))
				.reduce((first, last) -> last).ifPresent(receipt -> signalController().ifPresent(controller ->
					controller.recordRendezvous(saved.state().computer(), pin, receipt))));
		} catch (RuntimeException ignored) { /* Cosmetic observer only, never transport authority. */ }
	}
	private void runtimeChanged() {
		try { observeProtocolHistory(); }
		finally { markEditorChanged.run(); observeRecentEvents(); }
	}
    private void breakpointBusChanged(){try{observeProtocolHistory(breakpointBus.host);}finally{markEditorChanged.run();}}
    private void observeProtocolHistory(){observeProtocolHistory(host);}
    private void observeProtocolHistory(ComputerBlockHost host) {
        if (host == null || !(blockEntity.getLevel() instanceof ServerLevel level)) return;
		host.flowSnapshot().ifPresent(saved -> {
			var config = CBConfigs.SERVER.biocomputing; boolean loaded = CBConfigs.SERVER_SPEC.isLoaded();
			Optional<XBusExchange.Status> failure;
			try {
				var history = XBusHistorySavedData.get(level);
				// A purely local new flow reserves no communication capacity. A reset of a restored
				// old image must still retire independently recorded consumption, even if its local peers are empty.
				if (saved.receipts().isEmpty() && saved.control().peers().isEmpty() && !history.tracks(saved.state().computer())) return;
				failure = history.observe(java.util.List.of(saved), config.xbusHistoryComputerLimit(loaded), config.xbusHistoryClaimLimit(loaded));
			}
			catch (RuntimeException unavailableHistory) { failure = Optional.of(XBusExchange.Status.UNAVAILABLE); }
			// Local computation continues. Every later world XBus access still checks the authoritative store.
			if (failure.isPresent()) recentEvents.transition("xbus_history", failure.orElseThrow(), RecentEventLog.Kind.ERROR,
				failure.orElseThrow().name().toLowerCase(java.util.Locale.ROOT), "xbus_history");
			else recentEvents.settle("xbus_history", "ready");
		});
	}
	private void observeRecentEvents() {
		if (host == null) return;
		observeSerial();
		var log = recentEvents;
		var port = endpoint.configuration();
		if (!port.equals(logEndpoint)) {
			if (port.filter(EndpointConfiguration::enabled).isPresent()) {
				boolean reopening = logEndpoint.filter(old -> !old.enabled() && old.name().equals(port.orElseThrow().name())).isPresent();
				log.append(reopening ? RecentEventLog.Kind.RECOVERED : RecentEventLog.Kind.CONTROL,
					reopening ? "recovered" : "port_open", port.orElseThrow().name());
			} else if (logEndpoint.filter(EndpointConfiguration::enabled).isPresent())
				log.append(RecentEventLog.Kind.UNAVAILABLE, "closed", logEndpoint.orElseThrow().name());
			logEndpoint = port;
		}
		boolean ready = host.diagnostic().filter(d -> d.code() == ComputerBlockHost.Code.UNAVAILABLE).isEmpty();
		if (logAvailable != null && logAvailable != ready)
			log.append(ready ? RecentEventLog.Kind.RECOVERED : RecentEventLog.Kind.UNAVAILABLE, ready ? "recovered" : "unavailable", "");
		logAvailable = ready;
		host.diagnostic().filter(d -> d.code() != ComputerBlockHost.Code.UNAVAILABLE).ifPresentOrElse(
			d -> log.transition("host_error", d.code(), RecentEventLog.Kind.ERROR, d.code().name().toLowerCase(java.util.Locale.ROOT), ""),
			() -> log.settle("host_error", "none"));
		var flow = host.flowSnapshot();
		flow.ifPresent(saved -> {
			var state = saved.state();
			log.transition("pause", state.paused(), RecentEventLog.Kind.CONTROL,
				state.paused() ? "paused" : "resumed", "");
			String identity = state.flow() + ":" + state.operation() + ":" + state.status();
			if (state.status() == FlowRuntime.Status.WAITING_READ || state.status() == FlowRuntime.Status.WAITING_WRITE
				|| state.status() == FlowRuntime.Status.WAITING_READABLE || state.status() == FlowRuntime.Status.WAITING_TASK) {
				var step = saved.program().steps().get(state.cursor());
				String pin = switch (step) {
					case FlowProgram.Read read -> read.port();
					case FlowProgram.Write write -> write.port();
					case FlowProgram.AwaitReadable wait -> wait.port();
					default -> "";
				};
				log.transition("state", identity, RecentEventLog.Kind.WAIT,
					state.status().name().toLowerCase(java.util.Locale.ROOT), pin);
			} else if (state.status() == FlowRuntime.Status.FAILED)
				log.transition("state", identity, RecentEventLog.Kind.ERROR,
					state.diagnostic().map(d -> d.code().name().toLowerCase(java.util.Locale.ROOT)).orElse("failed"), "");
			else log.settle("state", identity);
		});
		host.snapshot().ifPresent(saved -> {
			var state = saved.machine();
			log.transition("pause", state.paused(), RecentEventLog.Kind.CONTROL, state.paused() ? "paused" : "resumed", "");
			state.diagnostic().ifPresentOrElse(d -> log.transition("event_error", d.code(), RecentEventLog.Kind.ERROR,
				d.code().name().toLowerCase(java.util.Locale.ROOT), ""), () -> log.settle("event_error", "none"));
		});
	}
	/** Authorized menu projection, intentionally excluding program, RAM, payloads and protocol receipts. */
	public DataValue.RecordValue recentEvents() { observeRecentEvents(); return recentEvents.value(); }
	/** Post-publication diagnostic only. No payload/identity is exposed and display failure cannot reject an instruction. */
	public void observeSerial() {
		try { recentEvents.observeSerial(serial.observedView()); }
		catch (RuntimeException | StackOverflowError ignored) { /* Not transport authority. */ }
	}
	/** Read-only physical wiring observation, not an instruction result or delivery acknowledgement. */
	public void observeExternalWiring(java.util.Map<String, String> codes) { recentEvents.observeWiring(codes); }
	/** Post-round rejection from the real scheduler. Never restores, advances or persists a runtime. */
	public void observeXBusExecution(String code) {
		wireSnapshot().ifPresent(saved -> observeXBusExecution(code, saved));
	}
	/** A scheduler may report the exact pre-completion operation after its local result was published. */
	public void observeXBusExecution(String code, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost.Saved saved) {
		if (java.util.Set.of("not_ready", "ready", "prepared", "exchanged", "readable").contains(code)) return;
		try {
			if (!ownsLoadedServerBlock(blockEntity)) return;
			if (host.flowSnapshot().filter(current -> current.state().computer().equals(saved.state().computer())).isPresent()) {
				var state = saved.state();
				if (state.paused() || !(state.status() == FlowRuntime.Status.WAITING_READ
					|| state.status() == FlowRuntime.Status.WAITING_WRITE || state.status() == FlowRuntime.Status.WAITING_READABLE)) return;
				String pin = com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowXBus.pin(saved.program(),state);
				var operation = java.util.List.of(state.flow(), state.operation(), saved.control().generation());
				recentEvents.observeExecution("local", operation, code, pin);
				signalController().ifPresent(controller -> controller.recordXBusExecution(state.computer(), pin, operation, code));
			}
		} catch (RuntimeException | StackOverflowError ignored) { /* Diagnostics cannot reject or replay an instruction. */ }
	}
	@Override public BehaviourType<?> getType() { return TYPE; }
	@Override public void initialize() { ComputerExecutionDirectory.register(this); }
	@Override public void tick() {
		ComputerExecutionDirectory.ticked(this);
        if (available.getAsBoolean() && blockEntity.getLevel() instanceof ServerLevel level) {
            var h=hardware();
            if (breakpointResident(h)) {
                var pins=java.util.List.copyOf(h.limits().serial().ports().keySet());
                boolean wire=breakpointBus.open(h.limits(),breakpoint);
                if(wire)XBusHistorySavedData.get(level).maintain(breakpointBus.host,BreakpointBus.limits(h.limits()),true);
                boolean changed=false;
                if(!breakpoint.halted()) {
                    var packet=wire?breakpointBus.input():Optional.<DataValue.RecordValue>empty();
                    changed=packet.isPresent()?breakpoint.receiveBus(packet.get(),serial,pins):breakpoint.sample(serial,pins);
                }
                if(wire) changed|=breakpointBus.output(breakpoint,h.limits());
                if(changed){programRevision++;markEditorChanged.run();}
                return; // Fixed resident hardware, not an extra CPU for the archived application.
            }
        }
		var access = access(); prepareEditor(access); var before = host.snapshot();
		try {
			tickSequencer(access);
			if (access.available() && host.sequentialMode() && blockEntity.getLevel() instanceof ServerLevel level)
				XBusHistorySavedData.get(level).maintain(host, access.limits(), true);
			// Flow runs once at level tick end, after every real participant has ticked.
			// Do not grant activity here: block/Cluster visitation order is not instruction order.
			if (host.sequentialMode() && blockEntity.getLevel() instanceof ServerLevel) { /* coordinated below the world tick */ }
			else if (blockEntity.getLevel() instanceof ServerLevel level)
				host.tick(level.getGameTime(), access.limits(), access.available(), withXBusStatus(localSignalInputs(access), access));
			else host.tick(access.limits(), access.available(), withXBusStatus(localSignalInputs(access), access));
		} finally { reconcileDocuments(before, false); migrateInstallation(); observeRecentEvents(); }
		deliverCommitted(access);
	}
	private void deliverCommitted(Access access) {
		var configuration = endpoint.configuration().filter(EndpointConfiguration::enabled);
		if (!access.available() || configuration.isEmpty()) return;
		var outgoing = host.outbound(access.limits(), true);
		if (outgoing.isEmpty()) return;
		var message = outgoing.orElseThrow();
		var source = host.snapshot().orElseThrow().id();
		var boundOutput = clusterAttachment.attachment().filter(binding -> binding.outputs().contains(message.endpoint()));
		var result = clusterAttachment.diagnostic().isPresent()
			? new EndpointTransport.Result("invalid_attachment", Optional.empty())
			: boundOutput.isPresent()
				? com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterMemberTransport.deliver(blockEntity,
					boundOutput.get(), source, configuration.orElseThrow().name(), message)
				: EndpointTransport.deliver(blockEntity, source, configuration.orElseThrow().name(), message);
		transportResult = Optional.of(result);
		if (result.accepted()) host.acknowledge(message.eventId(), message.ordinal(), access.limits(), true);
		transportContext = host.snapshot().flatMap(ComputerRuntime.Snapshot::delivery).orElse(null);
	}
	/** Receive grant is the explicitly open endpoint, not access to this machine's RAM or its world readers. */
	@Override public AdmissionStatus receive(MessageEnvelope envelope) {
		var access = access();
		if (!access.available()) return AdmissionStatus.UNAVAILABLE;
		if (endpoint.configuration().filter(EndpointConfiguration::enabled).isEmpty()) return AdmissionStatus.CLOSED;
		return host.receive(envelope, access.limits(), true);
	}
	/** Separate opt-in protocol; Cluster's existing buffered bus continues to call receive(). */
	@Override public ReceptionStatus receiveReady(String port, java.util.UUID expectedReceiver, MessageEnvelope envelope) {
		var access = access();
		if (!access.available()) return ReceptionStatus.UNAVAILABLE;
		var config = endpoint.configuration().filter(EndpointConfiguration::enabled);
		if (config.isEmpty() || !config.orElseThrow().name().equals(port)) return ReceptionStatus.CLOSED;
		return host.receiveReady(expectedReceiver, envelope, access.limits(), true);
	}
	public Optional<EndpointTransport.Result> transportResult() {
		if (!Objects.equals(transportContext, host.snapshot().flatMap(ComputerRuntime.Snapshot::delivery).orElse(null))) {
			transportResult = Optional.empty(); transportContext = null;
		}
		return transportResult;
	}
	/** Restore owned state for an authorized observation, without advancing it or any external input. */
	public DataValue.RecordValue communicationStatus() {
		var access = access();
		if (!access.available()) throw new PeerDiscovery.DiscoveryException("unavailable", "hardware_unavailable");
		host.synchronize(access.limits(), true);
		if (host.sequentialMode()) return CommunicationStatusView.flow(host.flowSnapshot(), host.loaded(), host.diagnostic());
		return CommunicationStatusView.of(host.snapshot(), access.limits(), host.loaded(), host.diagnostic(), transportResult());
	}
    /** Local hardware/service API. Player callers still require the normal physical menu authorization. */
    public com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice serialDevice() { return serial; }
    private java.util.Optional<com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware> residentRadio() {
        var selected = hardware();
        if (!selected.ready() || !selected.limits().serial().ports().containsKey(
                com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware.PORT)) return Optional.empty();
        try {
            var profile = CBConfigs.SERVER.biocomputing.residentRadio(CBConfigs.SERVER_SPEC.isLoaded());
            return profile.supports(selected) ? Optional.of(profile) : Optional.empty();
        } catch (IllegalArgumentException invalidConfig) { return Optional.empty(); }
    }
    public boolean hasResidentRadio() { return residentRadio().isPresent(); }
    private boolean radioLocationAvailable() {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) return false;
        var pos = blockEntity.getBlockPos();
        return level.getWorldBorder().isWithinBounds(pos)
            && com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat.getSpaceId(level, pos) == null;
    }
    /** Server-local menu entry; a packet adapter must still pass the normal physical menu authorization. */
    public void configureRadio(long expectedRevision, int preset, boolean armed) {
        if (expectedRevision != programRevision) throw new IllegalArgumentException("stale");
        requireEndpointAvailable();
        var spec = residentRadio().orElseThrow(() -> new IllegalArgumentException("missing_port"));
        spec.oscillator().reception(preset);
        if (armed && !radioLocationAvailable()) throw new IllegalArgumentException("radio_location_unavailable");
        if (!radio.valid()) throw new IllegalArgumentException("invalid_radio_save");
        if (radio.owned() && radio.preset() == preset && radio.armed() == armed) return;
        serial.configureRadio(armed);
        radio.tune(preset, armed);
        effectiveCarrier = CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);
        programRevision++; markEditorChanged.run();
    }
    /** Called only after this Computer's normal local menu authority has been checked. */
    public void configureCarrierInput(long expectedRevision, Optional<Direction> input) {
        if (expectedRevision != programRevision) throw new IllegalArgumentException("stale");
        requireEndpointAvailable();
        residentRadio().orElseThrow(() -> new IllegalArgumentException("missing_port"));
        if (!radio.valid()) throw new IllegalArgumentException("invalid_radio_save");
        if (input.isPresent() && receiveFace(input.get())) throw new IllegalArgumentException("rx_face_in_use");
        if (radio.carrierInput().equals(input)) return;
        if (input.isPresent() && !radioLocationAvailable()) throw new IllegalArgumentException("radio_location_unavailable");
        // Retune this device session without changing the enable state or complete receive queue.
        serial.configureRadio(radio.armed());
        radio.selectCarrierInput(input);
        effectiveCarrier = CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);
        programRevision++; markEditorChanged.run();
    }

    public Optional<Direction> receiveSend() { return radio.receiveSend(); }
    public boolean receiveFace(Direction face) {
        return receiveSend().filter(send -> send.getAxis() == face.getAxis()).isPresent();
    }
    public void configureReceiveLoop(long revision, Optional<Direction> send) {
        if (revision != programRevision) throw new IllegalArgumentException("stale");
        requireEndpointAvailable();
        if (!hasResidentRadio()) throw new IllegalArgumentException("missing_port");
        if (send.isPresent() && !radioLocationAvailable()) throw new IllegalArgumentException("radio_location_unavailable");
        if (send.isPresent() && carrierInput().filter(f -> f.getAxis() == send.get().getAxis()).isPresent())
            throw new IllegalArgumentException("rx_face_in_use");
        if (receiveSend().equals(send)) return;
        radio.selectReceiveSend(send);
        programRevision++; markEditorChanged.run();
    }
    private static boolean overlapsReceivePair(Optional<Direction> send, Optional<Direction> in, Optional<Direction> out) {
        return send.isPresent() && java.util.stream.Stream.concat(in.stream(),out.stream())
            .anyMatch(face -> face.getAxis() == send.get().getAxis());
    }

    private final WitchReceiverFilter witchFilter = new WitchReceiverFilter();
    private final WitchCarrierStage witchCarrier = new WitchCarrierStage();

    private static CarrierHardwareConfig.Limits carrierLimits() {
        return CBConfigs.SERVER.biocomputing.carrierLimits(CBConfigs.SERVER_SPEC.isLoaded());
    }
    private boolean witchCarrierActive() {
        return witchCarrier.setting().mode() != WitchCarrierStage.Mode.OFF && available.getAsBoolean()
            && WitchReceiverFilter.supports(hardware()) && radioLocationAvailable();
    }
    public Optional<Direction> witchCarrierInput() {
        return witchCarrierActive() ? witchCarrier.setting().input() : Optional.empty();
    }
    public Optional<Direction> witchCarrierOutput() {
        return witchCarrierActive() ? witchCarrier.setting().output() : Optional.empty();
    }
    public CarrierClock.Output advanceWitchCarrier(long tick, CarrierClock.Output reference, CarrierHardwareConfig.Limits limits) {
        if (!witchCarrierActive()) return CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);
        if (witchCarrier.advance(tick, reference, limits)) markEditorChanged.run();
        return witchCarrier.output();
    }
    /** Menu authority is checked by the caller; recheck the revision and physical resident here. */
    public void configureWitchCarrier(long revision, WitchCarrierStage.Setting next) {
        if (revision != programRevision) throw new IllegalArgumentException("stale");
        requireEndpointAvailable();
        if (!WitchReceiverFilter.supports(hardware())) throw new IllegalArgumentException("missing_port");
        if (next.mode() != WitchCarrierStage.Mode.OFF && !radioLocationAvailable())
            throw new IllegalArgumentException("radio_location_unavailable");
        if (overlapsReceivePair(witchOutput().flatMap(WitchReceiverFilter.Setting::output), next.input(), next.output()))
            throw new IllegalArgumentException("rx_face_in_use");
        if (witchCarrier.configure(next, carrierLimits())) { programRevision++; markEditorChanged.run(); }
    }
    private String witchLink = "off";
    private int observedReceiveWidth;
    public void observeWitchFilter(String link, int width) { witchLink = link; observedReceiveWidth = width; }
    public Optional<WitchReceiverFilter.Setting> witchOutput() {
        return witchFilter.setting().output().isPresent() && witchFilter.valid() && !witchFilter.needsRewire()
            && available.getAsBoolean() && WitchReceiverFilter.supports(hardware()) && radioLocationAvailable()
            ? Optional.of(witchFilter.setting()) : Optional.empty();
    }
    public void configureWitchFilter(long revision, WitchReceiverFilter.Setting setting) {
        if (revision != programRevision) throw new IllegalArgumentException("stale");
        requireEndpointAvailable();
        if (!WitchReceiverFilter.supports(hardware())) throw new IllegalArgumentException("missing_port");
        if (setting.output().isPresent() && !radioLocationAvailable()) throw new IllegalArgumentException("radio_location_unavailable");
        if (overlapsReceivePair(setting.output(), witchCarrierInput(), witchCarrierOutput()))
            throw new IllegalArgumentException("rx_face_in_use");
        if (witchFilter.valid() && !witchFilter.needsRewire() && witchFilter.setting().equals(setting)) return;
        witchFilter.configure(setting);
        witchLink = setting.output().isPresent() ? "waiting" : "off";
        programRevision++; markEditorChanged.run();
    }
    public Optional<Direction> carrierInput() { return radio.carrierInput(); }

    /** xo0 belongs to the resident crystal, independent of whether rf0 is armed or the program paused. */
    public Optional<CarrierFrequency> radioCrystal(ResidentRadioHardware profile) {
        if (!radio.valid() || !radioLocationAvailable() || !profile.supports(hardware())) return Optional.empty();
        try { return profile.oscillator().reference(radio.preset()).output(); }
        catch (IllegalArgumentException invalidPreset) { return Optional.empty(); }
    }

    private CarrierClock.Output effectiveCarrier = CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);

    public void residentRadioRemoved() {
        // A changed server profile may already have disabled the outgoing resident.
        if (!witchCarrier.valid() || witchCarrier.setting().mode() != WitchCarrierStage.Mode.OFF
                || WitchReceiverFilter.supports(hardware())) {
            witchCarrier.read(null);
            witchFilter.read(null); observeWitchFilter("off",0); programRevision++; markEditorChanged.run();
        }
        boolean owned = radio.owned();
        if (!owned && !hasResidentRadio()) return;
        if (owned) {
            serial.radioRemoved();
            radio.disarm();
        }
        effectiveCarrier = CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);
        programRevision++; markEditorChanged.run();
    }
    public void residentRadioInstalled() {
        if (hasResidentRadio() || WitchReceiverFilter.supports(hardware())) { programRevision++; markEditorChanged.run(); }
    }
    public Optional<RadioSerialNetwork.Radio> radioStation(ResidentRadioHardware profile,
            Optional<CarrierFrequency> crystal, Optional<CarrierClock.Output> external) {
        effectiveCarrier = external.orElseGet(() -> crystal.map(CarrierClock.Output::valid)
            .orElseGet(() -> CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE)));
        if (!radio.armed()) return Optional.empty();
        if (crystal.isEmpty()) { radio.domainFault("radio_location_unavailable"); return Optional.empty(); }
        return profile.attached(hardware(), serial, blockEntity.getBlockPos(), radio.preset(), external);
    }
    public void observeRadio(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.RadioSerialNetwork.Observation status) {
        radio.observe(status);
    }
    public void observeRadioDomain(String fault) { radio.domainFault(fault); }
    /** Compact local menu projection: preset labels are derived from the finite arithmetic progression. */
    public DataValue.RecordValue radioStatus() {
        var spec = residentRadio();
        var fields = new java.util.LinkedHashMap<>(radio.status(spec.isPresent()).fields());
        fields.put("revision", new DataValue.IntegerValue(programRevision));
        if (WitchReceiverFilter.supports(hardware())) {
            fields.put("witch_filter",witchFilter.setting().encode());
            fields.put("witch_carrier", witchCarrier.setting().encode());
            var limits = carrierLimits();
            fields.put("witch_carrier_max_factor", new DataValue.IntegerValue(limits.maxFactor()));
            fields.put("witch_carrier_default_divisor", new DataValue.IntegerValue(limits.initialFactor(false)));
            fields.put("witch_carrier_default_multiplier", new DataValue.IntegerValue(limits.initialFactor(true)));
            var output = witchCarrierActive() ? witchCarrier.output() : CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);
            fields.put("witch_carrier_state", new DataValue.TextValue(!witchCarrier.valid() ? "invalid"
                : witchCarrier.setting().mode() == WitchCarrierStage.Mode.OFF ? "off"
                : output.state().name().toLowerCase(java.util.Locale.ROOT)));
            output.frequency().ifPresent(f -> fields.put("witch_carrier_frequency", new DataValue.TextValue(f.toString())));
            fields.put("witch_link",new DataValue.TextValue(!witchFilter.valid() ? "invalid" : witchFilter.needsRewire() ? "rewire" : witchLink));
        }
        spec.ifPresent(profile -> {
            fields.put("receive_send", new DataValue.TextValue(receiveSend().map(Direction::getSerializedName).orElse("internal")));
            fields.put("witch_link",new DataValue.TextValue(witchLink));
            fields.put("effective_receive_width",new DataValue.IntegerValue(receiveSend().isPresent()
                ? observedReceiveWidth : profile.oscillator().receiveWidth()));
            fields.put("carrier", new CarrierPortStatus(radio.carrierInput(), effectiveCarrier).encode());
            var oscillator = profile.oscillator();
            var port = serial.status(com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware.PORT);
            fields.put("transmit", new DataValue.TextValue(port.transmit()));
            fields.put("frames", new DataValue.IntegerValue(port.receive().frames()));
            fields.put("preset_count", new DataValue.IntegerValue(oscillator.presets()));
            fields.put("first_center", new DataValue.IntegerValue(oscillator.first()));
            fields.put("center_spacing", new DataValue.IntegerValue(oscillator.spacing()));
            fields.put("transmit_width", new DataValue.IntegerValue(oscillator.transmitWidth()));
            fields.put("receive_width", new DataValue.IntegerValue(oscillator.receiveWidth()));
        });
        return new DataValue.RecordValue(fields);
    }
    private void requireWiredPort(String name) {
        if (com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware.PORT.equals(name)
            && (radio.owned() || hasResidentRadio())) throw new IllegalArgumentException("radio_interface");
    }
    public DataValue.RecordValue serialStatus() {
        var view = serial.view();
        if (radio.owned() || hasResidentRadio()) {
            var wired = new java.util.TreeMap<>(view.ports());
            wired.remove(com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware.PORT);
            view = new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.View(view.diagnostic(), wired);
        }
        var base=com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.encode(view);
        if (!breakpointResident(hardware()))return base;
        String text=breakpoint.captured().orElse("");
        String preview=text.codePoints().limit(160).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
        var bp=new DataValue.RecordValue(java.util.Map.of("xbus",new DataValue.TextValue(hardware().limits().pins().xbus().stream().findFirst().orElse("")),"pin",new DataValue.TextValue(breakpoint.output()),
            "state",new DataValue.TextValue(breakpoint.halted()?"halted":"armed"),
            "outcome",new DataValue.TextValue(breakpoint.outcome().isEmpty() && breakpointBus.failure()!=null ? breakpointBus.failure() : breakpoint.outcome()),"preview",new DataValue.TextValue(preview.length()<text.length()?preview+"…":preview)));
        return new DataValue.RecordValue(java.util.Map.of("serial",base.field("serial"),"breakpoint",bp));
    }
    public void configureSerial(com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.Configuration config) {
        requireWiredPort(config.port());
        requireEndpointAvailable();
        var observed = serial.view();
        if (observed.diagnostic().isPresent()) throw new IllegalArgumentException(observed.diagnostic().orElseThrow());
        var port = observed.ports().get(config.port());
        if (port == null) throw new IllegalArgumentException("missing_port");
        if (port.gateway().isPresent() && port.gateway().orElseThrow().side() != config.side()) throw new IllegalArgumentException("gateway_face_locked");
        if (port.side().filter(config.side()::equals).isPresent() && port.enabled() == config.enabled()) return;
        var status = port.status(); boolean interrupt = status.transmit().equals("queued") || status.transmit().equals("transmitting");
        var preview = new java.util.TreeMap<>(observed.ports());
        preview.put(config.port(), new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.PortView(
            Optional.of(config.side()), config.enabled(), new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.Status(
                interrupt ? "interrupted" : status.transmit(), config.enabled() ? "disconnected" : "closed",
                interrupt ? "configuration_changed" : status.failure(), status.receive()), port.gateway()));
        ProgramEditorWire.encodeValue(com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.encode(
            new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.View(Optional.empty(), preview)), hardware().limits().archive());
        serial.configure(config.port(), config.side(), config.enabled());
        programRevision++; markEditorChanged.run();
    }
    public void configureSerialGateway(String name, Optional<com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayBinding> gateway) {
        requireWiredPort(name);
        requireEndpointAvailable();
        var observed = serial.view();
        if (observed.diagnostic().isPresent()) throw new IllegalArgumentException(observed.diagnostic().orElseThrow());
        var port = observed.ports().get(name);
        if (port == null) throw new IllegalArgumentException("missing_port");
        if (port.gateway().equals(gateway)) return;
        var status = port.status(); boolean interrupt = status.transmit().equals("queued") || status.transmit().equals("transmitting");
        var preview = new java.util.TreeMap<>(observed.ports());
        preview.put(name, new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.PortView(
            gateway.map(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayBinding::side).or(() -> port.side()), false,
            new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.Status(interrupt ? "interrupted" : status.transmit(),
                "closed", interrupt ? "configuration_changed" : status.failure(), status.receive()), gateway));
        ProgramEditorWire.encodeValue(com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.encode(
            new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.View(Optional.empty(), preview)), hardware().limits().archive());
        serial.configureGateway(name, gateway); programRevision++; markEditorChanged.run();
    }
	@Override public void unload() { analyzer.gap(); fisher.gap(); sequencer.suspend(); transferRam.clear(); fileRam.clear(); invalidateSignals(); serial.unload(); breakpointBus.host.unload(); ComputerExecutionDirectory.unregister(this); host.unload(); observeRecentEvents(); }
    private void invalidateSignals() {
        if (blockEntity.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
            com.nobodiiiii.createbiotech.content.biocomputing.network.signal.CableSignalWorldTicks.invalidate(level, blockEntity.getBlockPos());
    }
    public com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections signalConnections() { return signalConnections; }
    private java.util.Map<String,net.minecraft.core.BlockPos> signalContacts=java.util.Map.of();
    public void observeSignalConnections(java.util.Map<String,String> status,java.util.Map<String,net.minecraft.core.BlockPos> contacts) {
        signalConnectionStatus=java.util.Map.copyOf(status); signalContacts=java.util.Map.copyOf(contacts);
    }
    private java.util.Map<String,Integer> cableInputs(java.util.Map<String,Integer> base){
        if(signalConnections.bindings().isEmpty() || !(blockEntity.getLevel() instanceof ServerLevel level))return base;
        var inputs=new java.util.HashMap<>(base);
        signalConnections.bindings().forEach((pin,binding)->{
            if(!binding.enabled())return;
            var pos=signalContacts.get(pin);
            if(pos!=null && "input".equals(signalConnectionStatus.get(pin))) inputs.put(pin,
                com.nobodiiiii.createbiotech.content.biocomputing.network.signal.CableSignalWorldTicks.power(level,pos));
            else if(!"active".equals(signalConnectionStatus.get(pin)))inputs.remove(pin);
        });
        return java.util.Map.copyOf(inputs);
    }
    public DataValue.RecordValue signalStatus() { return signalStatus(signalConnections.bindings()); }
    private DataValue.RecordValue signalStatus(java.util.Map<String, com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.Binding> bindings) {
        var names = new java.util.TreeSet<>(hardware().limits().pins().simpleIo()); names.addAll(bindings.keySet());
        var ports = new java.util.LinkedHashMap<String, DataValue>();
        var outputs = sequencer.armed() ? signalOutputs().orElse(SimpleIoState.EMPTY).outputs()
            : host.loaded() ? host.flowSnapshot().map(s -> s.state().signals().outputs()).orElse(java.util.Map.of()) : java.util.Map.<String, Integer>of();
        for (var pin : names) {
            var binding = bindings.get(pin); boolean enabled = binding != null && binding.enabled();
            ports.put(pin, new DataValue.RecordValue(java.util.Map.of(
                "side", binding == null ? DataValue.NullValue.INSTANCE : new DataValue.TextValue(binding.side().getName()),
                "enabled", new DataValue.BooleanValue(enabled),
                "gateway", binding == null ? DataValue.NullValue.INSTANCE : binding.gateway().<DataValue>map(com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPortBinding::value).orElse(DataValue.NullValue.INSTANCE),
                "level", outputs.containsKey(pin) ? new DataValue.IntegerValue(outputs.get(pin)) : DataValue.NullValue.INSTANCE,
                "status", new DataValue.TextValue(enabled ? signalConnectionStatus.getOrDefault(pin, "pending") : "closed"))));
        }
        return new DataValue.RecordValue(java.util.Map.of("ports", new DataValue.RecordValue(ports),
            "diagnostic", signalConnections.diagnostic().<DataValue>map(DataValue.TextValue::new).orElse(DataValue.NullValue.INSTANCE)));
    }
    public void configureSignal(com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.Configuration config) {
        requireEndpointAvailable();
        if (!hardware().limits().pins().hasSimpleIo(config.pin())) throw new IllegalArgumentException("missing_pin");
        var preview = signalConnections.preview(config);
        ProgramEditorWire.encodeValue(signalStatus(preview), hardware().limits().archive());
        if (preview.equals(signalConnections.bindings())) return;
        signalConnections.configure(config); signalConnectionStatus = java.util.Map.of();
        invalidateSignals();
        programRevision++; markEditorChanged.run();
    }
    public void clearSignals() {
        requireEndpointAvailable();
        if (signalConnections.write() == null) return;
        signalConnections.clear(); signalConnectionStatus = java.util.Map.of(); programRevision++; markEditorChanged.run();
        invalidateSignals();
    }
    public void configureSignalGateway(String pin, Optional<com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPortBinding> gateway) {
        requireEndpointAvailable();
        if (!hardware().limits().pins().hasSimpleIo(pin)) throw new IllegalArgumentException("missing_pin");
        var preview = signalConnections.previewGateway(pin, gateway);
        ProgramEditorWire.encodeValue(signalStatus(preview), hardware().limits().archive());
        if (preview.equals(signalConnections.bindings())) return;
        signalConnections.configureGateway(pin, gateway); signalConnectionStatus = java.util.Map.of();
        invalidateSignals(); programRevision++; markEditorChanged.run();
    }
	@Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (!clientPacket) { recentEvents.clear(); logAvailable = null; logEndpoint = Optional.empty(); transportResult = Optional.empty(); transportContext = null;
            transferRam.clear(); fileRam.clear();
            sequencer.read(tag.get(VindicatorSequencer.ROOT));
            breakpoint.load(tag.get(BREAKPOINT_ROOT));breakpointBus.load(tag.get(BREAKPOINT_BUS_ROOT));
            analyzer.load(tag.get(ANALYZER_ROOT));
            fisher.load(tag.get(FisherListener.ROOT));
            savedDraft.read(tag.get(DRAFT_ROOT));
			clusterAttachment.read(tag.get(ATTACHMENT_ROOT));
            radio.read(tag.get(RADIO_ROOT));
            witchFilter.read(tag.get(WitchReceiverFilter.ROOT)); observeWitchFilter("off",0);
            witchCarrier.read(tag.get(WitchCarrierStage.ROOT));
            serial.read(tag.get(SERIAL_ROOT));
            installation = tag.contains(INSTALLATION_ROOT) ? tag.get(INSTALLATION_ROOT).copy() : null;
            signalConnections.read(tag.get(SIGNAL_ROOT)); signalConnectionStatus = java.util.Map.of();
			endpoint.read(tag.get(ENDPOINT_ROOT)); host.read(tag.get(ROOT)); editorDurable = tag.get(EDITOR_ROOT) == null ? null : tag.get(EDITOR_ROOT).copy();
			activeDocument = null; pendingDocument = null; activeEncoded = pendingEncoded = null;
			editorDecoded = false; editorValidated = false; editorDiagnostic = null; programRevision++; observeProgram(); }
	}
	@Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ANALYZER_ROOT);
        if(!clientPacket){var trace=analyzer.save();if(trace!=null)tag.put(ANALYZER_ROOT,trace);}
        tag.remove(WitchReceiverFilter.ROOT);
        tag.remove(WitchCarrierStage.ROOT);
        if (!clientPacket) { var stage = witchCarrier.write(); if (stage != null) tag.put(WitchCarrierStage.ROOT, stage); }
        if (!clientPacket) { var filter = witchFilter.write(); if (filter != null) tag.put(WitchReceiverFilter.ROOT,filter); }
        tag.remove(VindicatorSequencer.ROOT);
        if (!clientPacket) { var state = sequencer.save(); if (state != null) tag.put(VindicatorSequencer.ROOT,state); }
        tag.remove(BREAKPOINT_BUS_ROOT);
        if(!clientPacket){var wire=breakpointBus.save();if(wire!=null)tag.put(BREAKPOINT_BUS_ROOT,wire);}
        tag.remove(BREAKPOINT_ROOT);
        if (!clientPacket) { var state=breakpoint.save(); if(state!=null)tag.put(BREAKPOINT_ROOT,state); }
        tag.remove(FisherListener.ROOT);
        if (!clientPacket) { var data=fisher.save(); if(data!=null)tag.put(FisherListener.ROOT,data); }
        tag.remove(DRAFT_ROOT);
        if (!clientPacket) { var draft = savedDraft.write(); if (draft != null) tag.put(DRAFT_ROOT, draft); }
        tag.remove(INSTALLATION_ROOT);
        if (clientPacket) { tag.remove(ROOT); tag.remove(EDITOR_ROOT); tag.remove(ENDPOINT_ROOT); tag.remove(ATTACHMENT_ROOT); tag.remove(SERIAL_ROOT); tag.remove(RADIO_ROOT); tag.remove(SIGNAL_ROOT); return; }
        if (installation != null) tag.put(INSTALLATION_ROOT, installation.copy());
        var signalData = signalConnections.write();
        if (signalData == null) tag.remove(SIGNAL_ROOT); else tag.put(SIGNAL_ROOT, signalData);
        tag.remove(RADIO_ROOT);
        var radioData = radio.write();
        if (radioData != null) tag.put(RADIO_ROOT, radioData);
        var serialData = serial.write();
        if (serialData == null) tag.remove(SERIAL_ROOT); else tag.put(SERIAL_ROOT, serialData);
		var connection = clusterAttachment.write();
		if (connection == null) tag.remove(ATTACHMENT_ROOT); else tag.put(ATTACHMENT_ROOT, connection);
		var endpointData = endpoint.write();
		if (endpointData == null) tag.remove(ENDPOINT_ROOT); else tag.put(ENDPOINT_ROOT, endpointData);
		var data = host.write();
		if (data == null) tag.remove(ROOT); else tag.put(ROOT, data);
		if (editorDurable == null) tag.remove(EDITOR_ROOT); else tag.put(EDITOR_ROOT, editorDurable.copy());
	}
	// isSafeNBT remains false: a schematic must not implicitly duplicate Computer identity or RAM.
	    /** Stable installed-image identity only. No draft, program, inputs, locals or diagnostic payload. */
    public record InstalledProgram(UUID computer, UUID installation, boolean paused) {}
    private record InstalledOwner(UUID computer, boolean paused) {}
    public Optional<InstalledProgram> installedProgram() {
        if (!(installation instanceof CompoundTag tag) || !tag.getAllKeys().equals(java.util.Set.of("Version", "Id"))
            || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1 || !tag.hasUUID("Id")) return Optional.empty();
        UUID id = tag.getUUID("Id");
        return installedOwner().map(owner -> new InstalledProgram(owner.computer(), id, owner.paused()));
    }
    private Optional<InstalledOwner> installedOwner() {
        var access = access();
        if (!host.synchronize(access.limits(), access.available())) return Optional.empty();
        return host.sequentialMode() ? host.flowSnapshot()
            .map(saved -> new InstalledOwner(saved.state().computer(), saved.state().paused()))
            : host.snapshot().filter(saved -> saved.program() != null)
                .map(saved -> new InstalledOwner(saved.id(), saved.machine().paused()));
    }
    private void migrateInstallation() {
        // Missing is the only legacy form. Present malformed metadata is never silently repaired.
        if (installation == null && installedOwner().isPresent()) newInstallation();
    }
    private void newInstallation() {
        fisher.clear();
        var tag = new CompoundTag(); tag.putInt("Version", 1); tag.putUUID("Id", UUID.randomUUID());
        installation = tag; markEditorChanged.run();
    }
    public Optional<ComputerRuntime.Snapshot> snapshot() { return host.snapshot(); }
    public void updateTerminalKeys(java.util.UUID source, long bits) {
        if (bits == 0 || access().available()) host.updateTerminalKeys(source, bits);
    }
    public String pressTerminalKey(String key) {
        var access = access(); prepareEditor(access);
        try {
            return host.pressTerminalKey(key, access.limits(), access.available());
        } catch (com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException limit) {
            throw new ComputationException(ComputationException.Code.MEMORY_LIMIT, "terminal", limit.getMessage());
        }
    }
    private boolean breakpointReceiveEnabled(){return !trapMode() || (!breakpoint.halted() && breakpointBus.input().isEmpty() && !serial.hasWiredInput());}
    private boolean trapWireAvailable(){return available.getAsBoolean() && breakpointBus.verified() && (breakpoint.halted() || !serial.hasWiredInput());}
    private boolean trapMode(){return breakpointResident(hardware());}
    private record Wire(ComputerBlockHost host, Access access) {}
    // Pick a single resident and budget for this logical operation, not once per argument.
    private Wire wire() {
        var selected = hardware();
        return breakpointResident(selected)
            ? new Wire(breakpointBus.host, new Access(BreakpointBus.limits(selected.limits()), trapWireAvailable()))
            : new Wire(host, access(selected));
    }
    // The post-prepare XBus barrier must not release other callers' reservations.
    private ComputerHostLimits observedWireLimits(HardwareSelection selected) {
        var limits = selected.limits();
        if (breakpointResident(selected)) return BreakpointBus.limits(limits);
        long reserved = (long) fileRam.reservedBytes() + transferRam.reservedBytes();
        return limits.withMemoryUnits(Math.max(0L, limits.hardware().memoryUnits() - reserved));
    }
    public Optional<FlowBlockHost.Saved> wireSnapshot(){return trapMode() ?
        (breakpointBus.canRun(breakpoint)?breakpointBus.host.flowSnapshot():Optional.empty()) : host.flowSnapshot();}
    public Optional<FlowBlockHost.Saved> flowSnapshot() { return host.flowSnapshot(); }
    /** Local, authorized service seam. Not a remote UUID-based task or RAM access capability. */
    public UUID startFlowTask(com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key entry, DataValue.RecordValue arguments) {
        var access = access(); return host.startFlowTask(entry, arguments, access.limits(), access.available());
    }
    public Optional<FlowRuntime.Checkpoint> flowTask(UUID task) { return host.flowTask(task); }
    public FlowRuntime.Checkpoint claimFlowTask(UUID task) {
        var access = access(); return host.claimFlowTask(task, access.limits(), access.available());
    }
	public boolean beginFlowTick(long tick) {
		if (scheduledTick == tick) return false;
		var wire = wire();
		if (!wire.host().beginFlowTick(tick, wire.access().limits(), wire.access().available())) return false;
		scheduledTick = tick; sampleFisher(tick, wire.access()); return true;
	}
	public long advanceFlowRound(long tick, java.util.Map<String, Integer> inputs) {
		var wire = wire();
		return wire.host().advanceFlowRound(tick, inputs, wire.access().limits(), wire.access().available());
	}
	/** Full debugger/standalone snapshot; batch execution may consume more than one instruction. */
	public java.util.Map<String, Integer> sampleFlowSignals() {
		var access = access();
		return withXBusStatus(signalController().map(controller -> controller.signalInputs(this)).orElseGet(() -> localSignalInputs(access)), access);
	}
	/** Coordinator-only sampling, before any machine executes its one instruction in this round. */
	public java.util.Map<String, Integer> sampleFlowRoundSignals() {
		if(trapMode())return java.util.Map.of();
		var reads = host.roundSignalReads();
		if (reads.isEmpty()) return java.util.Map.of();
		var access = access();
		var signals = reads.stream().anyMatch(use -> use.kind() ==
			com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterDevices.Kind.SIO_READ)
			? signalController().map(controller -> controller.signalInputs(this)).orElseGet(() -> localSignalInputs(access))
			: java.util.Map.<String, Integer>of();
		return withXBusStatus(signals, access,
			com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowDevices.statusPins(reads));
	}
	/** Sample only status pins used by the installed program. The keys never become driven outputs. */
	private java.util.Map<String, Integer> withXBusStatus(java.util.Map<String, Integer> signals, Access access) {
		return withXBusStatus(signals, access, host.xbusStatusPins());
	}
	private java.util.Map<String, Integer> withXBusStatus(java.util.Map<String, Integer> signals, Access access, java.util.Set<String> pins) {
		signals=cableInputs(signals);
		var saved = host.flowSnapshot();
		if (saved.isEmpty()) return signals;
		var sampled = new java.util.LinkedHashMap<>(signals);
		if (pins.isEmpty()) return signals;
		for (var pin : pins) {
			int code = 0;
			if (access.available() && access.limits().pins().hasXBus(pin) && !saved.orElseThrow().state().paused()
				&& blockEntity.getLevel() instanceof ServerLevel level) {
				code = XBusConnectedNetwork.readStatus(level, this, new XBusNetwork.Pin(saved.orElseThrow().state().computer(), pin));
			}
			sampled.put(pin, code);
		}
		return java.util.Map.copyOf(sampled);
	}
	/** Hardware signal state only; not a view into another Computer's locals. */
	public Optional<SimpleIoState> signalOutputs() {
		var selected = hardware(); var access = access(selected);
        if (!host.synchronize(access.limits(),access.available())) return Optional.empty();
        var ordinary = host.flowSnapshot().map(s -> s.state().signals()).orElse(SimpleIoState.EMPTY);
        if (sequencer.armed() && VindicatorSequencer.supports(selected) && selected.limits().pins().hasSimpleIo(sequencer.pin()))
            return Optional.of(sequencer.mux(ordinary,sequencerChunkTicking() && !sequencerPaused()));
        return Optional.of(ordinary);
	}
	public Optional<SimpleIoNetwork.Port> signalPort(String endpointName, String pin) {
		if (advertisement().filter(c -> c.name().equals(endpointName)).isEmpty() || !hardware().limits().pins().hasSimpleIo(pin)) return Optional.empty();
		return signalOutputs().map(state -> state.outputs().containsKey(pin) ? SimpleIoNetwork.Port.output(state.outputs().get(pin)) : SimpleIoNetwork.Port.input());
	}
    /** Authorized local debugger projection; unavailable hosts never publish private child state. */
    public Optional<FlowBlockHost.Saved> taskDebugSnapshot() {
        var access = access(); prepareEditor(access);
        return host.loaded() ? host.flowSnapshot() : Optional.empty();
    }
	/** Authorized editor observation; contains local values but no ids, receipts or other Computer state. */
	public Optional<DataValue.RecordValue> flowStatus() {
		var access = access(); prepareEditor(access);
		return host.sequentialMode() ? Optional.of(ComputerStatusView.flow(host.flowSnapshot(), host.loaded(), diagnostic(), host.canResetResourceFailure())) : Optional.empty();
	}
	/** @deprecated Internal compatibility entrypoint; use installDocument for source-bound installation. */
	@Deprecated
	public void installFlow(FlowProgram program, DataValue.RecordValue inputs) {
		var access = access();
		host.installFlow(Objects.requireNonNull(program), Objects.requireNonNull(inputs), access.limits(), access.available());
        newInstallation();
		// Explicit mode replacement; no retained event document may edit or drive the new flow.
		editorDurable = null; activeDocument = pendingDocument = null; activeEncoded = pendingEncoded = null;
		editorDecoded = editorValidated = true; editorDiagnostic = null;
		transportResult = Optional.empty(); transportContext = null;
		programRevision++; observeProgram(); markEditorChanged.run();
	}
	@Override public Optional<XBusPortState> xbusPort(String endpointName, String pin) {
		var wire = wire(); var host = wire.host(); var access = wire.access();
		if (!access.available() || endpoint.configuration().filter(c -> c.enabled() && c.name().equals(endpointName)).isEmpty()
			|| !host.sequentialMode() || !host.synchronize(access.limits(), true)) return Optional.empty();
		return wireSnapshot().flatMap(saved -> XBusPortState.of(saved, access.limits().pins(), pin));
	}
	@Override public java.util.List<String> xbusPins(String endpointName) {
		var wire = wire(); var host = wire.host(); var access = wire.access();
		if (!access.available() || endpoint.configuration().filter(c -> c.enabled() && c.name().equals(endpointName)).isEmpty()
			|| !host.sequentialMode() || !host.synchronize(access.limits(), true)) return java.util.List.of();
		return access.limits().pins().xbus();
	}
	@Override public Optional<XBusAccess> xbusAccess(String endpointName, XBusPortState expected) {
		Objects.requireNonNull(expected);
		var wire = wire(); var host = wire.host();
		var selectedLimits = wire.access().limits();
		var result = new XBusAccess(host, expected, () -> selectedLimits, () -> {
			var current = wire();
			if (!current.access().available() || host != current.host()) return Optional.of(XBusExchange.Status.UNAVAILABLE);
			if (endpoint.configuration().filter(c -> c.enabled() && c.name().equals(endpointName)).isEmpty())
				return Optional.of(XBusExchange.Status.CLOSED);
			if (xbusPort(endpointName, expected.pin()).filter(expected::equals).isEmpty()) return Optional.of(XBusExchange.Status.STALE);
			return Optional.empty();
		}).validated(() -> {
			// Local read-only hardware sources, not the potentially restoring xbusPort/communicationIdentity methods.
			var selected = hardware.get();
			if (!available.getAsBoolean() || !selected.ready() || breakpointResident(selected) && (!trapWireAvailable() || !breakpointBus.canRun(breakpoint)) || !host.loaded()) return Optional.of(XBusExchange.Status.UNAVAILABLE);
			if (!observedWireLimits(selected).equals(selectedLimits) || host != (breakpointResident(selected) ? breakpointBus.host : this.host)) return Optional.of(XBusExchange.Status.STALE);
			return endpoint.configuration().filter(c -> c.enabled() && c.name().equals(endpointName)).isEmpty()
				? Optional.of(XBusExchange.Status.CLOSED) : Optional.empty();
		});
		if (blockEntity.getLevel() instanceof ServerLevel level) {
			try {
				var config = CBConfigs.SERVER.biocomputing;
				result = result.historical(XBusHistorySavedData.get(level),
					() -> config.xbusHistoryComputerLimit(CBConfigs.SERVER_SPEC.isLoaded()),
					() -> config.xbusHistoryClaimLimit(CBConfigs.SERVER_SPEC.isLoaded()));
			} catch (RuntimeException unavailableHistory) { return Optional.of(XBusAccess.denied(XBusExchange.Status.UNAVAILABLE)); }
		}
		return Optional.of(result);
	}
	/** Identity-only observation for named communication bindings; geometric membership does not require it. */
	@Override public Optional<java.util.UUID> communicationIdentity() {
		var wire = wire(); var host = wire.host(); var access = wire.access();
		if (!host.synchronize(access.limits(), access.available())) return Optional.empty();
		return host.sequentialMode() ? host.flowSnapshot().map(saved -> saved.state().computer()) : host.snapshot().map(ComputerRuntime.Snapshot::id);
	}
	/** Call after communicationIdentity() returned empty. A retained, invalid installation is not an absent program. */
	public String missingCommunicationIdentityCode() {
		return host.diagnostic().map(value -> value.code().name().toLowerCase(java.util.Locale.ROOT)).orElse("no_program");
	}
	public Optional<ClusterAttachment> clusterAttachment() { return clusterAttachment.attachment(); }
	/** Configuration guard: expose one lifecycle bit, never another Computer's RAM or program. */
	public boolean communicationQuiescent() {
        if(trapMode()) return breakpointBus.verified() && breakpointBus.host.flowSnapshot().filter(s ->
            s.receipts().isEmpty() && s.tasks().contexts().isEmpty() &&
            (!breakpoint.halted() || !breakpoint.outcome().equals("xbus_waiting"))).isPresent();
		var access = access();
		if (!host.synchronize(access.limits(), access.available())) return false;
		if (host.sequentialMode()) return host.flowSnapshot().map(saved -> saved.tasks().contexts().isEmpty() && saved.receipts().isEmpty() && (saved.state().status() == FlowRuntime.Status.COMPLETE
			|| saved.state().status() == FlowRuntime.Status.FAILED || saved.state().status() == FlowRuntime.Status.READY && saved.state().work() == 0)).orElse(false);
		var state = host.snapshot();
		return state.isPresent() && state.get().machine().status() != ComputationMachine.Status.RUNNING
			&& state.get().mailbox().queued().isEmpty() && state.get().rpc().pending().isEmpty()
			&& state.get().rpc().staged().isEmpty() && state.get().pending().isEmpty()
			&& host.outbound(access.limits(), access.available()).isEmpty();
	}
	public Optional<String> clusterAttachmentDiagnostic() { return clusterAttachment.diagnostic(); }
	/** Trusted physical configuration service only; not exposed as a computation effect. */
	public void attachCluster(ClusterAttachment attachment) { clusterAttachment.configure(attachment); markEditorChanged.run(); }
	public void detachCluster(java.util.UUID controller) {
		if (clusterAttachment.attachment().filter(a -> a.controller().equals(controller)).isPresent()) {
			clusterAttachment.clear(); markEditorChanged.run();
		}
	}
	public Optional<ComputerBlockHost.Diagnostic> diagnostic() { return host.diagnostic().or(() -> Optional.ofNullable(editorDiagnostic)); }
	public Optional<ProgramDocument> document() {
		var access = access(); prepareEditor(access);
		if (pendingDocument != null) return Optional.of(pendingDocument);
		if (activeDocument != null) return Optional.of(activeDocument);
		if (host.sequentialMode()) return host.flowSource().map(saved -> new ProgramDocument(saved.program().expressions(), java.util.Map.of(),
			Optional.empty(), Optional.of(com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody.fromProgram(
				saved.program(), saved.initialInputs()))));
		return host.snapshot().map(s -> s.pending().map(ComputerRuntime.PendingInstallation::program).orElse(s.program())).map(ProgramDocument::of);
	}
	/** Authorized active presentation observation; never advances the machine or reads peripherals. */
	public DataValue.RecordValue terminalStatus() {
		var access = access(); prepareEditor(access);
		if (!access.available()) throw new IllegalArgumentException("Computer hardware unavailable");
		if (diagnostic().isPresent()) throw new IllegalArgumentException(diagnostic().orElseThrow().message());
        if (host.sequentialMode()) {
            var status = com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.ofFlowOwner(
                Optional.ofNullable(activeDocument).flatMap(ProgramDocument::terminal), host.flowSnapshot());
            var question = host.terminalQuestion();
            if (question.isEmpty()) return status;
            var fields = new java.util.LinkedHashMap<>(status.fields());
            var current = question.orElseThrow();
            fields.put("question", new DataValue.RecordValue(java.util.Map.of("token", new DataValue.TextValue(current.token()), "text", current.text())));
            return new DataValue.RecordValue(fields);
        }
		var observed = host.snapshot();
		return com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.of(
			Optional.ofNullable(activeDocument).flatMap(ProgramDocument::terminal), observed);
	}
    public void answerTerminal(String token, String text) {
        var access = access();
        host.answerTerminal(token, text, access.limits(), access.available());
    }
	/** Read-only physical identity and effective server budgets, never the captured entity data. */
	public HardwareSelection hardware() { return hardware.get(); }
	/** Read authorization samples physical hardware without restoring, ticking or changing RAM. */
	public boolean peripheralAvailable() { return available.getAsBoolean() && hardware().ready(); }
	/** Discovery samples only physical readiness and independently saved endpoint metadata. */
	@Override public Optional<EndpointConfiguration> advertisement() {
		return peripheralAvailable() ? endpoint.configuration().filter(EndpointConfiguration::enabled) : Optional.empty();
	}
	@Override public java.util.List<EndpointConfiguration> advertisements() {
		return peripheralAvailable() ? endpoint.configuration().stream().toList() : java.util.List.of();
	}
	public void requireEndpointAvailable() {
		if (!peripheralAvailable()) throw new PeerDiscovery.DiscoveryException("unavailable", "hardware_unavailable");
	}
	public DataValue.RecordValue endpointStatus() {
		requireEndpointAvailable();
		return endpointStatus(endpoint.configuration(), endpoint.diagnostic());
	}
	private static DataValue.RecordValue endpointStatus(Optional<EndpointConfiguration> config, Optional<String> diagnostic) {
		return new DataValue.RecordValue(java.util.Map.of(
			"endpoint", config.<DataValue>map(EndpointConfiguration::value).orElse(DataValue.NullValue.INSTANCE),
			"endpoint_diagnostic", diagnostic.<DataValue>map(DataValue.TextValue::new).orElse(DataValue.NullValue.INSTANCE)));
	}
	public void configureEndpoint(EndpointConfiguration config) {
		requireEndpointAvailable(); Objects.requireNonNull(config);
		ProgramEditorWire.encodeValue(endpointStatus(Optional.of(config), Optional.empty()), hardware().limits().archive());
		endpoint.configure(config); endpointChanged();
	}
	public void disableEndpoint() { requireEndpointAvailable(); endpoint.disable(); endpointChanged(); }
	public void clearEndpoint() { requireEndpointAvailable(); endpoint.clear(); endpointChanged(); }
	private void endpointChanged() { programRevision++; markEditorChanged.run(); observeRecentEvents(); }
	public long programRevision() {
		// Observe only when an editor needs a fence, not a graph comparison on every RAM tick.
		if (observeProgram()) programRevision++;
		return programRevision;
	}
	private boolean observeProgram() {
		var state = host.snapshot();
		var program = state.map(ComputerRuntime.Snapshot::program).orElse(null);
		var pending = state.flatMap(ComputerRuntime.Snapshot::pending);
		boolean changed = !Objects.equals(program, observedProgram) || !pending.equals(observedPending);
		observedProgram = program; observedPending = pending;
		return changed;
	}
	public ComputerRuntime.InstallResult install(ProgramArchive archive, ComputerRuntime.MemoryPolicy policy) {
		return installDocument(ProgramDocument.of(archive), policy);
	}
	public ComputerRuntime.InstallResult installDocument(ProgramDocument document, ComputerRuntime.MemoryPolicy policy) {
		Objects.requireNonNull(document); var access = access(); prepareEditor(access);
		var prepared = PreparedProgram.prepare(document, access.limits());
		var encoded = ValueNbtCodec.encode(ProgramDocumentCodec.encode(document), access.limits().archive());
		var existing = activeDocument != null ? activeDocument : host.snapshot().map(ComputerRuntime.Snapshot::program).map(ProgramDocument::of).orElse(null);
		var existingEncoded = activeEncoded;
		if (existingEncoded == null && existing != null) {
			ProgramDocumentWire.encode(existing, access.limits().archive());
			existingEncoded = ValueNbtCodec.encode(ProgramDocumentCodec.encode(existing), access.limits().archive());
		}
		var result = host.installProgram(prepared, policy, access.limits(), access.available());
		if (prepared.installationPolicy() == PreparedProgram.InstallationPolicy.RESET_PAUSED) {
			transportResult = Optional.empty(); transportContext = null;
		}
		if (result == ComputerRuntime.InstallResult.APPLIED) {
			newInstallation();
            activeDocument = document; activeEncoded = encoded; pendingDocument = null; pendingEncoded = null;
		} else {
			activeDocument = existing; activeEncoded = existingEncoded; pendingDocument = document; pendingEncoded = encoded;
		}
		publishEditor(); editorDiagnostic = null; editorValidated = true;
		programRevision++; observeProgram(); return result;
	}
	public void begin(DataValue.RecordValue inputs) {
		var access = access(); prepareEditor(access); var before = host.snapshot();
		try { host.begin(inputs, access.limits(), access.available()); } finally { reconcileDocuments(before, false); }
	}
	public void beginLocal(String entry, DataValue.RecordValue inputs) {
		var access = access(); prepareEditor(access);
		if (pendingDocument != null) throw new ComputationException(ComputationException.Code.BUSY, "terminal", "A program installation is pending");
		if (activeDocument == null || !activeDocument.archive().program().entries().containsKey(entry))
			throw new IllegalArgumentException("Unknown local event entry: " + entry);
		host.beginLocal(entry, inputs, access.limits(), access.available());
	}
    /** The menu already resolved a one-use frozen interaction against the installed document. */
    public void beginTerminal(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalInteraction.Invocation invocation) {
        if (invocation.target() instanceof com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalTarget.Event event) {
            beginLocal(event.entry(), invocation.inputs()); return;
        }
        var access = access(); prepareEditor(access);
        if (activeDocument == null || !host.sequentialMode() || diagnostic().isPresent()) throw new IllegalArgumentException("No available Flow terminal program");
        activeDocument.validateTerminalTargets();
        if (host.flowSnapshot().orElseThrow().state().paused()) throw new ComputationException(ComputationException.Code.BUSY, "terminal", "Computer is paused");
        var target = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalTarget.Task)invocation.target();
        host.startFlowTask(target.procedure(), invocation.inputs(), access.limits(), access.available());
    }
	public void step() {
		var access = access(); prepareEditor(access); var before = host.snapshot();
		var signals = sampleFlowSignals();
		try { host.step(access.limits(), access.available(), signals); } finally { reconcileDocuments(before, false); }
	}
	public void setPaused(boolean paused) { var access = access(); host.setPaused(paused, access.limits(), access.available());
        if (paused) fisher.gap();
        if (paused && sequencer.armed()) { sequencer.suspend(); invalidateSignals(); }
    }
	public void configurePeriodic(long intervalTicks, DataValue.RecordValue inputs) {
		var access = access(); prepareEditor(access);
		// The entire saved-input reply must fit before any persistent state is changed.
		var schedule = new PeriodicSchedule(intervalTicks, intervalTicks, true, inputs);
		ProgramEditorWire.encodeValue(ComputerStatusView.periodic(Optional.of(schedule)), access.limits().archive());
		host.configurePeriodic(intervalTicks, inputs, access.limits(), access.available()); programRevision++;
	}
	public void disablePeriodic() {
		var access = access(); host.disablePeriodic(access.limits(), access.available()); programRevision++;
	}
	public void clearPeriodic() {
		var access = access(); host.clearPeriodic(access.limits(), access.available()); programRevision++;
	}
	public void observePeriodic() {
		var access = access(); prepareEditor(access); host.observe(access.limits(), access.available());
	}
	public void reset() {
        if (stoppedByBreakpoint()) {resetBreakpoint();return;}
        transferRam.clear(); fileRam.clear();
		var access = access(); prepareEditor(access); var before = host.snapshot();
		try { host.reset(access.limits(), access.available()); } finally { reconcileDocuments(before, true); }
	}

	private void reconcileDocuments(Optional<ComputerRuntime.Snapshot> before, boolean reset) {
		if (before.flatMap(ComputerRuntime.Snapshot::pending).isEmpty()) return;
		var snapshot = host.snapshot(); if (snapshot.isEmpty() || snapshot.get().pending().isPresent()) return;
		boolean applied = !reset && snapshot.get().installationDiagnostic().isEmpty();
        if (applied) newInstallation();
        if (!editorValidated || pendingDocument == null) return;
        if (applied) { activeDocument = pendingDocument; activeEncoded = pendingEncoded; }
		pendingDocument = null; pendingEncoded = null;
		publishEditor();
	}
	private void prepareEditor(Access access) {
		if (!host.synchronize(access.limits(), access.available()) && !(access.available() && host.flowSource().isPresent()
			&& host.diagnostic().filter(d -> d.code() == ComputerBlockHost.Code.HARDWARE_MISMATCH).isPresent())) return;
		decodeEditor(access); validateEditorAgainstRuntime(access);
	}
	private void validateEditorAgainstRuntime(Access access) {
		if (editorValidated || editorDiagnostic != null || (activeDocument == null && pendingDocument == null)) return;
		if (host.sequentialMode()) {
			var saved = host.flowSource(); if (saved.isEmpty()) return;
            boolean terminalValid = false;
            if (activeDocument != null) {
                try { activeDocument.validateTerminalTargets(); terminalValid = true; }
                catch (IllegalArgumentException | ComputationException invalid) { /* Retain the existing invalid-document diagnostic path. */ }
            }
			if (activeDocument != null && activeDocument.flow().isPresent() && pendingDocument == null
				&& activeDocument.flow().orElseThrow().program(activeDocument.archive()).equals(saved.orElseThrow().program())
				&& activeDocument.flow().orElseThrow().initial().equals(saved.orElseThrow().initialInputs())
				&& terminalValid) {
				editorValidated = true;
			} else {
				editorDiagnostic = new ComputerBlockHost.Diagnostic(ComputerBlockHost.Code.INVALID_SAVE, "Editor document does not match installed flow");
				activeDocument = pendingDocument = null; activeEncoded = pendingEncoded = null;
			}
			return;
		}
		var snapshot = host.snapshot(); if (snapshot.isEmpty()) return;
		try {
			if (activeDocument != null && activeDocument.flow().isPresent() || pendingDocument != null && pendingDocument.flow().isPresent())
				throw new IllegalArgumentException("Flow document cannot describe an event runtime");
			var activeCanonical = activeDocument == null ? null : ProgramArchive.from(activeDocument.archive().compile(access.limits().hardware()));
			var pendingCanonical = pendingDocument == null ? null : ProgramArchive.from(pendingDocument.archive().compile(access.limits().hardware()));
			boolean activeMatches = Objects.equals(activeCanonical, snapshot.get().program());
			boolean pendingMatches = snapshot.get().pending().map(ComputerRuntime.PendingInstallation::program).map(program -> Objects.equals(program, pendingCanonical)).orElse(pendingCanonical == null);
			if (!activeMatches || !pendingMatches) throw new IllegalArgumentException("Editor documents do not match runtime archive");
			editorValidated = true;
		} catch (RuntimeException error) {
			editorDiagnostic = new ComputerBlockHost.Diagnostic(ComputerBlockHost.Code.INVALID_SAVE, "Invalid editor metadata: " + error.getMessage());
			activeDocument = null; pendingDocument = null; activeEncoded = pendingEncoded = null;
		}
	}
	private void decodeEditor(Access access) {
		if (editorDecoded || editorDurable == null) { editorDecoded = true; return; }
		try {
			if (!(editorDurable instanceof CompoundTag tag) || tag.getInt("Version") != 1) throw new IllegalArgumentException("Invalid editor metadata");
			if (tag.getAllKeys().stream().anyMatch(key -> !java.util.Set.of("Version", "Active", "Pending").contains(key))
				|| tag.contains("Active") && !tag.contains("Active", Tag.TAG_COMPOUND)
				|| tag.contains("Pending") && !tag.contains("Pending", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid editor fields");
			if (tag.contains("Active", Tag.TAG_COMPOUND)) {
				activeDocument = ProgramDocumentCodec.decode(ValueNbtCodec.decode(tag.getCompound("Active"), access.limits().archive())); activeEncoded = tag.getCompound("Active").copy();
			}
			if (tag.contains("Pending", Tag.TAG_COMPOUND)) {
				pendingDocument = ProgramDocumentCodec.decode(ValueNbtCodec.decode(tag.getCompound("Pending"), access.limits().archive())); pendingEncoded = tag.getCompound("Pending").copy();
			}
			editorDecoded = true;
		} catch (RuntimeException error) {
			editorDiagnostic = new ComputerBlockHost.Diagnostic(ComputerBlockHost.Code.INVALID_SAVE, "Invalid editor metadata: " + error.getMessage());
			// Deliberately leave the opaque tag untouched; a later successful install replaces it.
			editorDecoded = true; activeDocument = null; pendingDocument = null; activeEncoded = pendingEncoded = null;
		}
	}
	private void publishEditor() {
		// Use prevalidated encodings: applying/resetting pending must not fail after runtime publication.
		var tag = new CompoundTag(); tag.putInt("Version", 1);
		if (activeEncoded != null) tag.put("Active", activeEncoded);
		if (pendingEncoded != null) tag.put("Pending", pendingEncoded);
		editorDurable = tag; editorDecoded = true; markEditorChanged.run();
	}

	private Access access() { return access(hardware()); }
	private Access access(HardwareSelection selected) {
        var limits = selected.limits();
        long capacity = limits.hardware().memoryUnits();
        if (!selected.ready() || (long)fileRam.reservedBytes() + transferRam.reservedBytes() > capacity) {
            transferRam.clear(); fileRam.clear();
        }
		return new Access(limits.withMemoryUnits(capacity - fileRam.reservedBytes() - transferRam.reservedBytes()), available.getAsBoolean() && selected.ready() && !breakpointResident(selected));
	}
	private Optional<com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity> signalController() {
		if (!(blockEntity.getLevel() instanceof ServerLevel level)) return Optional.empty();
		return clusterAttachment.attachment().flatMap(binding -> level.hasChunkAt(binding.position())
			&& level.getBlockEntity(binding.position()) instanceof com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity controller
			&& controller.controllerId().equals(binding.controller()) ? Optional.of(controller) : Optional.empty());
	}
	/** Fallback only. Bound signals cannot silently become unwired zero when the coordinator fails. */
	private java.util.Map<String, Integer> localSignalInputs(Access access) {
		return clusterAttachment.attachment().isPresent() || clusterAttachment.diagnostic().isPresent()
			? java.util.Map.of() : access.limits().pins().unconnectedSignals();
	}
	private static Supplier<HardwareSelection> configuredHardware(Supplier<ItemStack> resident) {
		var source = new CapturedResidentHardware(resident);
		return () -> source.select(CBConfigs.SERVER.biocomputing, CBConfigs.SERVER_SPEC.isLoaded());
	}
	private record Access(ComputerHostLimits limits, boolean available) {}

	private static boolean ownsLoadedServerBlock(SmartBlockEntity owner) {
		return !owner.isRemoved() && owner.getLevel() instanceof ServerLevel level
			&& level.hasChunkAt(owner.getBlockPos()) && level.getBlockEntity(owner.getBlockPos()) == owner;
	}
}
